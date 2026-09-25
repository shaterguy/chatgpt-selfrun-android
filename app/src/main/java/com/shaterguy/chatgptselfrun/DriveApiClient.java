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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Minimal V3-only Drive v3 and Docs v1 REST client. Tokens are memory-only and never logged. */
final class DriveApiClient {
    static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    static final String MIME_DOCUMENT = "application/vnd.google-apps.document";
    static final String MIME_OCTET_STREAM = "application/octet-stream";
    static final String MIME_JSON = "application/json";
    private static final String GOOGLE_WORKSPACE_MIME_PREFIX = "application/vnd.google-apps.";
    private static final String FILE_FIELDS = "id,name,mimeType,size,parents,trashed,appProperties,version,"
            + "createdTime,modifiedTime,webViewLink,isAppAuthorized,shared,driveId,capabilities(canAddChildren)";
    private static final String POLL_FIELDS = "id,name,mimeType,parents,trashed,version,createdTime,modifiedTime,shared";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DOCUMENT_CHARS = 1_000_000;
    private static final int UPLOAD_BUFFER_BYTES = 256 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("www.googleapis.com", "docs.googleapis.com")));

    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
        boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    }

    /** The request body was not opened, so a native create could not have been submitted. */
    static final class CreateNotSubmittedException extends IOException {
        CreateNotSubmittedException(String message, Throwable cause) { super(message, cause); }
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
        final String driveId;
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
            driveId = json.optString("driveId", "");
            JSONObject capabilities = json.optJSONObject("capabilities");
            canAddChildren = capabilities != null && capabilities.optBoolean("canAddChildren", false);
        }
    }

    /** One coherent native Google Docs read used by the V3 Drive adapter. */
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

    String getAccountPermissionId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", accessToken, null);
        JSONObject user = json.optJSONObject("user");
        String id = user == null ? "" : user.optString("permissionId", "");
        if (!validOpaqueAccountId(id)) throw new IllegalStateException("Drive account permissionId unavailable");
        return id;
    }

    Metadata getMetadata(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId
                + "?supportsAllDrives=true&fields=" + FILE_FIELDS;
        return new Metadata(request("GET", endpoint, accessToken, null));
    }

    Metadata getPollMetadata(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId
                + "?supportsAllDrives=true&fields=" + POLL_FIELDS;
        return new Metadata(request("GET", endpoint, accessToken, null));
    }

    /** Compatibility overload: V3 ignores retired signal-document transport and reads only the pinned object. */
    Metadata getPollMetadata(String accessToken, String fileId, boolean ignoredRetiredTransport) throws Exception {
        return getPollMetadata(accessToken, fileId);
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

    Metadata findLatestServerDispatch(String accessToken, String requestId, String parentId) throws Exception {
        requireParent(parentId);
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("safe dispatch request id required");
        }
        String q = "'" + parentId + "' in parents and trashed = false and mimeType = '" + MIME_JSON + "'"
                + " and appProperties has { key='selfrun_kind' and value='server_dispatch' }"
                + " and appProperties has { key='request_id' and value='" + requestId + "' }";
        String fields = "files(" + FILE_FIELDS + ")";
        String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
                + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name()) + "&pageSize=100";
        JSONArray files = request("GET", endpoint, accessToken, null, false).optJSONArray("files");
        Metadata match = null;
        int bestAttempt = -1;
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject raw = files.optJSONObject(i);
            if (raw == null) continue;
            Metadata candidate = new Metadata(raw);
            if (!MIME_JSON.equals(candidate.mimeType) || !parentId.equals(candidate.parentId)
                    || candidate.trashed
                    || !"server_dispatch".equals(candidate.appProperties.optString("selfrun_kind"))
                    || !requestId.equals(candidate.appProperties.optString("request_id"))) continue;
            int attempt;
            try { attempt = Integer.parseInt(candidate.appProperties.optString("dispatch_attempt", "0")); }
            catch (NumberFormatException invalid) { attempt = 0; }
            if (match == null || attempt > bestAttempt) {
                match = candidate;
                bestAttempt = attempt;
            }
        }
        return match;
    }

    String getStartPageToken(String accessToken) throws Exception {
        JSONObject json = request("GET",
                "https://www.googleapis.com/drive/v3/changes/startPageToken?supportsAllDrives=true",
                accessToken, null, false);
        return requireChangeToken(json.optString("startPageToken", ""));
    }

    Metadata findSingleTurnDocumentSince(String accessToken, String startPageToken,
                                         String jobId, String parentId) throws Exception {
        requireParent(parentId);
        if (jobId == null || !jobId.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("safe V3 document name required");
        }
        String pageToken = requireChangeToken(startPageToken);
        Set<String> seenTokens = new HashSet<>();
        Metadata match = null;
        while (true) {
            if (!seenTokens.add(pageToken)) {
                throw new IllegalStateException("DOCUMENT_CREATE_CHANGE_TOKEN_LOOP");
            }
            String fields = "nextPageToken,newStartPageToken,changes(fileId,removed,file(" + FILE_FIELDS + "))";
            String endpoint = "https://www.googleapis.com/drive/v3/changes?supportsAllDrives=true"
                    + "&includeItemsFromAllDrives=true&spaces=drive&pageSize=1000&pageToken="
                    + URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name())
                    + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name());
            JSONObject page = request("GET", endpoint, accessToken, null, false);
            JSONArray changes = page.optJSONArray("changes");
            if (changes != null) for (int i = 0; i < changes.length(); i++) {
                JSONObject change = changes.optJSONObject(i);
                if (change == null || change.optBoolean("removed", false)) continue;
                JSONObject raw = change.optJSONObject("file");
                if (raw == null) continue;
                Metadata candidate = new Metadata(raw);
                if (!jobId.equals(candidate.name) || !MIME_DOCUMENT.equals(candidate.mimeType)
                        || !parentId.equals(candidate.parentId) || candidate.trashed || candidate.shared
                        || !candidate.isAppAuthorized
                        || !jobId.equals(candidate.appProperties.optString("job_id"))
                        || !"turn_document".equals(candidate.appProperties.optString("selfrun_kind"))) continue;
                if (match != null && !match.id.equals(candidate.id)) {
                    throw new IllegalStateException("DOCUMENT_CREATE_DUPLICATE");
                }
                match = candidate;
            }
            String next = page.optString("nextPageToken", "");
            if (next.isEmpty()) {
                requireChangeToken(page.optString("newStartPageToken", ""));
                return match;
            }
            pageToken = requireChangeToken(next);
        }
    }

    private static String requireChangeToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 1024) {
            throw new IllegalStateException("DOCUMENT_CREATE_CHANGE_TOKEN_INVALID");
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch < 0x21 || ch > 0x7e) {
                throw new IllegalStateException("DOCUMENT_CREATE_CHANGE_TOKEN_INVALID");
            }
        }
        return token;
    }

    String generateFileId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files",
                accessToken, null, false);
        JSONArray ids = json.optJSONArray("ids");
        String id = ids == null || ids.length() != 1 ? "" : ids.optString(0, "");
        requireFileId(id);
        return id;
    }

    String generateFolderId(String accessToken) throws Exception { return generateFileId(accessToken); }

    Metadata createJobFolder(String accessToken, String folderId, String jobId, String parentId) throws Exception {
        requireFileId(folderId);
        requireParent(parentId);
        JSONObject body = baseMetadata(jobId, MIME_FOLDER, parentId, "job_folder").put("id", folderId);
        return create(accessToken, body, false);
    }

    Metadata uploadAttachmentResumable(String accessToken, String fileId, String jobId, String parentId,
                                       int attachmentIndex, String fileName, String mimeType, long contentLength,
                                       InputStream content) throws Exception {
        requireFileId(fileId);
        requireParent(parentId);
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
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
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

    private String startResumableSession(String accessToken, JSONObject metadata, String mimeType,
                                         long contentLength) throws Exception {
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
            String response = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
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
            if (!validFileId(created.id)) {
                throw new OutcomeUnknownException("native document create response omitted its id", null);
            }
            return created;
        } catch (ApiException definiteFailure) {
            throw definiteFailure;
        } catch (OutcomeUnknownException unknown) {
            throw unknown;
        } catch (Throwable responseFailure) {
            throw new OutcomeUnknownException("native document create response was not trustworthy", responseFailure);
        }
    }

    Metadata findTaskControlFile(String accessToken, String taskId, String parentId) throws Exception {
        requireParent(parentId);
        if (taskId == null || !taskId.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("safe task control id required");
        }
        String name = "__SELFRUN_CONTROL__" + taskId + ".json";
        String q = "'" + parentId + "' in parents and trashed = false and mimeType = '" + MIME_JSON + "'"
                + " and name='" + name + "'"
                + " and appProperties has { key='selfrun_kind' and value='task_control' }"
                + " and appProperties has { key='task_id' and value='" + taskId + "' }";
        String fields = "files(" + FILE_FIELDS + ")";
        String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
                + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name()) + "&pageSize=2";
        JSONArray files = request("GET", endpoint, accessToken, null, false).optJSONArray("files");
        if (files == null || files.length() == 0) return null;
        if (files.length() != 1) throw new IllegalStateException("multiple task control files found");
        Metadata match = new Metadata(files.getJSONObject(0));
        if (!name.equals(match.name) || !MIME_JSON.equals(match.mimeType)
                || !parentId.equals(match.parentId) || match.trashed
                || !"task_control".equals(match.appProperties.optString("selfrun_kind"))
                || !taskId.equals(match.appProperties.optString("task_id"))) {
            throw new IllegalStateException("task control metadata mismatch");
        }
        return match;
    }

    Metadata createServerDispatchFile(String accessToken, String fileId, String name,
                                      String parentId, JSONObject appProperties,
                                      JSONObject content) throws Exception {
        requireFileId(fileId);
        requireParent(parentId);
        if (name == null || name.isEmpty() || name.length() > 240) {
            throw new IllegalArgumentException("safe dispatch name required");
        }
        JSONObject properties = appProperties == null ? new JSONObject() : new JSONObject(appProperties.toString());
        properties.put("selfrun_kind", "server_dispatch");
        JSONObject metadata = new JSONObject()
                .put("id", fileId)
                .put("name", name)
                .put("mimeType", MIME_JSON)
                .put("parents", new JSONArray().put(parentId))
                .put("appProperties", properties);
        Metadata created;
        try {
            created = create(accessToken, metadata, false);
        } catch (Throwable createFailure) {
            try {
                created = getMetadata(accessToken, fileId);
            } catch (Throwable missing) {
                createFailure.addSuppressed(missing);
                throw createFailure;
            }
        }
        if (!fileId.equals(created.id) || !MIME_JSON.equals(created.mimeType)
                || !parentId.equals(created.parentId) || created.trashed) {
            throw new IllegalStateException("server dispatch metadata mismatch");
        }
        writeServerDispatchFile(accessToken, fileId, content);
        return getMetadata(accessToken, fileId);
    }

    Metadata createTaskControlFile(String accessToken, String fileId, String taskId,
                                   String parentId, JSONObject content) throws Exception {
        requireFileId(fileId);
        requireParent(parentId);
        if (taskId == null || !taskId.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("safe task control id required");
        }
        String name = "__SELFRUN_CONTROL__" + taskId + ".json";
        JSONObject metadata = new JSONObject()
                .put("id", fileId)
                .put("name", name)
                .put("mimeType", MIME_JSON)
                .put("parents", new JSONArray().put(parentId))
                .put("appProperties", new JSONObject()
                        .put("job_id", taskId)
                        .put("task_id", taskId)
                        .put("selfrun_kind", "task_control"));
        Metadata created;
        try {
            created = create(accessToken, metadata, false);
        } catch (Throwable createFailure) {
            try {
                created = getMetadata(accessToken, fileId);
            } catch (Throwable missing) {
                createFailure.addSuppressed(missing);
                throw createFailure;
            }
        }
        if (!fileId.equals(created.id) || !name.equals(created.name)
                || !MIME_JSON.equals(created.mimeType) || !parentId.equals(created.parentId)
                || created.trashed) {
            throw new IllegalStateException("task control metadata mismatch");
        }
        writeServerDispatchFile(accessToken, fileId, content);
        return getMetadata(accessToken, fileId);
    }

    JSONObject readServerDispatchFile(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        return request("GET", "https://www.googleapis.com/drive/v3/files/" + fileId
                + "?alt=media&supportsAllDrives=true", accessToken, null, false);
    }

    void writeServerDispatchFile(String accessToken, String fileId, JSONObject content) throws Exception {
        requireFileId(fileId);
        if (content == null) throw new IllegalArgumentException("dispatch content required");
        request("PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + fileId
                        + "?uploadType=media&supportsAllDrives=true&fields=id,modifiedTime",
                accessToken, content, true, "dispatch write outcome unknown");
    }

    Metadata createDebugLogDocument(String accessToken, String taskId, String parentId) throws Exception {
        requireParent(parentId);
        JSONObject metadata = baseMetadata(taskId + "-debug-log", MIME_DOCUMENT, parentId, "debug_log");
        metadata.getJSONObject("appProperties").put("job_id", taskId);
        return create(accessToken, metadata, true);
    }

    String findDebugLogDocument(String accessToken, String taskId, String parentId) throws Exception {
        requireParent(parentId);
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("debug task required");
        String query = "'" + parentId + "' in parents and trashed = false and name = '" + taskId
                + "-debug-log' and mimeType = '" + MIME_DOCUMENT + "'";
        JSONObject result = request("GET", "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&pageSize=2&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&fields="
                + URLEncoder.encode("nextPageToken,files(id)", StandardCharsets.UTF_8.name()), accessToken, null);
        JSONArray files = result.optJSONArray("files");
        if (!result.optString("nextPageToken").isEmpty() || files == null || files.length() > 1)
            throw new IOException("debug lookup ambiguous");
        return files.length() == 0 ? "" : files.getJSONObject(0).getString("id");
    }

    static final class DebugLogSnapshot {
        final String text, revisionId, tabId;
        final int endIndex;
        DebugLogSnapshot(String text, String revisionId, String tabId, int endIndex) {
            this.text = text; this.revisionId = revisionId; this.tabId = tabId; this.endIndex = endIndex;
        }
    }

    DebugLogSnapshot readDebugLogSnapshot(String token, String id) throws Exception {
        requireFileId(id);
        // Read beyond the Result/tail ceiling, but fail this auxiliary attempt before exhausting the app heap.
        long headroom = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory()
                + Runtime.getRuntime().freeMemory();
        int responseBudget = (int) Math.max(1L, Math.min(16L * 1024 * 1024, headroom / 16));
        JSONObject doc = request("GET", "https://docs.googleapis.com/v1/documents/" + id + "?includeTabsContent=true",
                token, null, false, "debug read failed", responseBudget);
        JSONArray tabs = doc.getJSONArray("tabs");
        if (tabs.length() != 1) throw new IOException("debug document tab mismatch");
        JSONObject tab = tabs.getJSONObject(0);
        if (tab.optJSONArray("childTabs") != null && tab.getJSONArray("childTabs").length() != 0)
            throw new IOException("debug document child tabs");
        String revision = doc.getString("revisionId"), tabId = tab.getJSONObject("tabProperties").getString("tabId");
        if (revision.isEmpty() || tabId.isEmpty()) throw new IOException("debug revision missing");
        JSONArray content = tab.getJSONObject("documentTab").getJSONObject("body").getJSONArray("content");
        StringBuilder text = new StringBuilder(); int end = 1;
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.getJSONObject(i);
            if (item.has("sectionBreak")) continue;
            if (!item.has("paragraph")) throw new IOException("unexpected debug document structure");
            JSONArray elements = item.getJSONObject("paragraph").getJSONArray("elements");
            for (int j = 0; j < elements.length(); j++) {
                JSONObject element = elements.getJSONObject(j);
                if (!element.has("textRun")) throw new IOException("unexpected debug document element");
                text.append(element.getJSONObject("textRun").getString("content"));
            }
            end = item.getInt("endIndex") - 1;
        }
        return new DebugLogSnapshot(text.toString(), revision, tabId, end);
    }

    void replaceDebugLogDocument(String token, String id, DebugLogSnapshot current, String text) throws Exception {
        requireFileId(id);
        JSONArray requests = new JSONArray();
        if (current.endIndex > 1) requests.put(new JSONObject().put("deleteContentRange", new JSONObject().put("range",
                new JSONObject().put("startIndex", 1).put("endIndex", current.endIndex).put("tabId", current.tabId))));
        requests.put(new JSONObject().put("insertText", new JSONObject().put("text", text).put("location",
                new JSONObject().put("index", 1).put("tabId", current.tabId))));
        request("POST", "https://docs.googleapis.com/v1/documents/" + id + ":batchUpdate", token,
                new JSONObject().put("requests", requests).put("writeControl",
                        new JSONObject().put("requiredRevisionId", current.revisionId)), true, "debug write outcome unknown");
    }

    void initializeDocument(String accessToken, String documentId, String initialText) throws Exception {
        initializeDocument(accessToken, documentId, initialText, "");
    }

    void initializeDocument(String accessToken, String documentId, String initialText,
                            String requiredRevisionId) throws Exception {
        requireFileId(documentId);
        if (initialText == null || initialText.isEmpty()) throw new IllegalArgumentException("initial text required");
        JSONObject location = new JSONObject().put("index", 1);
        JSONObject insert = new JSONObject().put("location", location).put("text", initialText);
        JSONObject body = new JSONObject().put("requests",
                new JSONArray().put(new JSONObject().put("insertText", insert)));
        if (requiredRevisionId != null && !requiredRevisionId.isEmpty()) {
            body.put("writeControl", new JSONObject().put("requiredRevisionId", requiredRevisionId));
        }
        request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate",
                accessToken, body, true, "document initialization result unknown");
    }

    DocumentSnapshot readDocumentSnapshot(String accessToken, String documentId) throws Exception {
        return readNativeDocumentSnapshot(accessToken, documentId);
    }

    DocumentSnapshot readTurnDocumentSnapshot(String accessToken, String documentId) throws Exception {
        return readNativeDocumentSnapshot(accessToken, documentId);
    }

    private DocumentSnapshot readNativeDocumentSnapshot(String accessToken, String documentId) throws Exception {
        requireFileId(documentId);
        JSONObject document = request("GET",
                "https://docs.googleapis.com/v1/documents/" + documentId + "?includeTabsContent=true",
                accessToken, null);
        String revisionId = document.optString("revisionId", "");
        if (revisionId.isEmpty()) throw new IllegalStateException("execution document revisionId missing");
        JSONArray tabs = document.optJSONArray("tabs");
        if (tabs == null || tabs.length() != 1) {
            throw new IllegalStateException("execution document must contain exactly one tab");
        }
        JSONObject tab = tabs.optJSONObject(0);
        if (tab == null || (tab.optJSONArray("childTabs") != null
                && tab.optJSONArray("childTabs").length() > 0)) {
            throw new IllegalStateException("nested execution document tabs are forbidden");
        }
        JSONObject tabProperties = tab.optJSONObject("tabProperties");
        String tabId = tabProperties == null ? "" : tabProperties.optString("tabId", "");
        if (tabId.isEmpty()) throw new IllegalStateException("execution document tabId missing");
        JSONObject documentTab = tab.optJSONObject("documentTab");
        JSONObject body = documentTab == null ? null : documentTab.optJSONObject("body");
        if (body == null) throw new IllegalStateException("execution document body missing");
        JSONArray content = body.optJSONArray("content");
        StringBuilder text = new StringBuilder();
        appendTextRuns(content, text);
        if (text.length() > MAX_DOCUMENT_CHARS) throw new IllegalStateException("execution document too large");
        int[] claimRange = firstClaimRange(content);
        JSONObject namedRanges = documentTab.optJSONObject("namedRanges");
        return new DocumentSnapshot(text.toString(), revisionId, tabId,
                claimRange[0], claimRange[1], namedRanges);
    }

    String readDocumentText(String accessToken, String documentId) throws Exception {
        return readNativeDocumentSnapshot(accessToken, documentId).text;
    }

    /** Generic Docs arbitration primitive; it does not participate in V3 execution state. */
    boolean createNamedRangeClaim(String accessToken, String documentId, DocumentSnapshot snapshot,
                                  String claimName) throws Exception {
        requireFileId(documentId);
        if (snapshot == null || snapshot.revisionId.isEmpty()) throw new IllegalArgumentException("document snapshot required");
        if (!validNamedRangeName(claimName)) throw new IllegalArgumentException("safe named range claim required");
        if (snapshot.hasNamedRange(claimName)) return true;
        if (snapshot.claimStartIndex < 1 || snapshot.claimEndIndex <= snapshot.claimStartIndex) {
            throw new IllegalStateException("valid claim range unavailable");
        }
        JSONObject range = new JSONObject().put("startIndex", snapshot.claimStartIndex)
                .put("endIndex", snapshot.claimEndIndex).put("tabId", snapshot.tabId);
        JSONObject create = new JSONObject().put("name", claimName).put("range", range);
        JSONObject body = new JSONObject()
                .put("requests", new JSONArray().put(new JSONObject().put("createNamedRange", create)))
                .put("writeControl", new JSONObject().put("requiredRevisionId", snapshot.revisionId));
        try {
            request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate",
                    accessToken, body, true, "named-range claim result unknown");
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
        JSONObject json = request("POST",
                "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&fields=" + FILE_FIELDS,
                accessToken, body, outcomeSensitive);
        return new Metadata(json);
    }

    private static JSONObject baseMetadata(String name, String mimeType, String parentId, String kind) throws Exception {
        return new JSONObject().put("name", name).put("mimeType", mimeType)
                .put("parents", new JSONArray().put(parentId))
                .put("appProperties", new JSONObject().put("job_id", name).put("selfrun_kind", kind));
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body)
            throws Exception {
        return request(method, endpoint, accessToken, body, false);
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body,
                                      boolean outcomeSensitive) throws Exception {
        return request(method, endpoint, accessToken, body, outcomeSensitive,
                "native document create result unknown");
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body,
                                      boolean outcomeSensitive, String outcomeUnknownMessage) throws Exception {
        return request(method, endpoint, accessToken, body, outcomeSensitive, outcomeUnknownMessage, MAX_RESPONSE_BYTES);
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body,
                                      boolean outcomeSensitive, String outcomeUnknownMessage, int responseLimit) throws Exception {
        URL url = requireAllowedUrl(endpoint);
        HttpURLConnection connection = null;
        boolean requestBodyOpened = false;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + requireToken(accessToken));
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    requestBodyOpened = true;
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(stream, responseLimit);
            if (status < 200 || status >= 300) {
                ApiException api = apiException(status, response);
                if (outcomeSensitive && status >= 500) {
                    throw new OutcomeUnknownException(outcomeUnknownMessage, api);
                }
                throw api;
            }
            return response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
        } catch (IOException error) {
            if (error instanceof OutcomeUnknownException) throw error;
            if (outcomeSensitive && body != null && !requestBodyOpened) {
                throw new CreateNotSubmittedException("native create was not submitted", error);
            }
            if (outcomeSensitive) throw new OutcomeUnknownException(outcomeUnknownMessage, error);
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ApiException apiException(int status, String response) {
        String reason = "HTTP " + status;
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            String message = error == null ? "" : error.optString("status", "");
            if (!message.isEmpty()) reason += " " + message;
        } catch (Throwable ignored) { }
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
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("access token required");
        }
        return accessToken;
    }

    private static String readBounded(InputStream source) throws Exception {
        return readBounded(source, MAX_RESPONSE_BYTES);
    }

    private static String readBounded(InputStream source, int limit) throws Exception {
        if (source == null) return "";
        try (InputStream input = new BufferedInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IllegalStateException("Drive response too large");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void appendTextRuns(Object value, StringBuilder output) {
        if (output.length() > MAX_DOCUMENT_CHARS) throw new IllegalStateException("execution document too large");
        if (value instanceof JSONObject object) {
            JSONObject textRun = object.optJSONObject("textRun");
            if (textRun != null) output.append(textRun.optString("content", ""));
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"textRun".equals(key)) appendTextRuns(object.opt(key), output);
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) appendTextRuns(array.opt(i), output);
        }
    }

    private static int[] firstClaimRange(JSONArray content) {
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null || item.optJSONObject("paragraph") == null) continue;
            int start = item.optInt("startIndex", -1);
            int end = item.optInt("endIndex", -1);
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
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed < 0 ? -1L : parsed;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static boolean validMimeType(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    }

    static boolean validAttachmentMimeType(String value) {
        return validMimeType(value)
                && !value.toLowerCase(java.util.Locale.ROOT).startsWith(GOOGLE_WORKSPACE_MIME_PREFIX);
    }

    static String normalizeAttachmentMimeType(String value) {
        return validAttachmentMimeType(value) ? value : MIME_OCTET_STREAM;
    }

    static boolean validFileId(String value) {
        return value != null && !"root".equals(value) && value.matches("[A-Za-z0-9_-]{8,200}");
    }

    static boolean validOpaqueAccountId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{5,256}");
    }

    private static void requireFileId(String fileId) {
        if (!validFileId(fileId)) throw new IllegalArgumentException("valid Drive file id required");
    }

    static void requireParent(String parentId) {
        if (!validFileId(parentId)) {
            throw new IllegalArgumentException("explicit Drive parent id required; root fallback is forbidden");
        }
    }
}
