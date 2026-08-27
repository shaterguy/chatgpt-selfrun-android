package com.shaterguy.chatgptselfrun;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.UUID;

/** Coordinates one explicit user immediate-input attempt without advancing the SelfRun state machine. */
final class UserImmediateInputCoordinator {
    static final String OUTCOME_SENT = "SENT";
    static final String OUTCOME_DEFERRED = "DEFERRED";
    static final String OUTCOME_FAILED = "FAILED";

    private static final long SEND_SETTLE_MS = 160L;
    private static final long RETRY_MS = 90L;
    private static final int MAX_CALLBACK_RETRIES = 2;
    private static final int MAX_CLEANUP_RETRIES = 3;

    interface Callback { void onResult(Result result); }

    static final class Result {
        final String outcome;
        final String detail;

        Result(String outcome, String detail) {
            this.outcome = safe(outcome);
            this.detail = safe(detail);
        }
    }

    private UserImmediateInputCoordinator() {}

    static void submit(SelfRunStore store, SelfRunRunLog runLog, String text, Callback callback) {
        String value = safe(text);
        String runId = safe(store == null ? "" : store.runId());
        if (store == null || runLog == null || callback == null || runId.isEmpty()) {
            if (callback != null) callback.onResult(new Result(OUTCOME_FAILED, "run unavailable"));
            return;
        }
        if (value.trim().isEmpty() || !UserNextInputStore.withinUtf8Limit(
                value, UserNextInputStore.MAX_USER_UTF8_BYTES)) {
            callback.onResult(new Result(OUTCOME_FAILED, "invalid input"));
            return;
        }
        WebView view = HeadlessWebViewHost.activeWebView();
        String conversationUrl = safe(store.conversationUrl());
        if (!immediateEligible(store.active(), store.paused(), store.userStopped(), store.phase(),
                conversationUrl, view != null) || !UserNextInputStore.editable(runId)) {
            defer(store, runLog, runId, value, callback, "current response unavailable");
            return;
        }
        String requestId = runId + ":" + UUID.randomUUID().toString().replace("-", "");
        new Attempt(store, runLog, runId, conversationUrl, value, requestId, view, callback).prepare(0);
    }

    static boolean immediateEligible(boolean active, boolean paused, boolean stopped, String phase,
                                     String conversationUrl, boolean webViewAvailable) {
        return active && !paused && !stopped && webViewAvailable
                && SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(safe(phase))
                && !SelfRunScript.conversationId(safe(conversationUrl)).isEmpty();
    }

    static boolean matchingReservation(String stored, String requested) {
        return !safe(requested).isEmpty() && safe(requested).equals(safe(stored));
    }

    private static final class Attempt {
        final SelfRunStore store;
        final SelfRunRunLog runLog;
        final String runId;
        final String conversationUrl;
        final String text;
        final String requestId;
        final WebView view;
        final Callback callback;
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean finished;

        Attempt(SelfRunStore store, SelfRunRunLog runLog, String runId, String conversationUrl,
                String text, String requestId, WebView view, Callback callback) {
            this.store = store;
            this.runLog = runLog;
            this.runId = runId;
            this.conversationUrl = conversationUrl;
            this.text = text;
            this.requestId = requestId;
            this.view = view;
            this.callback = callback;
        }

        void prepare(int retry) {
            if (finished) return;
            if (!sameRun() || HeadlessWebViewHost.activeWebView() != view) {
                deferNow("webview changed before prepare");
                return;
            }
            evaluate(UserImmediateInputDom.prepare(conversationUrl, text, requestId), parsed -> {
                if (finished) return;
                if (!parsed.valid) {
                    if (retry < MAX_CALLBACK_RETRIES) {
                        handler.postDelayed(() -> prepare(retry + 1), RETRY_MS);
                    } else cleanupAndDefer(0, "prepare callback ambiguous");
                    return;
                }
                if (UserImmediateInputDom.SENT.equals(parsed.status)) { finishSent("prepare marker already sent"); return; }
                if (UserImmediateInputDom.PREPARED.equals(parsed.status)) {
                    handler.postDelayed(() -> resolve(0), SEND_SETTLE_MS);
                    return;
                }
                if (UserImmediateInputDom.CLEANUP_PENDING.equals(parsed.status)) {
                    cleanupAndDefer(0, "prepare cleanup pending");
                    return;
                }
                if (UserImmediateInputDom.DEFERRED.equals(parsed.status)
                        || "TARGET_ERROR".equals(parsed.status) || "AUTH_REQUIRED".equals(parsed.status)) {
                    deferNow("prepare deferred: " + parsed.status);
                    return;
                }
                cleanupAndDefer(0, "unexpected prepare status: " + parsed.status);
            });
        }

        void resolve(int retry) {
            if (finished) return;
            if (!sameRun() || HeadlessWebViewHost.activeWebView() != view) {
                deferNow("webview changed before resolve");
                return;
            }
            if (!immediateEligible(store.active(), store.paused(), store.userStopped(), store.phase(),
                    store.conversationUrl(), true)) {
                cleanupAndDefer(0, "response phase moved before SEND decision");
                return;
            }
            evaluate(UserImmediateInputDom.resolve(conversationUrl, text, requestId), parsed -> {
                if (finished) return;
                if (!parsed.valid) {
                    if (retry < MAX_CALLBACK_RETRIES) {
                        handler.postDelayed(() -> resolve(retry + 1), RETRY_MS);
                    } else cleanupAndDefer(0, "resolve callback ambiguous");
                    return;
                }
                if (UserImmediateInputDom.SENT.equals(parsed.status)) { finishSent("resolve marker already sent"); return; }
                if (UserImmediateInputDom.SEND_READY.equals(parsed.status)) {
                    removeMatchingReservationThenClick();
                    return;
                }
                if (UserImmediateInputDom.DEFERRED.equals(parsed.status)) {
                    deferNow("enabled SEND unavailable");
                    return;
                }
                if (UserImmediateInputDom.CLEANUP_PENDING.equals(parsed.status)) {
                    cleanupAndDefer(0, "resolve cleanup pending");
                    return;
                }
                cleanupAndDefer(0, "unexpected resolve status: " + parsed.status);
            });
        }

