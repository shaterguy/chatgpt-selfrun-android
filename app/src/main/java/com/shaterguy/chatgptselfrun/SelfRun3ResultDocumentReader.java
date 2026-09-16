package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Read-only Docs transport for Result consumption. It tolerates extra and nested tabs without mutating them. */
final class SelfRun3ResultDocumentReader {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    static final class Snapshot {
        final String revisionId;
        final List<String> bodies;

        Snapshot(String revisionId, List<String> bodies) {
            this.revisionId = revisionId == null ? "" : revisionId;
            this.bodies = bodies == null ? Collections.emptyList() : List.copyOf(bodies);
        }
    }

    static Snapshot read(String accessToken, String documentId) throws Exception {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("access token required");
        }
        if (!DriveApiClient.validFileId(documentId)) {
            throw new IllegalArgumentException("valid Drive file id required");
        }
        URL url = new URL("https://docs.googleapis.com/v1/documents/" + documentId + "?includeTabsContent=true");
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
            String response = readBounded(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw new DriveApiClient.ApiException(status, "HTTP " + status);
            JSONObject document = response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
            String revisionId = document.optString("revisionId", "");
            if (revisionId.isEmpty()) throw new IllegalStateException("result document revisionId missing");
            ArrayList<String> bodies = new ArrayList<>();
            JSONArray tabs = document.optJSONArray("tabs");
            if (tabs != null) {
                for (int i = 0; i < tabs.length(); i++) collectTab(tabs.optJSONObject(i), bodies);
            } else {
                JSONObject body = document.optJSONObject("body");
                bodies.add(extractBody(body));
            }
            if (bodies.isEmpty()) bodies.add("");
            return new Snapshot(revisionId, bodies);
        } finally {
            connection.disconnect();
        }
    }

    private static void collectTab(JSONObject tab, List<String> output) {
        if (tab == null) return;
        JSONObject documentTab = tab.optJSONObject("documentTab");
        if (documentTab != null) output.add(extractBody(documentTab.optJSONObject("body")));
        JSONArray children = tab.optJSONArray("childTabs");
        if (children != null) for (int i = 0; i < children.length(); i++) {
            collectTab(children.optJSONObject(i), output);
        }
    }

    private static String extractBody(JSONObject body) {
        if (body == null) return "";
        StringBuilder text = new StringBuilder();
        appendTextRuns(body.optJSONArray("content"), text);
        return text.toString();
    }

    private static void appendTextRuns(Object value, StringBuilder output) {
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

    private static String readBounded(InputStream source) throws Exception {
        if (source == null) return "";
        try (InputStream input = new BufferedInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Docs response too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private SelfRun3ResultDocumentReader() { }
}
