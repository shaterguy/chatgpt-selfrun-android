package com.shaterguy.chatgptselfrun;

/** Shared URL parsing and JavaScript string quoting helpers. */
final class SelfRunScript {
    private SelfRunScript() {}

    static String projectId(String url) {
        if (url == null) return "";
        String[] parts = url.split("/");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("g".equals(parts[i])) return parts[i + 1];
        }
        return "";
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
