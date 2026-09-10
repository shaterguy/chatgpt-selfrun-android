package com.shaterguy.chatgptselfrun;

/** Metadata-only waiting. No response UI inspection, rendering, or held wake lock. */
final class SelfRun3PowerPolicy {
    static final long WEB_STEP_RETRY_MS = 500L;
    static final long CALLBACK_TIMEOUT_MS = 5_000L;
    static final long WAKE_LOCK_MAX_MS = 90_000L;
    private static final long[] NETWORK_RETRIES = {15_000L,30_000L,60_000L,120_000L,240_000L};
    static long networkRetryDelay(int attempt) { return NETWORK_RETRIES[Math.min(Math.max(attempt,0),NETWORK_RETRIES.length-1)]; }
    static boolean mayAttachOutput(SelfRun3Engine.Stage stage) { return stage == SelfRun3Engine.Stage.READY; }
    static boolean maySend(SelfRun3Engine.State state) { return state != null && state.stage() == SelfRun3Engine.Stage.DISPATCHING && state.flag("sendClaimed") && !state.flag("accepted") && !state.flag("dispatchObserved"); }
    private SelfRun3PowerPolicy() {}
}
