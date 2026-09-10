package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun3TurnStallPolicyTest {
    @Test public void exact125MinuteDefaultExposesOneShotRemainingDelay() {
        SelfRun3Engine.State state = waiting(1_000L, 50_000L, 7);
        long due = 1_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS;
        assertEquals(125L, SelfRun3RuntimeSettings.DEFAULT_STALL_ALERT_MINUTES);
        assertEquals(7_500_000L, SelfRun3TurnStallPolicy.ALERT_AFTER_MS);
        assertFalse(state.flag("resultBodyMutationObserved"));
        assertEquals(SelfRun3TurnStallPolicy.ALERT_AFTER_MS,
                SelfRun3TurnStallPolicy.remainingDelayMs(state, 1_000L, 50_000L, 7));
        assertEquals(1L, SelfRun3TurnStallPolicy.remainingDelayMs(state, due - 1L,
                50_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS - 1L, 7));
        assertEquals(0L, SelfRun3TurnStallPolicy.remainingDelayMs(state, due,
                50_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS, 7));
        assertFalse(SelfRun3TurnStallPolicy.shouldAlert(state, due - 1L,
                50_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS - 1L, 7));
        assertTrue(SelfRun3TurnStallPolicy.shouldAlert(state, due,
                50_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS, 7));
    }

    @Test public void customThresholdChangesDeadlineWithoutMovingAnchor() {
        SelfRun3Engine.State state = waiting(2_000L, 80_000L, 9);
        long custom = 60_000L;
        assertEquals(custom, SelfRun3TurnStallPolicy.remainingDelayMs(state, 2_000L, 80_000L, 9, custom));
        assertFalse(SelfRun3TurnStallPolicy.shouldAlert(state, 61_999L, 139_999L, 9, custom));
        assertTrue(SelfRun3TurnStallPolicy.shouldAlert(state, 62_000L, 140_000L, 9, custom));
        assertEquals(2_000L, state.time("canonicalPostConfirmedElapsed"));
    }

    @Test public void rebootFallsBackToPersistedWallClockRemainingDelay() {
        SelfRun3Engine.State state = waiting(5_000L, 100_000L, 3);
        assertEquals(1L, SelfRun3TurnStallPolicy.remainingDelayMs(state, 1_000L,
                100_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS - 1L, 4));
        assertEquals(0L, SelfRun3TurnStallPolicy.remainingDelayMs(state, 1_000L,
                100_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS, 4));
        assertFalse(SelfRun3TurnStallPolicy.shouldAlert(state, 1_000L,
                100_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS - 1L, 4));
        assertTrue(SelfRun3TurnStallPolicy.shouldAlert(state, 1_000L,
                100_000L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS, 4));
    }

    @Test public void pauseAndSuccessfulNextTurnInvalidateReservation() {
        SelfRun3Engine.State state = waiting(10L, 20L, 2);
        long dueElapsed = 10L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS;
        long dueWall = 20L + SelfRun3TurnStallPolicy.ALERT_AFTER_MS;

        JSONObject pause = new JSONObject(); put(pause, "reason", "USER_PAUSE");
        SelfRun3Engine.State paused = event(state, state.turnId(), state.taskId() + ":pause",
                SelfRun3Engine.Kind.PAUSE, pause);
        assertEquals(SelfRun3TurnStallPolicy.NO_ALERT_DELAY_MS,
                SelfRun3TurnStallPolicy.remainingDelayMs(paused, dueElapsed, dueWall, 2));
        assertFalse(SelfRun3TurnStallPolicy.shouldAlert(paused, dueElapsed, dueWall, 2));

        JSONObject result = SelfRun3Engine.emptyResult(state);
        put(result, "committed", true); put(result, "status", "CONTINUE"); put(result, "next_phase", "WORK");
        JSONObject payload = new JSONObject(); put(payload, "text", result.toString());
        SelfRun3Engine.State committed = event(state, state.turnId(), state.turnId() + ":result",
                SelfRun3Engine.Kind.RESULT, payload);
        committed = event(committed, committed.turnId(), committed.turnId() + ":commit",
                SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, committed.stage());
        assertEquals(SelfRun3TurnStallPolicy.NO_ALERT_DELAY_MS,
                SelfRun3TurnStallPolicy.remainingDelayMs(committed, dueElapsed, dueWall, 2));
        assertFalse(SelfRun3TurnStallPolicy.shouldAlert(committed, dueElapsed, dueWall, 2));
    }

    @Test public void integrationUsesDynamicOneShotReservationAndPreservesResultPolling() throws Exception {
        String service = source("SelfRunService.java");
        String notifier = source("SelfRun3TurnStallNotifier.java");
        String watchdog = source("SelfRun3ResultWatchdog.java");
        String coordinator = source("SelfRun3Coordinator.java");
        String settings = source("SelfRun3RuntimeSettings.java");

        assertTrue(service.contains("new SelfRun3TurnStallNotifier(this, store)"));
        assertTrue(service.contains("turnStallNotifier.start()"));
        assertTrue(service.contains("turnStallNotifier.close()"));
        assertTrue(notifier.contains("registerOnSharedPreferenceChangeListener"));
        assertTrue(notifier.contains("settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)"));
        assertTrue(notifier.contains("unregisterOnSharedPreferenceChangeListener"));
        assertTrue(notifier.contains("SelfRun3TurnStallPolicy.remainingDelayMs"));
        assertTrue(notifier.contains("runtimeSettings.stallAlertMs()"));
        assertTrue(notifier.contains("main.postDelayed(check, candidate.remainingMs)"));
        assertTrue(notifier.contains("generation == expectedGeneration"));
        assertTrue(notifier.contains("main.removeCallbacks(scheduledCheck)"));
        assertTrue(notifier.contains("NotificationHelper.notifyUser(context, \"다음 턴 전환 지연\""));
        assertFalse(notifier.contains("CHECK_INTERVAL_MS"));
        assertFalse(notifier.contains("scheduleNext()"));
        assertFalse(notifier.contains("postDelayed(poll"));
        assertFalse(notifier.contains("Kind.REPAIR"));
        assertFalse(notifier.contains("ledger.apply("));
        assertTrue(settings.contains("DEFAULT_RESULT_REPAIR_MINUTES = 120L"));
        assertTrue(settings.contains("DEFAULT_RESULT_POLL_SECONDS = 30L"));
        assertTrue(watchdog.contains("DEFAULT_RESULT_REPAIR_MINUTES * 60_000L"));
        assertTrue(coordinator.contains("drive.observeResult(token, current)"));
        assertTrue(coordinator.contains("runtimeSettings.resultPollMs()"));
        assertFalse(coordinator.contains("SelfRun3TurnStall"));
    }

    private static SelfRun3Engine.State waiting(long elapsed, long wall, int bootCount) {
        JSONObject config = new JSONObject(); put(config, "mode", "CHAT"); put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("stall-task", "stall-task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = event(state, state.turnId(), state.turnId() + ":setup",
                SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "result-doc");
        JSONObject ready = new JSONObject(); put(ready, "prompt", "bootstrap"); put(ready, "inputRevision", 0L);
        state = event(state, state.turnId(), state.turnId() + ":ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); put(claim, "at", wall - 1L);
        state = event(state, state.turnId(), state.turnId() + ":claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
        JSONObject started = new JSONObject();
        put(started, "requestId", state.requestId()); put(started, "source", "canonical_post");
        put(started, "protocolStage", "turn_request"); put(started, "atElapsed", elapsed);
        put(started, "atWall", wall); put(started, "bootCount", bootCount);
        return event(state, state.turnId(), state.turnId() + ":started", SelfRun3Engine.Kind.STARTED, started);
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject(); put(payload, "key", key); put(payload, "value", value);
        return event(state, state.turnId(), state.turnId() + ":resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State root, String turn, String id,
                                              SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(root, new SelfRun3Engine.Event(id, kind, root.taskId(), turn, payload));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void put(JSONObject object, String key, Object value) { SelfRun3Engine.put(object, key, value); }
}
