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
    static final String CONTINUATION_NO_PROGRESS = "CONTINUATION_NO_PROGRESS";
    static final String CONTINUATION_NO_START_TIMEOUT = "CONTINUATION_NO_START_TIMEOUT";
    static final String WEBVIEW_CREATE_FAILURE = "WEBVIEW_CREATE_FAILURE";
    static final String BOOTSTRAP_SUBMISSION_TIMEOUT = "BOOTSTRAP_SUBMISSION_TIMEOUT";
    static final int MAX_LOCAL_FAILURES = 3;
    static final long CONTINUATION_HARD_FAILURE_GRACE_MS = 5_000L;
    static final long CONTINUATION_SOFT_STALL_GRACE_MS = 15_000L;
    static final long CONTINUATION_NO_START_MAX_WAIT_MS = 60_000L;
    static final int NO_START_WAIT = 0;
    static final int NO_START_ROLLOVER = 1;
    static final int NO_START_PAUSE_TRANSIENT = 2;

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

    static boolean trustedChatgptServiceHost(String host) {
        if (host == null) return false;
        String normalized = host.trim().toLowerCase(java.util.Locale.ROOT);
        return "chatgpt.com".equals(normalized) || normalized.endsWith(".chatgpt.com");
    }

    static boolean rolloverRenderer(String conversationUrl, boolean didCrash) {
        return knownConversation(conversationUrl) && didCrash;
    }

    static boolean hardContinuationFailureStatus(String status) {
        return "SUBMISSION_AMBIGUOUS".equals(status) || "MARKER_FAILED".equals(status)
                || "SUBMISSION_FAILED".equals(status)
                || SelfRunContinuationDom.UNKNOWN.equals(status) || "SCRIPT_ERROR".equals(status);
    }

    static boolean softContinuationStallStatus(String status) {
        return "COMPOSER_CLEARING".equals(status) || "COMPOSER_INPUTTING".equals(status)
                || SelfRunContinuationDom.SEND_DISABLED.equals(status);
    }

    static String continuationFailureBucket(String status) {
        if (hardContinuationFailureStatus(status)) return "CONTINUATION_HARD_NO_PROGRESS";
        if (softContinuationStallStatus(status)) return "CONTINUATION_SOFT_STALL";
        return normalizeCause(status);
    }

    static boolean shouldCountContinuationFailure(String status, long phaseStartedAt, long now) {
        if (phaseStartedAt <= 0L || now < phaseStartedAt) return false;
        long elapsed = now - phaseStartedAt;
        if (hardContinuationFailureStatus(status)) return elapsed >= CONTINUATION_HARD_FAILURE_GRACE_MS;
        return softContinuationStallStatus(status) && elapsed >= CONTINUATION_SOFT_STALL_GRACE_MS;
    }

    static int postDispatchNoStartAction(long dispatchStartedElapsed, boolean sawStop,
                                         long validatedSinceElapsed, long nowElapsed,
                                         boolean transientSeen) {
        if (sawStop || dispatchStartedElapsed <= 0L || validatedSinceElapsed <= 0L
                || nowElapsed < dispatchStartedElapsed || nowElapsed < validatedSinceElapsed) return NO_START_WAIT;
        long continuouslyValidatedStart = Math.max(dispatchStartedElapsed, validatedSinceElapsed);
        if (nowElapsed - continuouslyValidatedStart < CONTINUATION_NO_START_MAX_WAIT_MS) return NO_START_WAIT;
        return transientSeen ? NO_START_PAUSE_TRANSIENT : NO_START_ROLLOVER;
    }

    static boolean postDispatchNoStartTimedOut(long dispatchStartedElapsed, boolean sawStop,
                                                long validatedSinceElapsed, long nowElapsed) {
        return postDispatchNoStartAction(dispatchStartedElapsed, sawStop, validatedSinceElapsed,
                nowElapsed, false) == NO_START_ROLLOVER;
    }

    static boolean continuationProgressStatus(String status) {
        return "READY".equals(status) || "READY_TO_SUBMIT".equals(status)
                || SelfRunContinuationDom.SUBMISSION_PENDING.equals(status)
                || "CONTINUE_CLICKED".equals(status) || "SUBMISSION_CONFIRMED".equals(status)
                || "VERIFY_REQUIRED".equals(status) || "OBSERVER_ARMED".equals(status);
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
