package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.net.Uri;
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
    private String verifiedToken = "";
    SelfRun3DriveAdapter(Context context, SelfRunStore projection, SelfRun3Ledger ledger, BooleanSupplier permitted) {
        this.context = context.getApplicationContext(); this.projection = projection; this.ledger = ledger; this.permitted = permitted;
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
        return s;
    }
    String readResult(String token, SelfRun3Engine.State s) throws Exception {
        verifyAccount(token, s); validateDocument(token, s, s.resource("resultDocumentId"));
        checkpoint(); return api.readTurnDocumentSnapshot(token, s.resource("resultDocumentId")).text;
    }
    private SelfRun3Engine.State ensureDocument(String token, SelfRun3Engine.State original, String key, String intentKey, String name) throws Exception {
        SelfRun3Engine.State s = ledger.load(original.taskId());
        require(s.turnId().equals(original.turnId()), "STALE_TURN");
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
    private void validateDocument(String token, SelfRun3Engine.State s, String id) throws Exception {
        checkpoint(); DriveApiClient.Metadata m = api.getMetadata(token, id);
        require(id.equals(m.id) && s.resource("folderId").equals(m.parentId) && DriveApiClient.MIME_DOCUMENT.equals(m.mimeType)
                && !m.trashed && !m.shared && m.isAppAuthorized, "DOCUMENT_BOUNDARY_MISMATCH");
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
                    while ((n = in.read(buffer)) != -1) { checkpoint(); size += n; require(size <= SelfRunStore.MAX_ATTACHMENT_BYTES, "ATTACHMENT_TOO_LARGE"); }
                }
                projection.updateAttachmentSize(a.index, size); a = projection.nextUncommittedAttachment();
            }
            require(a.size <= SelfRunStore.MAX_ATTACHMENT_BYTES, "ATTACHMENT_TOO_LARGE");
            projection.markAttachmentUploading(a.index); checkpoint();
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                api.uploadAttachmentResumable(token, a.driveFileId, s.taskId(), s.resource("folderId"), a.index, a.name, a.mimeType, a.size, in);
            }
            // The next iteration validates the exact persisted file, including an ambiguous upload.
        }
    }
    private SelfRun3Engine.State pin(SelfRun3Engine.State s, String key, String value) {
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "key", key); SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(new SelfRun3Engine.Event("resource-" + UUID.randomUUID(), SelfRun3Engine.Kind.RESOURCE, s.taskId(), s.turnId(), payload));
    }
    private void checkpoint() throws IOException { if (!permitted.getAsBoolean()) throw new IOException("OPERATION_CANCELLED"); }
    private static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
    private static String stripTerminalNewline(String s) { return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s; }
}
