package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Minimal Drive v3 and Docs v1 REST client. Tokens are memory-only and never logged. */
final class DriveApiClient {
    static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    static final String MIME_DOCUMENT = "application/vnd.google-apps.document";
    static final String MIME_OCTET_STREAM = "application/octet-stream";
    private static final String GOOGLE_WORKSPACE_MIME_PREFIX = "application/vnd.google-apps.";
    private static final String FILE_FIELDS = "id,name,mimeType,size,parents,trashed,appProperties,version,"
            + "createdTime,modifiedTime,webViewLink,isAppAuthorized,shared,capabilities(canAddChildren)";
    private static final String POLL_FIELDS = "id,name,mimeType,parents,trashed,version,createdTime,modifiedTime,shared";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DOCUMENT_CHARS = 1_000_000;
    private static final int MAX_SIGNAL_DOCUMENTS = 2_000;
    private static final int UPLOAD_BUFFER_BYTES = 256 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("www.googleapis.com", "docs.googleapis.com")));

    private final Object signalSnapshotLock = new Object();
    private String pendingSignalTurnDocumentId = "";
    private String pendingSignalRunId = "";
    private List<Metadata> pendingSignalDocuments = Collections.emptyList();

    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
        boolean retryable() { return status == 429 || status >= 500; }
    }

    /** A non-idempotent create may have reached Drive even though no response was received. */
    static final class OutcomeUnknownException extends IOException {
        OutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
    }

    /** A resumable media session did not reach a final committed response. */
    static final class UploadIncompleteException extends IOException {
        UploadIncompleteException(String message) { super(message); }
    }

    static final class Metadata {
        final String id;
        final String name;
        final String mimeType;
        final long size;
        final String parentId;
        final boolean trashed;
        final String version;
        final String createdTime;
        final String modifiedTime;
        final String webViewLink;
        final JSONObject appProperties;
        final boolean isAppAuthorized;
        final boolean shared;
        final boolean canAddChildren;

        Metadata(JSONObject json) {
            id = json.optString("id", "");
            name = json.optString("name", "");
            mimeType = json.optString("mimeType", "");
            size = parseSize(json.opt("size"));
            JSONArray parents = json.optJSONArray("parents");
            parentId = parents == null || parents.length() != 1 ? "" : parents.optString(0, "");
            trashed = json.optBoolean("trashed", false);
            version = String.valueOf(json.opt("version") == null ? "" : json.opt("version"));
            createdTime = json.optString("createdTime", "");
            modifiedTime = json.optString("modifiedTime", "");
            webViewLink = json.optString("webViewLink", "");
            appProperties = json.optJSONObject("appProperties") == null
                    ? new JSONObject() : json.optJSONObject("appProperties");
            isAppAuthorized = json.optBoolean("isAppAuthorized", false);
            shared = json.optBoolean("shared", false);
            JSONObject capabilities = json.optJSONObject("capabilities");
            canAddChildren = capabilities != null && capabilities.optBoolean("canAddChildren", false);
        }

        private Metadata(Metadata source, String pollVersion, String pollModifiedTime) {
            id = source.id;
            name = source.name;
            mimeType = source.mimeType;
            size = source.size;
            parentId = source.parentId;
            trashed = source.trashed;
            version = pollVersion == null ? "" : pollVersion;
            createdTime = source.createdTime;
            modifiedTime = pollModifiedTime == null ? "" : pollModifiedTime;
            webViewLink = source.webViewLink;
            appProperties = source.appProperties;
            isAppAuthorized = source.isAppAuthorized;
            shared = source.shared;
            canAddChildren = source.canAddChildren;
        }
    }

    /** One coherent Docs read used for optimistic recovery-claim arbitration. */
    static final class DocumentSnapshot {
        final String text;
        final String revisionId;
        final String tabId;
        final int claimStartIndex;
        final int claimEndIndex;
        private final JSONObject namedRanges;

        DocumentSnapshot(String text, String revisionId, String tabId, int claimStartIndex,
                         int claimEndIndex, JSONObject namedRanges) {
            this.text = text == null ? "" : text;
            this.revisionId = revisionId == null ? "" : revisionId;
            this.tabId = tabId == null ? "" : tabId;
            this.claimStartIndex = claimStartIndex;
            this.claimEndIndex = claimEndIndex;
            this.namedRanges = namedRanges == null ? new JSONObject() : namedRanges;
        }

        boolean hasNamedRange(String name) {
            if (!validNamedRangeName(name)) return false;
            JSONObject matches = namedRanges.optJSONObject(name);
            JSONArray ranges = matches == null ? null : matches.optJSONArray("namedRanges");
            return ranges != null && ranges.length() > 0;
        }
    }

    private static final class PendingSignalBatch {
        final String runId;
        final List<Metadata> documents;
        PendingSignalBatch(String runId, List<Metadata> documents) {
            this.runId = runId == null ? "" : runId;
            this.documents = documents == null ? Collections.emptyList() : documents;
        }
    }

    String getAccountPermissionId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", accessToken, null);
        JSONObject user = json.optJSONObject("user");
        String id = user == null ? "" : user.optString("permissionId", "");
        if (!validOpaqueAccountId(id)) throw new IllegalStateException("Drive account permissionId unavailable");
        return id;
    }

    Metadata getMetadata(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId + "?supportsAllDrives=true&fields=" + FILE_FIELDS;
        return new Metadata(request("GET", endpoint, accessToken, null));
    }

    Metadata getPollMetadata(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId + "?supportsAllDrives=true&fields=" + POLL_FIELDS;
        Metadata turnDocument = new Metadata(request("GET", endpoint, accessToken, null));
        if (turnDocument.trashed || turnDocument.shared || !MIME_DOCUMENT.equals(turnDocument.mimeType)
                || !validFileId(turnDocument.parentId) || !SelfRunProtocolRules.validRunId(turnDocument.name)) {
            stageSignalDocuments(fileId, "", Collections.emptyList());
            return turnDocument;
        }
        List<Metadata> signals = listSignalDocuments(accessToken, turnDocument.name, turnDocument.parentId);
        stageSignalDocuments(fileId, turnDocument.name, signals);
        if (signals.isEmpty()) return turnDocument;
        Metadata latest = signals.get(signals.size() - 1);
        return new Metadata(turnDocument, "signal:" + latest.id, latest.createdTime);
    }

    private List<Metadata> listSignalDocuments(String accessToken, String runId, String parentId) throws Exception {
        requireParent(parentId);
        if (!SelfRunProtocolRules.validRunId(runId)) throw new IllegalArgumentException("valid run id required");
        String q = "'" + parentId + "' in parents and trashed = false and mimeType = '" + MIME_DOCUMENT + "'";
        String fields = "nextPageToken,files(" + FILE_FIELDS + ")";
        ArrayList<Metadata> result = new ArrayList<>();
        String pageToken = "";
        do {
            String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
                    + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                    + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name())
                    + "&pageSize=1000"
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name()));
            JSONObject page = request("GET", endpoint, accessToken, null, false);
            JSONArray files = page.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) {
                JSONObject raw = files.optJSONObject(i);
                if (raw == null) continue;
                Metadata candidate = new Metadata(raw);
                if (DriveSignalDocumentTransport.isCandidate(candidate, runId, parentId)) result.add(candidate);
                if (result.size() > MAX_SIGNAL_DOCUMENTS) {
                    throw new IllegalStateException("too many signal documents in one SelfRun job");
                }
            }
            pageToken = page.optString("nextPageToken", "");
        } while (!pageToken.isEmpty());
        result.sort(DriveSignalDocumentTransport.comparator(runId));
        return Collections.unmodifiableList(result);
    }

    private void stageSignalDocuments(String turnDocumentId, String runId, List<Metadata> documents) {
        synchronized (signalSnapshotLock) {
            pendingSignalTurnDocumentId = turnDocumentId == null ? "" : turnDocumentId;
            pendingSignalRunId = runId == null ? "" : runId;
            pendingSignalDocuments = documents == null || documents.isEmpty()
                    ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(documents));
        }
    }

    private PendingSignalBatch consumeSignalDocuments(String turnDocumentId) {
        synchronized (signalSnapshotLock) {
            if (!turnDocumentId.equals(pendingSignalTurnDocumentId) || pendingSignalDocuments.isEmpty()) {
                return new PendingSignalBatch("", Collections.emptyList());
            }
            PendingSignalBatch batch = new PendingSignalBatch(pendingSignalRunId, pendingSignalDocuments);
            pendingSignalTurnDocumentId = "";
            pendingSignalRunId = "";
            pendingSignalDocuments = Collections.emptyList();
            return batch;
        }
    }

    Metadata findSingleTurnDocument(String accessToken, String jobId, String parentId) throws Exception {
        requireParent(parentId);
        String q = "'" + parentId + "' in parents and trashed = false and mimeType = '" + MIME_DOCUMENT + "'";
        String fields = "files(" + FILE_FIELDS + ")";
        String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
                + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name()) + "&pageSize=10";
        JSONArray files = request("GET", endpoint, accessToken, null, false).optJSONArray("files");
        Metadata match = null;
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject raw = files.optJSONObject(i);
            if (raw == null) continue;
            Metadata candidate = new Metadata(raw);
            if (!jobId.equals(candidate.name) || !MIME_DOCUMENT.equals(candidate.mimeType)
                    || !parentId.equals(candidate.parentId) || candidate.trashed
                    || !jobId.equals(candidate.appProperties.optString("job_id"))
                    || !"turn_document".equals(candidate.appProperties.optString("selfrun_kind"))) continue;
            if (match != null) throw new IllegalStateException("multiple turn documents found for one SelfRun job");
            match = candidate;
        }
        return match;
    }

    String generateFileId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files", accessToken, null, false);
        JSONArray ids = json.optJSONArray("ids");
        String id = ids == null || ids.length() != 1 ? "" : ids.optString(0, "");
        requireFileId(id);
        return id;
    }

    String generateFolderId(String accessToken) throws Exception { return generateFileId(accessToken); }

    Metadata createJobFolder(String accessToken, String folderId, String jobId, String parentId) throws Exception {
        requireFileId(folderId); requireParent(parentId);
        JSONObject body = baseMetadata(jobId, MIME_FOLDER, parentId, "job_folder").put("id", folderId);
        return create(accessToken, body, false);
    }

    Metadata uploadAttachmentResumable(String accessToken, String fileId, String jobId, String parentId,
                                       int attachmentIndex, String fileName, String mimeType, long contentLength,
                                       InputStream content) throws Exception {
        requireFileId(fileId); requireParent(parentId);
        if (jobId == null || jobId.isEmpty()) throw new IllegalArgumentException("job id required");
        if (attachmentIndex < 0) throw new IllegalArgumentException("attachment index required");
        if (fileName == null || fileName.isEmpty() || fileName.length() > 180) throw new IllegalArgumentException("safe attachment name required");
        if (!validAttachmentMimeType(mimeType)) throw new IllegalArgumentException("valid attachment MIME type required");
        if (contentLength < 0) throw new IllegalArgumentException("known attachment length required");
        if (content == null) throw new IllegalArgumentException("attachment stream required");

        JSONObject metadata = new JSONObject()
                .put("id", fileId)
                .put("name", fileName)
                .put("mimeType", mimeType)
                .put("parents", new JSONArray().put(parentId))
                .put("appProperties", new JSONObject()
                        .put("job_id", jobId)
                        .put("selfrun_kind", "attachment")
                        .put("attachment_index", String.valueOf(attachmentIndex)));
        String session = startResumableSession(accessToken, metadata, mimeType, contentLength);
        HttpURLConnection connection = null;
        try {
            URL url = requireAllowedUrl(session);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + requireToken(accessToken));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", mimeType);
            connection.setRequestProperty("Content-Length", String.valueOf(contentLength));
            connection.setRequestProperty("Content-Range", contentLength == 0
                    ? "bytes */0" : "bytes 0-" + (contentLength - 1) + "/" + contentLength);
            connection.setFixedLengthStreamingMode(contentLength);
            long written = 0L;
            byte[] buffer = new byte[UPLOAD_BUFFER_BYTES];
            try (InputStream input = new BufferedInputStream(content); OutputStream output = connection.getOutputStream()) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    written += read;
                    if (written > contentLength) throw new IOException("attachment grew while uploading");
                    output.write(buffer, 0, read);
                }
                if (written != contentLength) throw new IOException("attachment length changed while uploading");
            }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(responseStream);
            if (status == 308) throw new UploadIncompleteException("resumable upload requires retry");
            if (status < 200 || status >= 300) throw apiException(status, response);
            JSONObject json = response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
            return new Metadata(json);
        } catch (IOException error) {
            throw new OutcomeUnknownException("attachment upload result unknown", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String startResumableSession(String accessToken, JSONObject metadata, String mimeType, long contentLength)
            throws Exception {
        URL url = requireAllowedUrl("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
                + "&supportsAllDrives=true&fields=" + URLEncoder.encode(FILE_FIELDS, StandardCharsets.UTF_8.name()));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + requireToken(accessToken));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("X-Upload-Content-Type", mimeType);
            connection.setRequestProperty("X-Upload-Content-Length", String.valueOf(contentLength));
            byte[] bytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            String response = readBounded(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw apiException(status, response);
            String location = connection.getHeaderField("Location");
            if (location == null || location.isEmpty()) throw new IOException("resumable session location missing");
            requireAllowedUrl(location);
            return location;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    Metadata createTurnDocument(String accessToken, String jobId, String parentId) throws Exception {
        requireParent(parentId);
        JSONObject body = baseMetadata(jobId, MIME_DOCUMENT, parentId, "turn_document");
        try {
            Metadata created = create(accessToken, body, true);
            if (!validFileId(created.id)) throw new OutcomeUnknownException("native document create response omitted its id", null);
            return created;
        } catch (ApiException definiteFailure) { throw definiteFailure; }
        catch (OutcomeUnknownException unknown) { throw unknown; }
        catch (Throwable responseFailure) { throw new OutcomeUnknownException("native document create response was not trustworthy", responseFailure); }
    }

    void initializeDocument(String accessToken, String documentId, String initialText) throws Exception {
        requireFileId(documentId);
        if (initialText == null || initialText.isEmpty()) throw new IllegalArgumentException("initial text required");
        JSONObject insert = new JSONObject().put("location", new JSONObject().put("index", 1)).put("text", initialText);
        JSONObject body = new JSONObject().put("requests", new JSONArray().put(new JSONObject().put("insertText", insert)));
        request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate", accessToken, body);
    }

    DocumentSnapshot readDocumentSnapshot(String accessToken, String documentId) throws Exception {
        requireFileId(documentId);
        PendingSignalBatch batch = consumeSignalDocuments(documentId);
        if (!batch.documents.isEmpty()) return readSignalDocumentSnapshot(accessToken, batch);
        return readNativeDocumentSnapshot(accessToken, documentId);
    }

    private DocumentSnapshot readSignalDocumentSnapshot(String accessToken, PendingSignalBatch batch) throws Exception {
        if (!SelfRunProtocolRules.validRunId(batch.runId)) throw new IllegalStateException("signal batch run id invalid");
        StringBuilder text = new StringBuilder();
        String latestId = "";
        for (Metadata metadata : batch.documents) {
            String logical;
            if (DriveSignalDocumentTransport.needsBodyRead(metadata.name)) {
                String body = readNativeDocumentSnapshot(accessToken, metadata.id).text;
                logical = DriveSignalDocumentTransport.materialize(metadata.name, body, batch.runId);
            } else {
                logical = DriveSignalDocumentTransport.materialize(metadata.name, "", batch.runId);
            }
            if (text.length() + logical.length() + 1 > MAX_DOCUMENT_CHARS) {
                throw new IllegalStateException("signal document log too large");
            }
            text.append(logical).append('\n');
            latestId = metadata.id;
        }
        return new DocumentSnapshot(text.toString(), "signal-batch:" + latestId, "", -1, -1, new JSONObject());
    }

    private DocumentSnapshot readNativeDocumentSnapshot(String accessToken, String documentId) throws Exception {
        requireFileId(documentId);
        JSONObject document = request("GET", "https://docs.googleapis.com/v1/documents/" + documentId + "?includeTabsContent=true", accessToken, null);
        String revisionId = document.optString("revisionId", "");
        if (revisionId.isEmpty()) throw new IllegalStateException("execution document revisionId missing");
        JSONArray tabs = document.optJSONArray("tabs");
        if (tabs == null || tabs.length() != 1) throw new IllegalStateException("execution document must contain exactly one tab");
        JSONObject tab = tabs.optJSONObject(0);
        if (tab == null || (tab.optJSONArray("childTabs") != null && tab.optJSONArray("childTabs").length() > 0)) throw new IllegalStateException("nested execution document tabs are forbidden");
        JSONObject tabProperties = tab.optJSONObject("tabProperties");
        String tabId = tabProperties == null ? "" : tabProperties.optString("tabId", "");
        if (tabId.isEmpty()) throw new IllegalStateException("execution document tabId missing");
        JSONObject documentTab = tab.optJSONObject("documentTab");
        JSONObject body = documentTab == null ? null : documentTab.optJSONObject("body");
        if (body == null) throw new IllegalStateException("execution document body missing");
        JSONArray content = body.optJSONArray("content");
        StringBuilder text = new StringBuilder(); appendTextRuns(content, text);
        if (text.length() > MAX_DOCUMENT_CHARS) throw new IllegalStateException("execution document too large");
        int[] claimRange = firstClaimRange(content);
        JSONObject namedRanges = documentTab.optJSONObject("namedRanges");
        return new DocumentSnapshot(text.toString(), revisionId, tabId,
                claimRange[0], claimRange[1], namedRanges);
    }

    String readDocumentText(String accessToken, String documentId) throws Exception {
        return readDocumentSnapshot(accessToken, documentId).text;
    }

    /**
     * Atomically creates a parser-invisible named-range recovery claim against one exact Docs revision.
     * Returns false only when a concurrent document edit won the revision race; ambiguous transport outcomes
     * are thrown so the caller can read back the deterministic claim before retrying.
     */
    boolean createNamedRangeClaim(String accessToken, String documentId, DocumentSnapshot snapshot,
                                  String claimName) throws Exception {
        requireFileId(documentId);
        if (snapshot == null || snapshot.revisionId.isEmpty()) throw new IllegalArgumentException("document snapshot required");
        if (!validNamedRangeName(claimName)) throw new IllegalArgumentException("safe named range claim required");
        if (snapshot.hasNamedRange(claimName)) return true;
        if (snapshot.claimStartIndex < 1 || snapshot.claimEndIndex <= snapshot.claimStartIndex) {
            throw new IllegalStateException("valid recovery claim range unavailable");
        }
        JSONObject range = new JSONObject().put("startIndex", snapshot.claimStartIndex)
                .put("endIndex", snapshot.claimEndIndex).put("tabId", snapshot.tabId);
        JSONObject create = new JSONObject().put("name", claimName).put("range", range);
        JSONObject body = new JSONObject()
                .put("requests", new JSONArray().put(new JSONObject().put("createNamedRange", create)))
                .put("writeControl", new JSONObject().put("requiredRevisionId", snapshot.revisionId));
        try {
            request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate",
                    accessToken, body, true, "watchdog recovery claim result unknown");
            return true;
        } catch (ApiException api) {
            if (api.status != 400) throw api;
            DocumentSnapshot current = readNativeDocumentSnapshot(accessToken, documentId);
            if (current.hasNamedRange(claimName)) return true;
            if (!snapshot.revisionId.equals(current.revisionId)) return false;
            throw api;
        }
    }

    private Metadata create(String accessToken, JSONObject body, boolean outcomeSensitive) throws Exception {
        JSONObject json = request("POST", "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&fields=" + FILE_FIELDS, accessToken, body, outcomeSensitive);
        return new Metadata(json);
    }

    private static JSONObject baseMetadata(String name, String mimeType, String parentId, String kind) throws Exception {
        return new JSONObject().put("name", name).put("mimeType", mimeType).put("parents", new JSONArray().put(parentId))
                .put("appProperties", new JSONObject().put("job_id", name).put("selfrun_kind", kind));
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body) throws Exception { return request(method, endpoint, accessToken, body, false); }
    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body, boolean outcomeSensitive) throws Exception {
        return request(method, endpoint, accessToken, body, outcomeSensitive, "native document create result unknown");
    }
    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body,
                                      boolean outcomeSensitive, String outcomeUnknownMessage) throws Exception {
        URL url = requireAllowedUrl(endpoint);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection(); connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000); connection.setReadTimeout(30_000); connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + requireToken(accessToken)); connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(stream);
            if (status < 200 || status >= 300) {
                ApiException api = apiException(status, response);
                if (outcomeSensitive && (status == 408 || status == 429 || status >= 500)) throw new OutcomeUnknownException(outcomeUnknownMessage, api);
                throw api;
            }
            return response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
        } catch (IOException error) {
            if (error instanceof OutcomeUnknownException) throw error;
            if (outcomeSensitive) throw new OutcomeUnknownException(outcomeUnknownMessage, error);
            throw error;
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static ApiException apiException(int status, String response) {
        String reason = "HTTP " + status;
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            String message = error == null ? "" : error.optString("status", "");
            if (!message.isEmpty()) reason += " " + message;
        } catch (Throwable ignored) {}
        return new ApiException(status, reason);
    }

    private static URL requireAllowedUrl(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || !ALLOWED_HOSTS.contains(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new IllegalArgumentException("Drive endpoint is not allowlisted");
        }
        return url;
    }

    private static String requireToken(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) throw new IllegalArgumentException("access token required");
        return accessToken;
    }

    private static String readBounded(InputStream source) throws Exception {
        if (source == null) return "";
        try (InputStream input = new BufferedInputStream(source); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0; int read;
            while ((read = input.read(buffer)) >= 0) { total += read; if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Drive response too large"); output.write(buffer, 0, read); }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void appendTextRuns(Object value, StringBuilder output) {
        if (output.length() > MAX_DOCUMENT_CHARS) throw new IllegalStateException("execution document too large");
        if (value instanceof JSONObject object) {
            JSONObject textRun = object.optJSONObject("textRun"); if (textRun != null) output.append(textRun.optString("content", ""));
            Iterator<String> keys = object.keys(); while (keys.hasNext()) { String key = keys.next(); if (!"textRun".equals(key)) appendTextRuns(object.opt(key), output); }
        } else if (value instanceof JSONArray array) { for (int i = 0; i < array.length(); i++) appendTextRuns(array.opt(i), output); }
    }

    private static int[] firstClaimRange(JSONArray content) {
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null || item.optJSONObject("paragraph") == null) continue;
            int start = item.optInt("startIndex", -1), end = item.optInt("endIndex", -1);
            if (start >= 1 && end > start) return new int[]{start, Math.min(end, start + 1)};
        }
        throw new IllegalStateException("execution document has no claimable paragraph range");
    }

    private static boolean validNamedRangeName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 256
                && value.matches("[A-Za-z0-9._-]{1,256}");
    }

    private static long parseSize(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return -1L;
        try { long parsed = Long.parseLong(String.valueOf(value)); return parsed < 0 ? -1L : parsed; }
        catch (Throwable ignored) { return -1L; }
    }

    static boolean validMimeType(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    }

    static boolean validAttachmentMimeType(String value) {
        return validMimeType(value) && !value.toLowerCase(java.util.Locale.ROOT).startsWith(GOOGLE_WORKSPACE_MIME_PREFIX);
    }

    static String normalizeAttachmentMimeType(String value) {
        return validAttachmentMimeType(value) ? value : MIME_OCTET_STREAM;
    }
    static boolean validFileId(String value) { return value != null && !"root".equals(value) && value.matches("[A-Za-z0-9_-]{8,200}"); }
    static boolean validOpaqueAccountId(String value) { return value != null && value.matches("[A-Za-z0-9._-]{5,256}"); }
    private static void requireFileId(String fileId) { if (!validFileId(fileId)) throw new IllegalArgumentException("valid Drive file id required"); }
    static void requireParent(String parentId) { if (!validFileId(parentId)) throw new IllegalArgumentException("explicit Drive parent id required; root fallback is forbidden"); }
}
