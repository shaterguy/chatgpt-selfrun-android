package com.shaterguy.chatgptselfrun;

import java.net.URI;

/** Strict, shared policy for every persisted or executed ChatGPT project target. */
final class ProjectUrlPolicy {
    static final class ProjectRef {
        final String projectId;
        final String conversationId;
        final String canonicalUrl;

        ProjectRef(String projectId, String conversationId) {
            this.projectId = projectId;
            this.conversationId = conversationId;
            this.canonicalUrl = "https://chatgpt.com/g/" + projectId + "/project";
        }
    }

    private ProjectUrlPolicy() { }

    static ProjectRef parseProject(String raw) {
        ChatRoutePolicy.Route route = ChatRoutePolicy.parse(raw);
        return route == null || route.general ? null : new ProjectRef(route.projectId, route.conversationId);
    }

    static boolean isTrustedChatgptPage(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 2048) return false;
        try {
            URI uri = new URI(raw);
            return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (Exception ignored) { return false; }
    }

    static boolean sameProject(String a, String b) {
        ProjectRef left = parseProject(a), right = parseProject(b);
        return left != null && right != null && ChatRoutePolicy.sameScope(a, b);
    }

    static boolean sameConversation(String expected, String actual) {
        return parseProject(expected) != null && parseProject(actual) != null
                && ChatRoutePolicy.sameConversation(expected, actual);
    }
}
