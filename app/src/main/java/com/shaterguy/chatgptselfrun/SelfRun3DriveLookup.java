package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Narrow V3-only exact-name lookup for app-authorized Drive documents. */
final class SelfRun3DriveLookup {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    static String findSingleDocumentId(String accessToken, String name, String parentId) throws Exception {
        if (accessToken == null || accessToken.isEmpty()) throw new IllegalArgumentException("access token required");
        DriveApiClient.requireParent(parentId);
        if (name == null || !name.matches("[A-Za-z0-9._:-]{1,240}")) throw new IllegalArgumentException("safe V3 document name required");
        String escapedName = name.replace("\\", "\\\\").replace("'", "\\'");
        String query = "'" + parentId + "' in parents and trashed = false and name = '" + escapedName
                + "' and mimeType = '" + DriveApiClient.MIME_DOCUMENT + "'";
        String fields = "files(id,name,mimeType,parents,trashed,shared,isAppAuthorized)";
        URL url = new URL("https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&pageSize=10&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&fields="
                + URLEncoder.encode(fields, StandardCharsets.UTF_8.name()));
        if (!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) throw new IllegalArgumentException("Drive endpoint rejected");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            String body = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw new DriveApiClient.ApiException(status, "HTTP " + status);
            JSONArray files = new JSONObject(body).optJSONArray("files");
            String found = "";
            if (files == null) return found;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || file.optBoolean("trashed") || file.optBoolean("shared")) continue;
                if (!name.equals(file.optString("name")) || !DriveApiClient.MIME_DOCUMENT.equals(file.optString("mimeType"))) continue;
                JSONArray parents = file.optJSONArray("parents");
                if (parents == null || parents.length() != 1 || !parentId.equals(parents.optString(0))) continue;
                if (!file.optBoolean("isAppAuthorized", false)) continue;
                String id = file.optString("id");
                if (!DriveApiClient.validFileId(id)) continue;
                if (!found.isEmpty() && !found.equals(id)) throw new IllegalStateException("multiple V3 documents match exact identity");
                found = id;
            }
            return found;
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream source) throws Exception {
        if (source == null) return "";
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Drive lookup response too large");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private SelfRun3DriveLookup() {}
}
