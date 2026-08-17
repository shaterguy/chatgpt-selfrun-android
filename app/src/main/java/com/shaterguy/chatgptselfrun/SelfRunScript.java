package com.shaterguy.chatgptselfrun;

/** Shared URL parsing and JavaScript string quoting helpers. */
final class SelfRunScript {
    static final String GENERAL_CHAT_URL = "https://chatgpt.com/";
    static final String GENERAL_CHAT_SCOPE = "__GENERAL_CHAT__";
    private static final int MAX_OPAQUE_ID_LENGTH = 160;
    private static final int MAX_URL_LENGTH = 2048;

    private SelfRunScript() {}

    static String projectId(String url) {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(url);
        return ref == null ? (isGeneralChatUrl(url) ? GENERAL_CHAT_SCOPE : "") : ref.projectId;
    }

    /**
     * General-chat runtime routes are identified by their canonical path, not provider-added
     * query/fragment state. Both chatgpt.com and www.chatgpt.com are accepted because the
     * WebView-side guard already treats them as the same trusted ChatGPT surface.
     */
    static boolean isGeneralChatUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH || containsControl(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme())
                    || !("chatgpt.com".equals(host) || "www.chatgpt.com".equals(host))
                    || uri.getRawUserInfo() != null || uri.getPort() != -1) return false;
            String rawPath = uri.getRawPath();
            String path = uri.getPath();
            if (rawPath == null || !rawPath.equals(path) || rawPath.contains("//")
                    || rawPath.contains("\\") || rawPath.contains("..") || rawPath.contains("%")) return false;
            if (path.isEmpty() || "/".equals(path)) return true;
            String[] segments = path.split("/");
            return segments.length == 3 && "c".equals(segments[1]) && validOpaqueId(segments[2]);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String conversationId(String url) {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(url);
        if (ref != null) return ref.conversationId;
        if (!isGeneralChatUrl(url)) return "";
        try {
            String[] segments = java.net.URI.create(url.trim()).getPath().split("/");
            return segments.length == 3 && "c".equals(segments[1]) && validOpaqueId(segments[2])
                    ? segments[2] : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean validOpaqueId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_OPAQUE_ID_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
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
