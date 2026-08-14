package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validated project-name/URL data exchanged between the ChatGPT DOM and native UI. */
final class ProjectCatalog {
    static final int MAX_PROJECTS = 1000;
    static final int MAX_NAME_CHARS = 300;
    static final int MAX_URL_CHARS = 4096;

    static final class Entry {
        final String name;
        final String url;
        Entry(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    static final class Probe {
        final String state;
        final boolean markerSeen;
        final List<Entry> entries;
        Probe(String state, boolean markerSeen, List<Entry> entries) {
            this.state = state == null ? "" : state;
            this.markerSeen = markerSeen;
            this.entries = entries;
        }
    }

    private ProjectCatalog() {}

    static String canonicalProjectUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_CHARS) return "";
        final URI uri;
        try { uri = new URI(value); }
        catch (URISyntaxException | IllegalArgumentException ignored) { return ""; }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
        String host = uri.getHost();
        if (!("chatgpt.com".equalsIgnoreCase(host) || "www.chatgpt.com".equalsIgnoreCase(host))) return "";
        if (uri.getRawUserInfo() != null || uri.getPort() != -1) return "";
        String path = uri.getPath();
        if (path == null) return "";
        String[] segments = path.split("/", -1);
        if (segments.length < 3 || !segments[0].isEmpty() || !"g".equals(segments[1])) return "";
        String id = segments[2];
        if (!validProjectId(id)) return "";
        boolean validTail = segments.length == 3
                || (segments.length == 4 && (segments[3].isEmpty() || "project".equals(segments[3])))
                || (segments.length == 5 && "project".equals(segments[3]) && segments[4].isEmpty());
        if (!validTail) return "";
        return "https://chatgpt.com/g/" + id + "/project";
    }

    static boolean isTrustedChatgptPage(String raw) {
        if (raw == null) return false;
        try {
            URI uri = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            return ("chatgpt.com".equalsIgnoreCase(host) || "www.chatgpt.com".equalsIgnoreCase(host))
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean validProjectId(String id) {
        if (id == null || !id.startsWith("g-p-") || id.length() > 240) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '-' && c != '_') return false;
        }
        return true;
    }

    static Probe parseProbe(String evaluateJavascriptResult) throws JSONException {
        if (evaluateJavascriptResult == null || evaluateJavascriptResult.length() > 2_000_000)
            throw new JSONException("project probe result missing or too large");
        Object decoded = new JSONTokener(evaluateJavascriptResult).nextValue();
        if (decoded instanceof String) decoded = new JSONTokener((String) decoded).nextValue();
        if (!(decoded instanceof JSONObject)) throw new JSONException("project probe is not an object");
        JSONObject object = (JSONObject) decoded;
        String state = object.optString("state", "");
        if (!("OPENING".equals(state) || "FOUND".equals(state) || "EMPTY".equals(state) || "ERROR".equals(state)))
            throw new JSONException("unexpected project probe state");
        JSONArray items = object.optJSONArray("entries");
        List<Entry> entries = normalize(items == null ? new JSONArray() : items);
        return new Probe(state, object.optBoolean("marker", false), entries);
    }

    static List<Entry> fromStoredJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        try {
            Object decoded = new JSONTokener(raw).nextValue();
            if (!(decoded instanceof JSONArray)) return new ArrayList<>();
            return normalize((JSONArray) decoded);
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }
    }

    static String toStoredJson(List<Entry> entries) {
        JSONArray array = new JSONArray();
        if (entries != null) {
            int count = 0;
            Set<String> seen = new HashSet<>();
            for (Entry entry : entries) {
                if (entry == null || count >= MAX_PROJECTS) break;
                String url = canonicalProjectUrl(entry.url);
                if (url.isEmpty() || !seen.add(url)) continue;
                String name = normalizeName(entry.name);
                JSONObject item = new JSONObject();
                try {
                    item.put("name", name);
                    item.put("url", url);
                } catch (JSONException impossible) {
                    continue;
                }
                array.put(item);
                count++;
            }
        }
        return array.toString();
    }

    private static List<Entry> normalize(JSONArray items) throws JSONException {
        if (items.length() > MAX_PROJECTS) throw new JSONException("too many project entries");
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String rawUrl = item.optString("url", "");
            if (rawUrl.length() > MAX_URL_CHARS) continue;
            String url = canonicalProjectUrl(rawUrl);
            if (url.isEmpty() || !seen.add(url)) continue;
            out.add(new Entry(normalizeName(item.optString("name", "")), url));
        }
        return out;
    }

    private static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) value = "프로젝트";
        if (value.length() > MAX_NAME_CHARS) value = value.substring(0, MAX_NAME_CHARS);
        return value;
    }

    static List<String> displayLabels(List<Entry> entries) {
        List<String> labels = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        if (entries == null) return labels;
        for (Entry entry : entries) {
            String base = entry == null || entry.name.isEmpty() ? "프로젝트" : entry.name;
            String key = base.toLowerCase(Locale.ROOT);
            int count = counts.containsKey(key) ? counts.get(key) + 1 : 1;
            counts.put(key, count);
            labels.add(count == 1 ? base : base + " (" + count + ")");
        }
        return labels;
    }

    static int indexOfUrl(List<Entry> entries, String rawUrl) {
        String target = canonicalProjectUrl(rawUrl);
        if (target.isEmpty() || entries == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (target.equals(entries.get(i).url)) return i;
        return -1;
    }
}
