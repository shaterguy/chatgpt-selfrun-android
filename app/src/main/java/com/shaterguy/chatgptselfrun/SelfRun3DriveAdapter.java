package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Drive transports pinned objects only. No title parsing, folder-order cursors or signal synthesis. */
final class SelfRun3DriveAdapter {
    private final Context context;
    private final SelfRunStore projection;
    private final SelfRun3Ledger ledger;
    private final DriveApiClient api = new DriveApiClient();
    private final BooleanSupplier permitted;
    private final PowerManager.WakeLock resultReadWakeLock;
    private final SelfRunRunLog diagnosticLog;
    private String verifiedToken = "";
    SelfRun3DriveAdapter(Context context, SelfRunStore projection, SelfRun3Ledger ledger, BooleanSupplier permitted) {
        this.context = context.getApplicationContext(); this.projection = projection; this.ledger = ledger; this.permitted = permitted;
        PowerManager power = this.context.getSystemService(PowerManager.class);
        resultReadWakeLock = power == null ? null : power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":selfrun3-result-read");
        if (resultReadWakeLock != null) resultReadWakeLock.setReferenceCounted(false);
        diagnosticLog = new SelfRunRunLog(this.context);
    }
    SelfRun3Engine.State setup(String token, SelfRun3Engine.State original) throws Exception {
        verifyAccount(token, original);
        SelfRun3Engine.State s = ledger.load(original.taskId());
        DriveApiClient.Metadata base = api.getMetadata(token, s.config().optString("baseFolderId"));
        require(DriveApiClient.MIME_FOLDER.equals(base.mimeType) && !base.trashed && !base.shared && base.canAddChildren, "BASE_FOLDER_INVALID");
        if (s.resource("folderId").isEmpty()) s = pin(s, "folderId", api.generateFolderId(token));
        checkpoint();
        DriveApiClient.Metadata folder;
        try { folder = api.getMetadata(token, s.resource("folderId")); }
        catch (DriveApiClient.ApiException e) {
            if (e.status != 404) throw e;
            checkpoint();
            try { folder = api.createJobFolder(token, s.resource("folderId"), s.taskId(), base.id); }
            catch (DriveApiClient.ApiException race) { if (race.status != 409) throw race; folder = api.getMetadata(token, s.resource("folderId")); }
        }
        require(folder.id.equals(s.resource("folderId")) && base.id.equals(folder.parentId) && !folder.trashed && !folder.shared
                && DriveApiClient.MIME_FOLDER.equals(folder.mimeType) && s.taskId().equals(folder.appProperties.optString("job_id")), "TASK_FOLDER_INVALID");
        s = ensureDocument(token, s, "requirementDocumentId", "requirementCreateIntent", s.taskId() + "-requirements");
        String requirement = s.config().optString("requirement");
        DriveApiClient.DocumentSnapshot initial = api.readTurnDocumentSnapshot(token, s.resource("requirementDocumentId"));
        if (initial.text.trim().isEmpty()) {
            checkpoint(); api.initializeDocument(token, s.resource("requirementDocumentId"), requirement, initial.revisionId);
            initial = api.readTurnDocumentSnapshot(token, s.resource("requirementDocumentId"));
        }
        require(stripTerminalNewline(initial.text).equals(stripTerminalNewline(requirement)), "REQUIREMENT_READBACK_MISMATCH");
        uploadAttachments(token, s);
        return ledger.load(s.taskId());
    }
    SelfRun3Engine.State prepareTurn(String token, SelfRun3Engine.State original) throws Exception {
        verifyAccount(token, original);
        SelfRun3Engine.State s = ensureDocument(token, original, "resultDocumentId", "resultCreateIntent", original.taskId() + "-" + original.turnId());
        validateDocument(token, s, s.resource("resultDocumentId"));
        DriveApiClient.DocumentSnapshot existing = api.readTurnDocumentSnapshot(token, s.resource("resultDocumentId"));
        if (existing.text.trim().isEmpty()) {
            checkpoint(); api.initializeDocument(token, s.resource("resultDocumentId"), SelfRun3Engine.emptyResult(s).toString(), existing.revisionId);
            existing = api.readTurnDocumentSnapshot(token, s.resource("resultDocumentId"));
        }
        JSONObject parsed = SelfRun3Engine.parseResult(existing.text, s);
        require(parsed == null, "RESULT_EXISTS_BEFORE_DISPATCH");
        String seed = SelfRun3Engine.emptyResult(s).toString();
        if (SelfRun3ResultWatchdog.fingerprint(seed).equals(SelfRun3ResultWatchdog.fingerprint(existing.text))) {
            JSONObject payload = new JSONObject();
            SelfRun3Engine.put(payload, "documentId", s.resource("resultDocumentId"));
            SelfRun3Engine.put(payload, "fingerprint", SelfRun3ResultWatchdog.fingerprint(existing.text));
            s = ledger.apply(new SelfRun3Engine.Event(s.turnId() + ":result-baseline",
                    SelfRun3Engine.Kind.RESULT_BASELINE, s.taskId(), s.turnId(), payload)).execution(s.turnId());
        }
        return s;
    }
    static final class ResultObservation {
        final String version;
        final String rawBody;
        final String candidateBody;
        ResultObservation(String version, String rawBody, String candidateBody) {
            this.version = version; this.rawBody = rawBody; this.candidateBody = candidateBody;
        }
    }
    ResultObservation observeResult(String token, SelfRun3Engine.State s) throws Exception {
        acquireResultReadWakeLock();
        diagnosticLog.record(projection, "V3_RESULT_READ", "stage=START;turn=" + s.turn());
        try {
            verifyAccount(token, s);
            DriveApiClient.Metadata metadata = validateDocument(token, s, s.resource("resultDocumentId"));
            checkpoint();
            String raw = api.readTurnDocumentSnapshot(token, s.resource("resultDocumentId")).text;
            if (raw == null) raw = "";
            String candidate = raw;
            if (raw.trim().isEmpty()) {
                diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_EMPTY;turn=" + s.turn());
                candidate = SelfRun3Engine.emptyResult(s).toString();
            } else {
                try {
                    JSONObject parsed = SelfRun3Engine.parseResult(raw, s);
                    if (parsed == null) diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_COMMIT;turn=" + s.turn());
                    else diagnosticLog.record(projection, "V3_RESULT_READ", "stage=COMMITTED;turn=" + s.turn());
                } catch (RuntimeException incompleteOrMalformed) {
                    String recovered = recoverSingleRedundantTrailingBrace(raw, s);
                    if (!recovered.isEmpty()) {
                        diagnosticLog.record(projection, "V3_RESULT_READ",
                                "stage=COMMITTED_RECOVERED_TRAILING_BRACE;turn=" + s.turn());
                        candidate = recovered;
                    } else if (isInvalidCommittedResult(raw, s)) {
                        diagnosticLog.record(projection, "V3_RESULT_READ", "stage=INVALID_COMMITTED;turn=" + s.turn());
                        throw new InvalidCommittedResultException();
                    } else {
                        diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_BODY;turn=" + s.turn());
                        candidate = SelfRun3Engine.emptyResult(s).toString();
                    }
                }
            }
            return new ResultObservation(metadata.modifiedTime + ":" + metadata.version, raw, candidate);
        } catch (Exception error) {
            diagnosticLog.record(projection, "V3_RESULT_READ", "stage=ERROR;type=" + error.getClass().getSimpleName());
            throw error;
        } finally {
            releaseResultReadWakeLock();
        }
    }
    String resultVersion(String token, SelfRun3Engine.State s) throws Exception {
        acquireResultReadWakeLock();
        try {
            verifyAccount(token, s);
            DriveApiClient.Metadata metadata = validateDocument(token, s, s.resource("resultDocumentId"));
            return metadata.modifiedTime + ":" + metadata.version;
        } finally {
            releaseResultReadWakeLock();
        }
    }
    String readResult(String token, SelfRun3Engine.State s) throws Exception {
        return observeResult(token, s).candidateBody;
    }
    private void acquireResultReadWakeLock() {
        if (resultReadWakeLock != null && !resultReadWakeLock.isHeld()) {
            resultReadWakeLock.acquire(SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS);
        }
    }
    private void releaseResultReadWakeLock() {
        if (resultReadWakeLock != null && resultReadWakeLock.isHeld()) {
            try { resultReadWakeLock.release(); } catch (Throwable ignored) { }
        }
    }
    static String recoverSingleRedundantTrailingBrace(String raw, SelfRun3Engine.State s) {
        if (raw == null || s == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() < 2 || !trimmed.endsWith("}")) return "";
        String candidate = trimmed.substring(0, trimmed.length() - 1).trim();
        if (candidate.isEmpty() || !candidate.endsWith("}")) return "";
        try {
            return SelfRun3Engine.parseResult(candidate, s) == null ? "" : candidate;
        } catch (RuntimeException stillInvalid) {
            return "";
        }
    }
    static boolean isInvalidCommittedResult(String raw, SelfRun3Engine.State s) {
        if (raw == null) return false;
        try {
            JSONObject body = SelfRun3StrictJson.parseObject(raw);
            if (!SelfRun3Engine.RESULT_SCHEMA.equals(body.opt("schema"))
                    || !s.taskId().equals(body.opt("task_id"))
                    || !s.turnId().equals(body.opt("turn_id"))
                    || !s.resource("resultDocumentId").equals(body.opt("document_id"))
                    || !Boolean.TRUE.equals(body.opt("committed"))) return false;
            try { SelfRun3Engine.parseResult(raw, s); return false; }
            catch (RuntimeException invalidPayload) { return true; }
        } catch (RuntimeException ambiguousIdentity) { return false; }
    }
    static final class InvalidCommittedResultException extends Exception { }

    private SelfRun3Engine.State ensureDocument(String token, SelfRun3Engine.State original, String key, String intentKey, String name) throws Exception {
        SelfRun3Engine.State s = ledger.loadExecution(original.taskId(), original.turnId());
        require(s != null, "STALE_TURN");
        if (!s.resource(key).isEmpty()) { validateDocument(token, s, s.resource(key)); return s; }
        checkpoint();
        String foundId = SelfRun3DriveLookup.findSingleDocumentId(token, name, s.resource("folderId"));
        DriveApiClient.Metadata found = foundId.isEmpty() ? null : api.getMetadata(token, foundId);
        if (found != null) return pin(s, key, found.id);
        // An earlier ambiguous native-Doc create can become visible later; never blindly repeat it.
        require(s.resource(intentKey).isEmpty(), "DOCUMENT_CREATE_UNCONFIRMED");
        s = pin(s, intentKey, name); checkpoint();
        DriveApiClient.Metadata created = api.createTurnDocument(token, name, s.resource("folderId"));
        // Preserve a successful create even when a pause arrives while the HTTP call is in flight.
        s = pin(s, key, created.id);
        validateDocument(token, s, created.id); return s;
    }
    private DriveApiClient.Metadata validateDocument(String token, SelfRun3Engine.State s, String id) throws Exception {
        checkpoint(); DriveApiClient.Metadata m = api.getMetadata(token, id);
        require(id.equals(m.id) && s.resource("folderId").equals(m.parentId) && DriveApiClient.MIME_DOCUMENT.equals(m.mimeType)
                && !m.trashed && !m.shared && m.isAppAuthorized, "DOCUMENT_BOUNDARY_MISMATCH");
        return m;
    }
    private void verifyAccount(String token, SelfRun3Engine.State s) throws Exception {
        checkpoint();
        require(s.config().optString("accountId").equals(projection.runDriveAccountId()), "ACCOUNT_BINDING_CHANGED");
        if (!token.equals(verifiedToken)) {
            require(s.config().optString("accountId").equals(api.getAccountPermissionId(token)), "ACCOUNT_MISMATCH"); verifiedToken = token;
        }
    }
    private void uploadAttachments(String token, SelfRun3Engine.State s) throws Exception {
        while (true) {
            checkpoint(); require(s.taskId().equals(projection.runId()), "STALE_ATTACHMENT_TASK");
            SelfRunStore.Attachment a = projection.nextUncommittedAttachment(); if (a == null) return;
            if (a.driveFileId.isEmpty()) { projection.reserveAttachmentFileId(a.index, api.generateFileId(token)); a = projection.nextUncommittedAttachment(); }
            DriveApiClient.Metadata existing = null;
            try { existing = api.getMetadata(token, a.driveFileId); }
            catch (DriveApiClient.ApiException e) { if (e.status != 404) throw e; }
            if (existing != null) {
                require(s.resource("folderId").equals(existing.parentId) && s.taskId().equals(existing.appProperties.optString("job_id"))
                        && "attachment".equals(existing.appProperties.optString("selfrun_kind")) && !existing.trashed && !existing.shared
                        && String.valueOf(a.index).equals(existing.appProperties.optString("attachment_index"))
                        && (a.size < 0 || existing.size == a.size), "ATTACHMENT_READBACK_MISMATCH");
                projection.markAttachmentCommitted(a.index); continue;
            }
            Uri uri = Uri.parse(a.uri); require("content".equals(uri.getScheme()), "ATTACHMENT_URI_INVALID");
            if (a.size < 0) {
                long size = 0; byte[] buffer = new byte[64 * 1024];
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    require(in != null, "ATTACHMENT_UNAVAILABLE"); int n;
                    while ((n = in.read(buffer)) != -1) {
                        checkpoint();
                        require(size <= Long.MAX_VALUE - n, "ATTACHMENT_SIZE_OVERFLOW");
                        size += n;
                    }
                }
                projection.updateAttachmentSize(a.index, size); a = projection.nextUncommittedAttachment();
            }
            projection.markAttachmentUploading(a.index); checkpoint();
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                api.uploadAttachmentResumable(token, a.driveFileId, s.taskId(), s.resource("folderId"), a.index, a.name, a.mimeType, a.size, in);
            }
            // The next iteration validates the exact persisted file, including an ambiguous upload.
        }
    }
    private SelfRun3Engine.State pin(SelfRun3Engine.State s, String key, String value) {
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "key", key); SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(new SelfRun3Engine.Event("resource-" + UUID.randomUUID(), SelfRun3Engine.Kind.RESOURCE, s.taskId(), s.turnId(), payload)).execution(s.turnId());
    }
    private void checkpoint() throws IOException { if (!permitted.getAsBoolean()) throw new IOException("OPERATION_CANCELLED"); }
    private static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
    private static String stripTerminalNewline(String s) { return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s; }
}
