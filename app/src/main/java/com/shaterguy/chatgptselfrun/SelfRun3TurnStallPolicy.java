package com.shaterguy.chatgptselfrun;

/** Read-only policy for warning when one dispatched conversation has not advanced for 125 minutes. */
final class SelfRun3TurnStallPolicy {
    static final long ALERT_AFTER_MS = 7_500_000L;
    static final long NO_ALERT_DELAY_MS = -1L;

    static long remainingDelayMs(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                                 int currentBootCount) {
        if (!alertable(state)) return NO_ALERT_DELAY_MS;

        org.json.JSONObject snapshot = state.json();
        if (snapshot.has("canonicalPostConfirmedElapsed") && snapshot.has("canonicalPostBootCount")) {
            long anchorElapsed = state.time("canonicalPostConfirmedElapsed");
            int anchorBootCount = state.number("canonicalPostBootCount");
            if (anchorElapsed >= 0L && currentBootCount >= 0 && anchorBootCount == currentBootCount
                    && nowElapsed >= anchorElapsed) {
                return remaining(nowElapsed - anchorElapsed);
            }
        }

        if (!snapshot.has("canonicalPostConfirmedAtWall")) return NO_ALERT_DELAY_MS;
        long anchorWall = state.time("canonicalPostConfirmedAtWall");
        if (anchorWall <= 0L || nowWall < anchorWall) return NO_ALERT_DELAY_MS;
        return remaining(nowWall - anchorWall);
    }

    static boolean shouldAlert(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                               int currentBootCount) {
        return remainingDelayMs(state, nowElapsed, nowWall, currentBootCount) == 0L;
    }

    private static boolean alertable(SelfRun3Engine.State state) {
        if (state == null || !state.flag("sendClaimed") || state.flag("committed")
                || state.flag("superseded") || state.hasResult()) return false;
        SelfRun3Engine.Stage stage = state.stage();
        return stage == SelfRun3Engine.Stage.WAITING || stage == SelfRun3Engine.Stage.RECONCILING;
    }

    private static long remaining(long ageMs) {
        return ageMs >= ALERT_AFTER_MS ? 0L : ALERT_AFTER_MS - ageMs;
    }

    private SelfRun3TurnStallPolicy() { }
}
