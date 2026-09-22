package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/** Durable policy for one accepted Result driving one automatic serial successor. */
final class SelfRun3SuccessorTransitionPolicy {
    static final String KEY = "successorTransition";
    static final long NO_DEADLINE = -1L;
    private static final Set<String> EXCLUDED_STATUSES =
            Set.of("DONE", "USER_ACTION_REQUIRED", "PAUSED");

    private SelfRun3SuccessorTransitionPolicy() { }

    static boolean shouldTrack(JSONObject result, SelfRun3Engine.State source, JSONObject payload) {
        if (result == null || source == null || payload == null || SelfRun3Engine.isBranch(source)
                || source.flag("taskStopped") || source.flag("taskPaused")
                || EXCLUDED_STATUSES.contains(result.optString("status"))) return false;
        JSONObject plan = result.optJSONObject("next_execution");
        if (plan == null || !"SERIAL".equals(plan.optString("type"))) return false;
        return payload.optLong("acceptedAtElapsed", -1L) >= 0L
                && payload.optLong("acceptedAtWall", -1L) > 0L
                && payload.optLong("successorTimeoutMs", -1L) > 0L;
    }

    static JSONObject begin(SelfRun3Engine.State source, JSONObject payload) {
        JSONObject transition = new JSONObject();
        SelfRun3Engine.put(transition, "predecessorTurnId", source.turnId());
        SelfRun3Engine.put(transition, "predecessorRequestId", source.requestId());
        SelfRun3Engine.put(transition, "predecessorResultDocumentId", source.resource("resultDocumentId"));
        SelfRun3Engine.put(transition, "acceptedAtElapsed", payload.optLong("acceptedAtElapsed"));
        SelfRun3Engine.put(transition, "acceptedAtWall", payload.optLong("acceptedAtWall"));
        SelfRun3Engine.put(transition, "acceptedBootCount", payload.optInt("acceptedBootCount", -1));
        SelfRun3Engine.put(transition, "watchdogStartedElapsed", payload.optLong("acceptedAtElapsed"));
        SelfRun3Engine.put(transition, "watchdogStartedAtWall", payload.optLong("acceptedAtWall"));
        SelfRun3Engine.put(transition, "watchdogBootCount", payload.optInt("acceptedBootCount", -1));
        SelfRun3Engine.put(transition, "timeoutMs", payload.optLong("successorTimeoutMs"));
        SelfRun3Engine.put(transition, "recoveryAttempt", 0);
        return transition;
    }

    static boolean armed(SelfRun3Engine.State state) {
        JSONObject transition = state == null ? new JSONObject() : state.successorTransition();
        return transition.optBoolean("active")
                && "VALID".equals(transition.optString("routingState"))
                && transition.optLong("timeoutMs", -1L) > 0L
                && SelfRun3Engine.validId(transition.optString("predecessorTurnId"));
    }

    static boolean routingValid(SelfRun3Engine.State state) {
        return state != null && "VALID".equals(state.successorTransition().optString("routingState"));
    }

    static boolean routingInvalid(SelfRun3Engine.State state) {
        return state != null && "INVALID".equals(state.successorTransition().optString("routingState"));
    }

    static JSONObject routingProfile(SelfRun3Engine.State state) {
        return SelfRun3Engine.copy(state.successorTransition().optJSONObject("profile"));
    }

    static String routingPhase(SelfRun3Engine.State state) {
        String phase = state.successorTransition().optString("phase");
        return Set.of("PLAN", "WORK", "VERIFY").contains(phase) ? phase : "PLAN";
    }

    static String routingSignal(SelfRun3Engine.State state) {
        String signal = state.successorTransition().optString("signal");
        return "USER_ACTION_RESUME".equals(signal) ? signal : "AUTO_NEXT_TURN";
    }

    static JSONArray routingProblems(SelfRun3Engine.State state) {
        return SelfRun3Engine.array(state.successorTransition().optJSONArray("problems"));
    }

    static long remainingMs(SelfRun3Engine.State state, long nowElapsed, long nowWall,
                            int currentBootCount) {
        if (!armed(state)) return NO_DEADLINE;
        JSONObject transition = state.successorTransition();
        long timeoutMs = transition.optLong("timeoutMs", -1L);
        long anchorElapsed = transition.optLong("watchdogStartedElapsed", -1L);
        long anchorWall = transition.optLong("watchdogStartedAtWall", -1L);
        int anchorBoot = transition.optInt("watchdogBootCount", -1);

        if (timeoutMs <= 0L) return NO_DEADLINE;
        if (anchorElapsed >= 0L && currentBootCount >= 0 && anchorBoot == currentBootCount
                && nowElapsed >= anchorElapsed) {
            return remaining(timeoutMs, nowElapsed - anchorElapsed);
        }
        if (anchorWall > 0L && nowWall >= anchorWall) {
            return remaining(timeoutMs, nowWall - anchorWall);
        }
        return timeoutMs;
    }

    static void rearm(JSONObject transition, JSONObject payload) {
        SelfRun3Engine.require(transition != null
                        && transition.optBoolean("active")
                        && "VALID".equals(transition.optString("routingState")),
                "active successor transition required");
        long elapsed = payload.optLong("atElapsed", -1L);
        long wall = payload.optLong("atWall", -1L);
        SelfRun3Engine.require(elapsed >= 0L && wall > 0L, "successor recovery clock required");
        SelfRun3Engine.put(transition, "watchdogStartedElapsed", elapsed);
        SelfRun3Engine.put(transition, "watchdogStartedAtWall", wall);
        SelfRun3Engine.put(transition, "watchdogBootCount", payload.optInt("bootCount", -1));
        SelfRun3Engine.put(transition, "recoveryAttempt",
                transition.optInt("recoveryAttempt", 0) + 1);
        SelfRun3Engine.put(transition, "stage", "TIMEOUT_RECOVERY");
    }

    private static long remaining(long timeoutMs, long ageMs) {
        if (ageMs >= timeoutMs) return 0L;
        return timeoutMs - Math.max(0L, ageMs);
    }
}
