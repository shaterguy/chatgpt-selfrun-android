package com.shaterguy.chatgptselfrun;

/** Shared URL parsing and JavaScript string quoting helpers. */
final class SelfRunScript {
    static final String GENERAL_CHAT_URL = "https://chatgpt.com/";
    static final String GENERAL_CHAT_SCOPE = "__GENERAL_CHAT__";

    private SelfRunScript() {}

    static String projectId(String url) {
        ChatRoutePolicy.Route route = ChatRoutePolicy.parse(url);
        return route == null ? "" : (route.general ? GENERAL_CHAT_SCOPE : route.projectId);
    }

    static boolean isGeneralChatUrl(String url) {
        ChatRoutePolicy.Route route = ChatRoutePolicy.parse(url);
        return route != null && route.general;
    }

    static String conversationId(String url) {
        ChatRoutePolicy.Route route = ChatRoutePolicy.parse(url);
        return route == null ? "" : route.conversationId;
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
