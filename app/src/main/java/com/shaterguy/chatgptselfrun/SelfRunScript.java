package com.shaterguy.chatgptselfrun;

/** Shared URL parsing and JavaScript string quoting helpers. */
final class SelfRunScript {
    static final String GENERAL_CHAT_URL = "https://chatgpt.com/";
    static final String GENERAL_CHAT_SCOPE = "__GENERAL_CHAT__";

    private SelfRunScript() {}

    static String projectId(String url) {
        if (url == null) return "";
        String normalized = url.trim();
        if (normalized.isEmpty()) return "";
        String[] parts = normalized.split("/");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("g".equals(parts[i])) return parts[i + 1];
        }
        return isGeneralChatUrl(normalized) ? GENERAL_CHAT_SCOPE : "";
    }

    static boolean isGeneralChatUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (!("chatgpt.com".equalsIgnoreCase(host) || "www.chatgpt.com".equalsIgnoreCase(host))) return false;
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) return true;
            String[] segments = path.split("/");
            return segments.length == 3 && "c".equals(segments[1]) && !segments[2].isEmpty();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String conversationId(String url) {
        if (url == null) return "";
        String[] parts = url.split("/");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("c".equals(parts[i])) return parts[i + 1];
        }
        return "";
    }

    static String quote(String value) {
        if (value == null) value = "";
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
