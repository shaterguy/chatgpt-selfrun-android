package com.shaterguy.chatgptselfrun;

import java.net.URI;

/** Strict parser and comparison policy for supported general and project ChatGPT routes. */
final class ChatRoutePolicy {
    private static final String HOST = "chatgpt.com";
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_ID_LENGTH = 160;

    static final class Route {
        final boolean general;
        final String projectId;
        final String conversationId;
        final String canonicalNewUrl;

        Route(boolean general, String projectId, String conversationId) {
            this.general = general;
            this.projectId = projectId;
            this.conversationId = conversationId;
            this.canonicalNewUrl = general
                    ? SelfRunScript.GENERAL_CHAT_URL
                    : "https://" + HOST + "/g/" + projectId + "/project";
        }

        boolean hasConversation() { return !conversationId.isEmpty(); }
    }

    private ChatRoutePolicy() { }

    static Route parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_URL_LENGTH || containsControl(raw)) return null;
        try {
            URI uri = new URI(raw);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String rawPath = uri.getRawPath(), path = uri.getPath();
            if (rawPath == null || !rawPath.equals(path) || rawPath.contains("//")
                    || rawPath.contains("\\") || rawPath.contains("..") || rawPath.contains("%")) return null;
            String[] parts = path.split("/", -1);
            if (parts.length == 2 && "".equals(parts[0]) && "".equals(parts[1])) {
                return new Route(true, "", "");
            }
            if (parts.length == 3 && "".equals(parts[0]) && "c".equals(parts[1])
                    && validOpaqueId(parts[2])) {
                return new Route(true, "", parts[2]);
            }
            if (parts.length < 3 || !"".equals(parts[0]) || !"g".equals(parts[1])
                    || !validProjectId(parts[2])) return null;
            if (parts.length == 3 || (parts.length == 4 && parts[3].isEmpty())
                    || (parts.length == 4 && "project".equals(parts[3]))) {
                return new Route(false, parts[2], "");
            }
            if (parts.length == 5 && "c".equals(parts[3]) && validOpaqueId(parts[4])) {
                return new Route(false, parts[2], parts[4]);
            }
        } catch (Exception ignored) { }
        return null;
    }

    static boolean sameScope(String expected, String actual) {
        Route left = parse(expected), right = parse(actual);
        return left != null && right != null && left.general == right.general
                && (left.general || left.projectId.equals(right.projectId));
    }

    static boolean sameConversation(String expected, String actual) {
        Route left = parse(expected), right = parse(actual);
        return left != null && right != null && left.hasConversation()
                && sameScope(expected, actual) && left.conversationId.equals(right.conversationId);
    }

    static boolean shouldCaptureBootstrapConversation(boolean bootstrapSubmitted,
                                                      String existingConversationUrl,
                                                      String expectedScopeUrl, String actualUrl) {
        Route actual = parse(actualUrl);
        return existingConversationUrl != null && existingConversationUrl.isEmpty()
                && bootstrapSubmitted
                && actual != null && actual.hasConversation() && sameScope(expectedScopeUrl, actualUrl);
    }

    private static boolean validProjectId(String value) {
        return value != null && value.startsWith("g-p-") && validOpaqueId(value);
    }

    private static boolean validOpaqueId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ID_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }
}
