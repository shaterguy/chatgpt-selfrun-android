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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;

/** Minimal Drive v3 and Docs v1 REST client. Tokens are memory-only and never logged. */
final class DriveApiClient {
    static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    static final String MIME_DOCUMENT = "application/vnd.google-apps.document";
    private static final String FILE_FIELDS = "id,name,mimeType,parents,trashed,appProperties,version,"
            + "modifiedTime,webViewLink,isAppAuthorized,shared,capabilities(canAddChildren)";
    private static final String POLL_FIELDS = "id,mimeType,parents,trashed,version,modifiedTime,shared";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DOCUMENT_CHARS = 1_000_000;
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("www.googleapis.com", "docs.googleapis.com")));

    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
        boolean retryable() { return status == 429 || status >= 500; }
    }

    /** A non-idempotent create may have reached Drive even though no response was received. */
    static final class OutcomeUnknownException extends IOException {
        OutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
    }

    static final class Metadata {
        final String id;
        final String name;
        final String mimeType;
        final String parentId;
        final boolean trashed;
        final String version;
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
            JSONArray parents = json.optJSONArray("parents");
            parentId = parents == null || parents.length() != 1 ? "" : parents.optString(0, "");
            trashed = json.optBoolean("trashed", false);
            version = String.valueOf(json.opt("version") == null ? "" : json.opt("version"));
            modifiedTime = json.optString("modifiedTime", "");
            webViewLink = json.optString("webViewLink", "");
            appProperties = json.optJSONObject("appProperties") == null
                    ? new JSONObject() : json.optJSONObject("appProperties");
            isAppAuthorized = json.optBoolean("isAppAuthorized", false);
            shared = json.optBoolean("shared", false);
            JSONObject capabilities = json.optJSONObject("capabilities");
            canAddChildren = capabilities != null && capabilities.optBoolean("canAddChildren", false);
        }
    }

    /** Stable opaque account identity. Access tokens themselves remain memory-only. */
    String getAccountPermissionId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)",
                accessToken, null);
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

    /** Direct-ID polling intentionally requests only the metadata required by the Drive state guard. */
    Metadata getPollMetadata(String accessToken, String fileId) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId
                + "?supportsAllDrives=true&fields=" + POLL_FIELDS;
        return new Metadata(request("GET", endpoint, accessToken, null));
    }

    /**
     * Drive supports generated IDs for folders. Persist this ID before files.create so a retry can
     * use files.get/the same ID and can never create a second folder.
     */
    String generateFolderId(String accessToken) throws Exception {
        JSONObject json = request("GET", "https://www.googleapis.com/drive/v3/files/generateIds"
                + "?count=1&space=drive&type=files", accessToken, null, false);
        JSONArray ids = json.optJSONArray("ids");
        String id = ids == null || ids.length() != 1 ? "" : ids.optString(0, "");
        requireFileId(id);
        return id;
    }

    Metadata createJobFolder(String accessToken, String folderId, String jobId, String parentId)
            throws Exception {
        requireFileId(folderId);
        requireParent(parentId);
        JSONObject body = baseMetadata(jobId, MIME_FOLDER, parentId, "job_folder").put("id", folderId);
        return create(accessToken, body, false);
    }

    Metadata createTurnDocument(String accessToken, String jobId, String parentId) throws Exception {
        requireParent(parentId);
        JSONObject body = baseMetadata(jobId, MIME_DOCUMENT, parentId, "turn_document");
        // Native Google Docs do not support Drive pre-generated IDs. Any lost response is terminally
        // ambiguous and must never be followed by files.list, name search, or another create call.
        try {
            Metadata created = create(accessToken, body, true);
            if (!validFileId(created.id)) {
                throw new OutcomeUnknownException("native document create response omitted its id", null);
            }
            return created;
        } catch (ApiException definiteFailure) {
            // A complete, non-retryable 4xx response proves Drive rejected the create.
            throw definiteFailure;
        } catch (OutcomeUnknownException unknown) {
            throw unknown;
        } catch (Throwable responseFailure) {
            throw new OutcomeUnknownException("native document create response was not trustworthy",
                    responseFailure);
        }
    }

    void initializeDocument(String accessToken, String documentId, String initialText) throws Exception {
        requireFileId(documentId);
        if (initialText == null || initialText.isEmpty()) throw new IllegalArgumentException("initial text required");
        JSONObject insert = new JSONObject()
                .put("location", new JSONObject().put("index", 1))
                .put("text", initialText);
        JSONObject body = new JSONObject().put("requests",
                new JSONArray().put(new JSONObject().put("insertText", insert)));
        request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate",
                accessToken, body);
    }

    String readDocumentText(String accessToken, String documentId) throws Exception {
        requireFileId(documentId);
        JSONObject document = request("GET", "https://docs.googleapis.com/v1/documents/" + documentId
                + "?includeTabsContent=true", accessToken, null);
        JSONArray tabs = document.optJSONArray("tabs");
        if (tabs == null || tabs.length() != 1) {
            throw new IllegalStateException("execution document must contain exactly one tab");
        }
        JSONObject tab = tabs.optJSONObject(0);
        if (tab == null || (tab.optJSONArray("childTabs") != null && tab.optJSONArray("childTabs").length() > 0)) {
            throw new IllegalStateException("nested execution document tabs are forbidden");
        }
        JSONObject documentTab = tab.optJSONObject("documentTab");
        JSONObject body = documentTab == null ? null : documentTab.optJSONObject("body");
        if (body == null) throw new IllegalStateException("execution document body missing");
        StringBuilder text = new StringBuilder();
        appendTextRuns(body.optJSONArray("content"), text);
        if (text.length() > MAX_DOCUMENT_CHARS) throw new IllegalStateException("execution document too large");
        return text.toString();
    }

    private Metadata create(String accessToken, JSONObject body, boolean outcomeSensitive) throws Exception {
        JSONObject json = request("POST", "https://www.googleapis.com/drive/v3/files"
                + "?supportsAllDrives=true&fields=" + FILE_FIELDS, accessToken, body, outcomeSensitive);
        return new Metadata(json);
    }

    private static JSONObject baseMetadata(String name, String mimeType, String parentId, String kind)
            throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("mimeType", mimeType)
                .put("parents", new JSONArray().put(parentId))
                .put("appProperties", new JSONObject()
                        .put("job_id", name)
                        .put("selfrun_kind", kind)
                        .put("protocol_version", "1")
                        .put("client_id", "selfrun_drive_android")
                        .put("created_by", "selfrun_drive_android"));
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body)
            throws Exception {
        return request(method, endpoint, accessToken, body, false);
    }

    private static JSONObject request(String method, String endpoint, String accessToken, JSONObject body,
                                      boolean outcomeSensitive) throws Exception {
        if (accessToken == null || accessToken.trim().isEmpty()) throw new IllegalArgumentException("access token required");
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || !ALLOWED_HOSTS.contains(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new IllegalArgumentException("Drive endpoint is not allowlisted");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(stream);
            if (status < 200 || status >= 300) {
                String reason = "HTTP " + status;
                try {
                    JSONObject error = new JSONObject(response).optJSONObject("error");
                    String message = error == null ? "" : error.optString("status", "");
                    if (!message.isEmpty()) reason += " " + message;
                } catch (Throwable ignored) {
                }
                ApiException api = new ApiException(status, reason);
                if (outcomeSensitive && (status == 408 || status == 429 || status >= 500)) {
                    throw new OutcomeUnknownException("native document create result unknown", api);
                }
                throw api;
            }
            return response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
        } catch (IOException error) {
            if (error instanceof OutcomeUnknownException) throw error;
            if (outcomeSensitive) {
                throw new OutcomeUnknownException("native document create result unknown", error);
            }
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream source) throws Exception {
        if (source == null) return "";
        try (InputStream input = new BufferedInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Drive response too large");
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
