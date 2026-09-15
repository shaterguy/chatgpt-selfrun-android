package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Narrow Drive files.watch client; OAuth token stays inside the Android process. */
final class SelfRunDriveWatchClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    void watchFile(String accessToken, String fileId, String channelId, String address,
                   String channelToken, long expirationMs) throws Exception {
        if (accessToken == null || accessToken.trim().isEmpty()) throw new IllegalArgumentException("Drive token required");
        if (!DriveApiClient.validFileId(fileId)) throw new IllegalArgumentException("valid Drive file id required");
        requireOpaque(channelId, 128);
        requireOpaque(channelToken, 160);
        URI callback = URI.create(address);
        if (!"https".equalsIgnoreCase(callback.getScheme()) || callback.getHost() == null
                || callback.getUserInfo() != null || callback.getFragment() != null) {
            throw new IllegalArgumentException("HTTPS webhook address required");
        }
        long now = System.currentTimeMillis();
        if (expirationMs <= now || expirationMs > now + 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("Drive watch expiration must be within one day");
        }

        JSONObject body = new JSONObject()
                .put("id", channelId)
                .put("type", "web_hook")
                .put("address", address)
                .put("token", channelToken)
                .put("expiration", expirationMs);
        URL url = new URL("https://www.googleapis.com/drive/v3/files/" + fileId
                + "/watch?supportsAllDrives=true");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            String response = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new DriveApiClient.ApiException(status,
                        response.isEmpty() ? "Drive watch HTTP " + status : response);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void requireOpaque(String value, int max) {
        if (value == null || value.length() < 16 || value.length() > max || value.trim().length() != value.length()
                || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("safe opaque channel value required");
        }
    }

    private static String readBounded(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Drive watch response too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
