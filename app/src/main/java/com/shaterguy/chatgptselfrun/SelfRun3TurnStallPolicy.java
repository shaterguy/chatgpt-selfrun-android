package com.shaterguy.chatgptselfrun;

/** Read-only policy for warning when one dispatched conversation has not advanced for 125 minutes. */
final class SelfRun3TurnStallPolicy {
    static final long ALERT_AFTER_MS = 7_500_000L;

    static boolean shouldAlert(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                               int currentBootCount) {
        if (state == null || !state.flag("sendClaimed") || state.flag("committed")
                || state.flag("superseded") || state.hasResult()) return false;
        SelfRun3Engine.Stage stage = state.stage();
        if (stage != SelfRun3Engine.Stage.WAITING && stage != SelfRun3Engine.Stage.RECONCILING) return false;

        org.json.JSONObject snapshot = state.json();
        if (snapshot.has("canonicalPostConfirmedElapsed") && snapshot.has("canonicalPostBootCount")) {
            long anchorElapsed = state.time("canonicalPostConfirmedElapsed");
            int anchorBootCount = state.number("canonicalPostBootCount");
            if (anchorElapsed >= 0L && currentBootCount >= 0 && anchorBootCount == currentBootCount
                    && nowElapsed >= anchorElapsed) {
                return nowElapsed - anchorElapsed >= ALERT_AFTER_MS;
            }
        }

        if (!snapshot.has("canonicalPostConfirmedAtWall")) return false;
        long anchorWall = state.time("canonicalPostConfirmedAtWall");
        return anchorWall > 0L && nowWall >= anchorWall && nowWall - anchorWall >= ALERT_AFTER_MS;
    }

    private SelfRun3TurnStallPolicy() { }
}