        void removeMatchingReservationThenClick() {
            if (finished) return;
            String stored = UserNextInputStore.current(runId);
            if (matchingReservation(stored, text) && !UserNextInputStore.delete(runId)) {
                cleanupAndDefer(0, "matching reservation could not be released safely");
                return;
            }
            click(0);
        }

        void click(int retry) {
            if (finished) return;
            if (!sameRun() || HeadlessWebViewHost.activeWebView() != view
                    || !SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())) {
                cleanupAndDefer(0, "response phase moved before immediate click");
                return;
            }
            evaluate(UserImmediateInputDom.click(conversationUrl, text, requestId), parsed -> {
                if (finished) return;
                if (!parsed.valid) {
                    if (retry < MAX_CALLBACK_RETRIES) {
                        handler.postDelayed(() -> click(retry + 1), RETRY_MS);
                    } else cleanupAndDefer(0, "click callback ambiguous");
                    return;
                }
                if (UserImmediateInputDom.SENT.equals(parsed.status)) { finishSent("enabled SEND clicked"); return; }
                if (UserImmediateInputDom.DEFERRED.equals(parsed.status)) {
                    deferNow("SEND disappeared before click");
                    return;
                }
                if (UserImmediateInputDom.CLEANUP_PENDING.equals(parsed.status)) {
                    cleanupAndDefer(0, "click cleanup pending");
                    return;
                }
                cleanupAndDefer(0, "unexpected click status: " + parsed.status);
            });
        }

        void cleanupAndDefer(int retry, String reason) {
            if (finished) return;
            if (!sameRun()) { finishFailed("run changed during cleanup"); return; }
            if (HeadlessWebViewHost.activeWebView() != view) {
                deferNow(reason + "; old webview gone");
                return;
            }
            evaluate(UserImmediateInputDom.cleanup(conversationUrl, text, requestId), parsed -> {
                if (finished) return;
                if (!parsed.valid) {
                    if (retry < MAX_CLEANUP_RETRIES) {
                        handler.postDelayed(() -> cleanupAndDefer(retry + 1, reason), RETRY_MS);
                    } else finishFailed(reason + "; cleanup callback ambiguous");
                    return;
                }
                if (UserImmediateInputDom.SENT.equals(parsed.status)) { finishSent(reason + "; sent marker recovered"); return; }
                if (UserImmediateInputDom.DEFERRED.equals(parsed.status)) { deferNow(reason); return; }
                if (UserImmediateInputDom.CLEANUP_PENDING.equals(parsed.status) && retry < MAX_CLEANUP_RETRIES) {
                    handler.postDelayed(() -> cleanupAndDefer(retry + 1, reason), RETRY_MS);
                    return;
                }
                finishFailed(reason + "; cleanup not confirmed");
            });
        }

        void evaluate(String script, ParsedCallback parsedCallback) {
            try {
                view.evaluateJavascript(script, raw -> parsedCallback.onParsed(BootstrapResultPolicy.parse(raw)));
            } catch (Throwable error) {
                parsedCallback.onParsed(BootstrapResultPolicy.parse(null));
            }
        }

        boolean sameRun() {
            return runId.equals(store.runId()) && store.active() && !store.userStopped();
        }

        void deferNow(String reason) {
            if (finished) return;
            if (UserNextInputStore.save(runId, text)) {
                runLog.record(store, "IMMEDIATE_INPUT", "result=deferred;reason=" + logSafe(reason));
                finish(new Result(OUTCOME_DEFERRED, reason));
            } else {
                finishFailed(reason + "; next-turn reservation failed");
            }
        }

        void finishSent(String detail) {
            if (finished) return;
            runLog.record(store, "IMMEDIATE_INPUT", "result=sent;detail=" + logSafe(detail));
            finish(new Result(OUTCOME_SENT, detail));
        }

        void finishFailed(String detail) {
            if (finished) return;
            runLog.record(store, "IMMEDIATE_INPUT", "result=failed;detail=" + logSafe(detail));
            finish(new Result(OUTCOME_FAILED, detail));
        }

        void finish(Result result) {
            if (finished) return;
            finished = true;
            callback.onResult(result);
        }
    }

    private interface ParsedCallback { void onParsed(BootstrapResultPolicy.Parsed parsed); }

    private static void defer(SelfRunStore store, SelfRunRunLog runLog, String runId, String text,
                              Callback callback, String reason) {
        if (UserNextInputStore.save(runId, text)) {
            runLog.record(store, "IMMEDIATE_INPUT", "result=deferred;reason=" + logSafe(reason));
            callback.onResult(new Result(OUTCOME_DEFERRED, reason));
        } else {
            runLog.record(store, "IMMEDIATE_INPUT", "result=failed;detail=fallback_reservation_failed");
            callback.onResult(new Result(OUTCOME_FAILED, reason + "; next-turn reservation failed"));
        }
    }

    private static String logSafe(String value) {
        String normalized = safe(value).replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
