package com.shaterguy.chatgptselfrun;

import java.net.URI;
import java.util.Locale;

/** Strict, shared policy for every persisted or executed ChatGPT project target. */
final class ProjectUrlPolicy {
    private static final String HOST = "chatgpt.com";
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_ID_LENGTH = 160;

    static final class ProjectRef {
        final String projectId;
        final String conversationId;
        final String canonicalUrl;

        ProjectRef(String projectId, String conversationId) {
            this.projectId = projectId;
            this.conversationId = conversationId;
            this.canonicalUrl = "https://" + HOST + "/g/" + projectId + "/project";
        }
    }

    private ProjectUrlPolicy() { }

    static ProjectRef parseProject(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_URL_LENGTH || containsControl(raw)) return null;
        try {
            URI uri = new URI(raw);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String rawPath = uri.getRawPath();
            String path = uri.getPath();
            if (rawPath == null || !rawPath.equals(path) || rawPath.contains("//") || rawPath.contains("\\")
                    || rawPath.contains("..") || rawPath.contains("%")) return null;
            String[] parts = path.split("/", -1);
            if (parts.length < 3 || !"".equals(parts[0]) || !"g".equals(parts[1]) || !validProjectId(parts[2])) return null;
            if (parts.length == 3 || (parts.length == 4 && parts[3].isEmpty())) return new ProjectRef(parts[2], "");
            if (parts.length == 4 && "project".equals(parts[3])) return new ProjectRef(parts[2], "");
            if (parts.length == 5 && "c".equals(parts[3]) && validOpaqueId(parts[4])) return new ProjectRef(parts[2], parts[4]);
        } catch (Exception ignored) { }
        return null;
    }

    static boolean isTrustedChatgptPage(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_URL_LENGTH || containsControl(raw)) return false;
        try {
            URI uri = new URI(raw);
            return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (Exception ignored) { return false; }
    }

    static boolean sameProject(String a, String b) {
        ProjectRef left = parseProject(a), right = parseProject(b);
        return left != null && right != null && left.projectId.equals(right.projectId);
    }

    static boolean sameConversation(String expected, String actual) {
        ProjectRef left = parseProject(expected), right = parseProject(actual);
        return left != null && right != null && !left.conversationId.isEmpty()
                && left.projectId.equals(right.projectId) && left.conversationId.equals(right.conversationId);
    }

    private static boolean validProjectId(String value) {
        return value != null && value.startsWith("g-p-") && validOpaqueId(value);
    }

    private static boolean validOpaqueId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ID_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
}
