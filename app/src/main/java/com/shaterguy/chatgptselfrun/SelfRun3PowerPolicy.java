package com.shaterguy.chatgptselfrun;

/** Bounded exception-path budgets; normal generation has no polling, rendering, or held wake lock. */
final class SelfRun3PowerPolicy {
    static final long NORMAL_WAIT_POLL_MS = 0L;
    static final long MISSED_CALLBACK_PROBE_MS = 10 * 60_000L;
    static final long WEB_PREPARATION_MAX_MS = 90_000L;
    static final long WEB_STEP_RETRY_MS = 500L;
    static final long CALLBACK_TIMEOUT_MS = 5_000L;
    static final long RECEIPT_STABILITY_MS = 5_000L;
    static final long WAKE_LOCK_MAX_MS = 90_000L;
    static final int MAX_MISSED_CALLBACK_PROBES = 3;
    static final int MAX_RECEIPT_PROBES = 3;
    private static final long[] RESULT_RETRIES = {5_000L,15_000L,30_000L,60_000L,90_000L};
    private static final long[] NETWORK_RETRIES = {15_000L,30_000L,60_000L,120_000L,240_000L};
    static long resultRetryDelay(int attempt) { return attempt < 0 || attempt >= RESULT_RETRIES.length ? -1L : RESULT_RETRIES[attempt]; }
    static long networkRetryDelay(int attempt) { return NETWORK_RETRIES[Math.min(Math.max(attempt,0),NETWORK_RETRIES.length-1)]; }
    static boolean mayAttachOutput(SelfRun3Engine.Stage stage) { return stage == SelfRun3Engine.Stage.READY; }
    static boolean maySend(SelfRun3Engine.State state) { return state.stage() == SelfRun3Engine.Stage.DISPATCHING && state.flag("sendClaimed") && !state.flag("accepted") && !state.flag("ended"); }
    private SelfRun3PowerPolicy() {}
}
