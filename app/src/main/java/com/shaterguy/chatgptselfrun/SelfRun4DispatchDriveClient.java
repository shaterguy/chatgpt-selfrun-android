package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Small Drive media client dedicated to the 4.0 dispatch JSON file. */
final class SelfRun4DispatchDriveClient {
    private static final String MIME_JSON = "application/json";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Set.of("www.googleapis.com");
    private final DriveApiClient drive = new DriveApiClient();

    DriveApiClient.Metadata ensureFile(String token, String fileId, SelfRun3Engine.State state) throws Exception {
        DriveApiClient.requireParent(state.config().optString("baseFolderId"));
        if (!DriveApiClient.validFileId(fileId)) throw new IllegalArgumentException("dispatch file id required");
        DriveApiClient.Metadata metadata;
        try {
            metadata = drive.getMetadata(token, fileId);
        } catch (DriveApiClient.ApiException notFound) {
            if (notFound.status != 404) throw notFound;
            try {
                metadata = createFile(token, fileId, state);
            } catch (DriveApiClient.ApiException race) {
                if (race.status != 409) throw race;
                metadata = drive.getMetadata(token, fileId);
            }
        }
        validate(metadata, fileId, state);
        return metadata;
    }

    void write(String token, String fileId, JSONObject body) throws Exception {
        if (!DriveApiClient.validFileId(fileId) || body == null) {
            throw new IllegalArgumentException("dispatch write identity required");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("dispatch file too large");
        String endpoint = "https://www.googleapis.com/upload/drive/v3/files/" + fileId
                + "?uploadType=media&supportsAllDrives=true";
        requestRaw("PATCH", endpoint, token, bytes, MIME_JSON);
    }

    JSONObject read(String token, String fileId) throws Exception {
        if (!DriveApiClient.validFileId(fileId)) throw new IllegalArgumentException("dispatch file id required");
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId
                + "?alt=media&supportsAllDrives=true";
        String raw = requestRaw("GET", endpoint, token, null, "");
        if (raw.trim().isEmpty()) return new JSONObject();
        return new JSONObject(raw);
    }

    void markResultCommitted(String token, SelfRun3Engine.State state) throws Exception {
        String fileId = state == null ? "" : state.resource("dispatchFileId");
        if (!DriveApiClient.validFileId(fileId)) return;
        JSONObject current = read(token, fileId);
        if (current.length() == 0) return;
        if (SelfRun4DispatchFile.RESULT_COMMITTED.equals(SelfRun4DispatchFile.status(current))) return;
        write(token, fileId, SelfRun4DispatchFile.resultCommitted(current, state));
    }

    private DriveApiClient.Metadata createFile(String token, String fileId,
                                                SelfRun3Engine.State state) throws Exception {
        JSONObject metadata = new JSONObject()
                .put("id", fileId)
                .put("name", SelfRun4DispatchFile.fileName(state))
                .put("mimeType", MIME_JSON)
                .put("parents", new JSONArray().put(state.config().optString("baseFolderId")))
                .put("appProperties", new JSONObject()
                        .put("job_id", state.taskId())
                        .put("selfrun_kind", "remote_dispatch")
                        .put("turn_id", state.turnId())
                        .put("request_id", state.requestId()));
        String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
                + "&fields=id,name,mimeType,parents,trashed,appProperties,isAppAuthorized,shared";
        JSONObject created = new JSONObject(requestRaw("POST", endpoint, token,
                metadata.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8"));
        return new DriveApiClient.Metadata(created);
    }

    private static void validate(DriveApiClient.Metadata metadata, String fileId,
                                 SelfRun3Engine.State state) {
        boolean valid = metadata != null
                && fileId.equals(metadata.id)
                && SelfRun4DispatchFile.fileName(state).equals(metadata.name)
                && MIME_JSON.equals(metadata.mimeType)
                && state.config().optString("baseFolderId").equals(metadata.parentId)
                && !metadata.trashed
                && !metadata.shared
                && metadata.isAppAuthorized
                && state.taskId().equals(metadata.appProperties.optString("job_id"))
                && "remote_dispatch".equals(metadata.appProperties.optString("selfrun_kind"))
                && state.turnId().equals(metadata.appProperties.optString("turn_id"))
                && state.requestId().equals(metadata.appProperties.optString("request_id"));
        if (!valid) throw new IllegalStateException("DISPATCH_BOUNDARY_MISMATCH");
    }

    private static String requestRaw(String method, String endpoint, String token,
                                     byte[] body, String contentType) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || !ALLOWED_HOSTS.contains(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new IllegalArgumentException("dispatch endpoint is not allowlisted");
        }
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("access token required");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            boolean patch = "PATCH".equals(method);
            connection.setRequestMethod(patch ? "POST" : method);
            if (patch) connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            String response = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw apiException(status, response);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static DriveApiClient.ApiException apiException(int status, String response) {
        String reason = "HTTP " + status;
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            String value = error == null ? "" : error.optString("status", "");
            if (!value.isEmpty()) reason += " " + value;
        } catch (Throwable ignored) { }
        return new DriveApiClient.ApiException(status, reason);
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
                if (total > MAX_BYTES) throw new IllegalStateException("dispatch response too large");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
