package com.shaterguy.chatgptselfrun;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS-only client for the opaque SelfRun push gateway. Never transports Drive credentials/content. */
final class SelfRunPushGatewayClient {
    static final String DEFAULT_BASE_URL = "https://selfrun-command-bridge-shaterguy.vercel.app";
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    static final class GatewayException extends Exception {
        final int status;
        GatewayException(int status, String message) { super(message); this.status = status; }
    }

    static final class WatchRegistration {
        final String watchKey;
        final String channelId;
        final String webhookUrl;
        final long expirationMs;
        WatchRegistration(String watchKey, String channelId, String webhookUrl, long expirationMs) {
            this.watchKey = watchKey;
            this.channelId = channelId;
            this.webhookUrl = webhookUrl;
            this.expirationMs = expirationMs;
        }
    }

    private final String baseUrl;
    private final String baseHost;

    SelfRunPushGatewayClient() { this(DEFAULT_BASE_URL); }

    SelfRunPushGatewayClient(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException("HTTPS gateway base URL required");
            }
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.baseUrl = normalized;
            this.baseHost = uri.getHost().toLowerCase();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("valid HTTPS gateway URL required", invalid);
        }
    }

    WatchRegistration registerWatch(String installationId, String applicationId, String taskId,
                                    String turnId, String resultDocumentId, String fcmToken) throws Exception {
        JSONObject response = post("/api/watch/register",
                registrationBody(installationId, applicationId, taskId, turnId, resultDocumentId, fcmToken));
        if (!response.optBoolean("ok", false)) throw new GatewayException(500, "gateway rejected watch registration");
        String watchKey = required(response, "watchKey", 160);
        String channelId = required(response, "channelId", 128);
        String webhookUrl = required(response, "webhookUrl", 512);
        long expirationMs = response.optLong("expirationMs", 0L);
        URI webhook = URI.create(webhookUrl);
        if (!"https".equalsIgnoreCase(webhook.getScheme()) || webhook.getHost() == null
                || !baseHost.equalsIgnoreCase(webhook.getHost()) || webhook.getUserInfo() != null
                || webhook.getFragment() != null) {
            throw new GatewayException(500, "gateway returned an untrusted webhook URL");
        }
        long now = System.currentTimeMillis();
        if (expirationMs <= now || expirationMs > now + 24L * 60L * 60L * 1000L) {
            throw new GatewayException(500, "gateway returned invalid watch expiration");
        }
        return new WatchRegistration(watchKey, channelId, webhookUrl, expirationMs);
    }

    JSONObject sendAck(JSONObject ackBody) throws Exception {
        if (ackBody == null) throw new IllegalArgumentException("ACK body required");
        return post("/api/push/ack", ackBody);
    }

    static JSONObject registrationBody(String installationId, String applicationId, String taskId,
                                       String turnId, String resultDocumentId, String fcmToken) {
        try {
            return new JSONObject()
                    .put("schema", "selfrun-watch-register-v1")
                    .put("installationId", requireLocal(installationId, 128))
                    .put("applicationId", requireLocal(applicationId, 160))
                    .put("taskId", requireLocal(taskId, 160))
                    .put("turnId", requireLocal(turnId, 200))
                    .put("resultDocumentId", requireLocal(resultDocumentId, 200))
                    .put("fcmToken", requireLocal(fcmToken, 4096));
        } catch (JSONException impossible) {
            throw new IllegalStateException("failed to encode gateway registration", impossible);
        }
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        URL url = URI.create(baseUrl + path).toURL();
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !baseHost.equalsIgnoreCase(url.getHost())) {
            throw new IllegalStateException("gateway host changed");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Cache-Control", "no-store");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 16 * 1024) throw new IllegalArgumentException("gateway request too large");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            String text = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new GatewayException(status, text.isEmpty() ? "gateway HTTP " + status : text);
            }
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            if (connection != null) connection.disconnect();
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
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("gateway response too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String required(JSONObject json, String key, int max) {
        return requireLocal(json.optString(key, ""), max);
    }

    private static String requireLocal(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max || value.trim().length() != value.length()
                || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid routing value");
        }
        return value;
    }
}
