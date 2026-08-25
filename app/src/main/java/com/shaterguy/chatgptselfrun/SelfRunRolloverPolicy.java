package com.shaterguy.chatgptselfrun;

import android.webkit.WebViewClient;

import java.util.LinkedHashSet;
import java.util.Set;

final class SelfRunRolloverPolicy {
    static final String ROUTE_MISMATCH = "ROUTE_MISMATCH";
    static final String TARGET_ERROR = "TARGET_ERROR";
    static final String WEBVIEW_MAIN_FRAME_LOCAL_ERROR = "WEBVIEW_MAIN_FRAME_LOCAL_ERROR";
    static final String WEBVIEW_HTTP_GONE = "WEBVIEW_HTTP_GONE";
    static final String RENDERER_CRASH = "RENDERER_CRASH";
    static final String TURN_COMPLETION_SIGNAL_TIMEOUT = "TURN_COMPLETION_SIGNAL_TIMEOUT";
    static final String CONTINUATION_CALLBACK_TIMEOUT = "CONTINUATION_CALLBACK_TIMEOUT";
    static final String WEBVIEW_CREATE_FAILURE = "WEBVIEW_CREATE_FAILURE";
    static final String BOOTSTRAP_SUBMISSION_TIMEOUT = "BOOTSTRAP_SUBMISSION_TIMEOUT";
    static final int MAX_LOCAL_FAILURES = 3;

    private SelfRunRolloverPolicy() {}

    static boolean knownConversation(String conversationUrl) {
        return conversationUrl != null && !SelfRunScript.conversationId(conversationUrl).isEmpty();
    }

    static boolean transientWebError(int code) {
        return code == WebViewClient.ERROR_HOST_LOOKUP
                || code == WebViewClient.ERROR_CONNECT
                || code == WebViewClient.ERROR_IO
                || code == WebViewClient.ERROR_TIMEOUT
                || code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                || code == WebViewClient.ERROR_TOO_MANY_REQUESTS;
    }

    static boolean rolloverMainFrameError(String conversationUrl, boolean networkValidated, int errorCode) {
        return knownConversation(conversationUrl) && networkValidated && !transientWebError(errorCode);
    }

    static boolean rolloverHttpStatus(String conversationUrl, boolean networkValidated, int status) {
        return knownConversation(conversationUrl) && networkValidated && (status == 404 || status == 410);
    }

    static boolean retryHttpStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    static boolean rolloverRenderer(String conversationUrl, boolean didCrash) {
        return knownConversation(conversationUrl) && didCrash;
    }

    static boolean localFailureBudgetExhausted(int failures) {
        return failures >= MAX_LOCAL_FAILURES;
    }

    static String normalizeCause(String cause) {
        String value = cause == null ? "" : cause.trim();
        if (value.matches("[A-Z0-9_]{1,80}")) return value;
        return "UNKNOWN_CONVERSATION_LOCAL_FAILURE";
    }

    static Set<String> parseCauses(String serialized) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (serialized == null || serialized.isEmpty()) return result;
        for (String item : serialized.split(",")) {
            String normalized = normalizeCause(item);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    static String appendCause(String serialized, String cause) {
        Set<String> causes = parseCauses(serialized);
        causes.add(normalizeCause(cause));
        return String.join(",", causes);
    }

    static boolean containsCause(String serialized, String cause) {
        return parseCauses(serialized).contains(normalizeCause(cause));
    }
}
