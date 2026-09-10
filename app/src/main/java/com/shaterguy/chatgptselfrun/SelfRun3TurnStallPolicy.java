package com.shaterguy.chatgptselfrun;

/** Read-only policy for warning when one dispatched conversation has not advanced past the configured threshold. */
final class SelfRun3TurnStallPolicy {
    static final long ALERT_AFTER_MS = SelfRun3RuntimeSettings.DEFAULT_STALL_ALERT_MINUTES * 60_000L;
    static final long NO_ALERT_DELAY_MS = -1L;

    static long remainingDelayMs(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                                 int currentBootCount) {
        return remainingDelayMs(state, nowElapsed, nowWall, currentBootCount, ALERT_AFTER_MS);
    }

    static long remainingDelayMs(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                                 int currentBootCount, long alertAfterMs) {
        if (alertAfterMs <= 0L || !alertable(state)) return NO_ALERT_DELAY_MS;

        org.json.JSONObject snapshot = state.json();
        if (snapshot.has("canonicalPostConfirmedElapsed") && snapshot.has("canonicalPostBootCount")) {
            long anchorElapsed = state.time("canonicalPostConfirmedElapsed");
            int anchorBootCount = state.number("canonicalPostBootCount");
            if (anchorElapsed >= 0L && currentBootCount >= 0 && anchorBootCount == currentBootCount
                    && nowElapsed >= anchorElapsed) {
                return remaining(nowElapsed - anchorElapsed, alertAfterMs);
            }
        }

        if (!snapshot.has("canonicalPostConfirmedAtWall")) return NO_ALERT_DELAY_MS;
        long anchorWall = state.time("canonicalPostConfirmedAtWall");
        if (anchorWall <= 0L || nowWall < anchorWall) return NO_ALERT_DELAY_MS;
        return remaining(nowWall - anchorWall, alertAfterMs);
    }

    static boolean shouldAlert(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                               int currentBootCount) {
        return shouldAlert(state, nowElapsed, nowWall, currentBootCount, ALERT_AFTER_MS);
    }

    static boolean shouldAlert(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                               int currentBootCount, long alertAfterMs) {
        return remainingDelayMs(state, nowElapsed, nowWall, currentBootCount, alertAfterMs) == 0L;
    }

    private static boolean alertable(SelfRun3Engine.State state) {
        if (state == null || !state.flag("sendClaimed") || state.flag("committed")
                || state.flag("superseded") || state.hasResult()) return false;
        SelfRun3Engine.Stage stage = state.stage();
        return stage == SelfRun3Engine.Stage.WAITING || stage == SelfRun3Engine.Stage.RECONCILING;
    }

    private static long remaining(long ageMs, long alertAfterMs) {
        return ageMs >= alertAfterMs ? 0L : alertAfterMs - ageMs;
    }

    private SelfRun3TurnStallPolicy() { }
}
