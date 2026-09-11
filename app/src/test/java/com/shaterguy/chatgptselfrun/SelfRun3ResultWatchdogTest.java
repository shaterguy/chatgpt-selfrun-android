package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun3ResultWatchdogTest {
    @Test public void thresholdIsExactFromLastIncompleteBodyMutation() {
        SelfRun3Engine.State s = waiting(1_000L, 2_000L, 7);
        assertEquals(10L, SelfRun3RuntimeSettings.DEFAULT_RESULT_REPAIR_MINUTES);
        assertEquals(600_000L, SelfRun3ResultWatchdog.STALE_AFTER_MS);
        assertEquals(1_000L, s.time("canonicalPostConfirmedElapsed"));
        assertEquals(2_000L, s.time("resultBodyMutationObservedElapsed"));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, 601_999L, 7));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(s, 602_000L, 7));
    }

    @Test public void customRepairThresholdUsesMutationAnchorNotCanonicalPost() {
        SelfRun3Engine.State s = waiting(10_000L, 20_000L, 8);
        long custom = 60_000L;
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, 79_999L, 8, custom));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(s, 80_000L, 8, custom));
        assertEquals(10_000L, s.time("canonicalPostConfirmedElapsed"));
        assertEquals(20_000L, s.time("resultBodyMutationObservedElapsed"));
    }

    @Test public void sameBodyDoesNotResetButEveryActualBodyChangeDoes() {
        SelfRun3Engine.State s = waiting(10L, 100L, 3);
        String first = s.text("resultBodyMutationFingerprint");

        SelfRun3Engine.State same = event(s, s.turnId(), s.turnId() + ":mutation-same",
                SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(s, first, 500L, 3));
        assertEquals(100L, same.time("resultBodyMutationObservedElapsed"));

        String second = SelfRun3ResultWatchdog.fingerprint("partial body B");
        SelfRun3Engine.State changed = event(same, same.turnId(), same.turnId() + ":mutation-b",
                SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(same, second, 500L, 3));
        assertEquals(second, changed.text("resultBodyMutationFingerprint"));
        assertEquals(500L, changed.time("resultBodyMutationObservedElapsed"));

        SelfRun3Engine.State changedBack = event(changed, changed.turnId(), changed.turnId() + ":mutation-a-again",
                SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(changed, first, 900L, 3));
        assertEquals(first, changedBack.text("resultBodyMutationFingerprint"));
        assertEquals(900L, changedBack.time("resultBodyMutationObservedElapsed"));
    }

    @Test public void returningToSeedClearsWaitAndLaterMutationStartsFresh() {
        SelfRun3Engine.State s = waiting(10L, 100L, 3);
        JSONObject seed = new JSONObject();
        put(seed, "documentId", s.resource("resultDocumentId"));
        put(seed, "fingerprint", s.text("resultSeedFingerprint"));
        SelfRun3Engine.State cleared = event(s, s.turnId(), s.turnId() + ":seed-return",
                SelfRun3Engine.Kind.RESULT_MUTATED, seed);
        assertFalse(cleared.flag("resultBodyMutationObserved"));
        assertEquals("", cleared.text("resultBodyMutationFingerprint"));
        assertFalse(cleared.json().has("resultBodyMutationObservedElapsed"));
        assertFalse(cleared.json().has("resultBodyMutationBootCount"));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(cleared, 1_000_000L, 3));

        String next = SelfRun3ResultWatchdog.fingerprint("new partial body");
        SelfRun3Engine.State restarted = event(cleared, cleared.turnId(), cleared.turnId() + ":new-mutation",
                SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(cleared, next, 2_000L, 3));
        assertTrue(restarted.flag("resultBodyMutationObserved"));
        assertEquals(2_000L, restarted.time("resultBodyMutationObservedElapsed"));
    }

    @Test public void processRestartPreservesClockAndRebootRequiresOneRebaseline() {
        SelfRun3Engine.State s = waiting(5_000L, 6_000L, 12);
        SelfRun3Engine.State restarted = new SelfRun3Engine.State(s.json());
        assertEquals(6_000L, restarted.time("resultBodyMutationObservedElapsed"));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(restarted, 606_000L, 12));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(restarted, 606_000L, 13));

        SelfRun3Engine.State rebooted = event(restarted, restarted.turnId(), restarted.turnId() + ":boot-rebaseline",
                SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(restarted, restarted.text("resultBodyMutationFingerprint"), 20_000L, 13));
        assertEquals(13, rebooted.number("resultBodyMutationBootCount"));
        assertEquals(20_000L, rebooted.time("resultBodyMutationObservedElapsed"));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(rebooted, 619_999L, 13));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(rebooted, 620_000L, 13));
    }

    @Test public void missingOrUncertainMutationClockCannotTriggerWatchdog() {
        SelfRun3Engine.State s = waiting(5_000L, 6_000L, 12);
        long due = 606_000L;
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, due, 13));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, 5_999L, 12));

        JSONObject raw = s.json();
        raw.remove("resultBodyMutationObservedElapsed");
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(new SelfRun3Engine.State(raw), due, 12));
        raw = s.json(); raw.remove("resultBodyMutationBootCount");
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(new SelfRun3Engine.State(raw), due, 12));
        raw = s.json(); raw.remove("resultBodyMutationObserved");
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(new SelfRun3Engine.State(raw), due, 12));
    }

    @Test public void pauseAndStopBlockRepairEligibility() {
        SelfRun3Engine.State s = waiting(100L, 200L, 2);
        long due = 200L + SelfRun3ResultWatchdog.STALE_AFTER_MS;
        JSONObject reason = new JSONObject(); put(reason, "reason", "USER_PAUSE");
        SelfRun3Engine.State paused = event(s, s.turnId(), s.taskId() + ":pause-test",
                SelfRun3Engine.Kind.PAUSE, reason);
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(paused, due, 2));
        SelfRun3Engine.State resumed = event(paused, paused.turnId(), paused.taskId() + ":resume-test",
                SelfRun3Engine.Kind.RESUME, new JSONObject());
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(resumed, due, 2));
        SelfRun3Engine.State stopped = event(resumed, resumed.turnId(), resumed.taskId() + ":stop-test",
                SelfRun3Engine.Kind.STOP, new JSONObject());
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(stopped, due, 2));
    }

    @Test public void supersededExecutionRejectsLateProgressButKeepsHistoryUrlEnrichment() {
        SelfRun3Engine.State s = waiting(1L, 2L, 1);
        String oldTurn = s.turnId();
        JSONObject repair = new JSONObject(); put(repair, "safeToRepair", true);
        SelfRun3Engine.State replacement = event(s, oldTurn, oldTurn + ":repair-stale-result",
                SelfRun3Engine.Kind.REPAIR, repair);
        SelfRun3Engine.State old = replacement.execution(oldTurn);
        assertNotNull(old); assertTrue(old.flag("superseded")); assertFalse(old.hasResult());

        JSONObject resultPayload = new JSONObject(); put(resultPayload, "text", committedResult(old).toString());
        SelfRun3Engine.State ignored = event(replacement, oldTurn, oldTurn + ":late-result",
                SelfRun3Engine.Kind.RESULT, resultPayload);
        assertFalse(ignored.execution(oldTurn).hasResult());
        assertEquals(replacement.turnId(), ignored.turnId());

        JSONObject resource = new JSONObject();
        put(resource, "key", "conversationUrl"); put(resource, "value", "https://chatgpt.com/c/abc-123");
        SelfRun3Engine.State enriched = event(ignored, oldTurn, oldTurn + ":conversation:abc-123",
                SelfRun3Engine.Kind.RESOURCE, resource);
        assertEquals("https://chatgpt.com/c/abc-123", enriched.execution(oldTurn).resource("conversationUrl"));
    }

    @Test public void nextExecutionResetsAllWatchdogEvidence() {
        SelfRun3Engine.State s = waiting(20L, 30L, 4);
        JSONObject resultPayload = new JSONObject(); put(resultPayload, "text", committedResult(s).toString());
        s = event(s, s.turnId(), s.turnId() + ":result-test", SelfRun3Engine.Kind.RESULT, resultPayload);
        s = event(s, s.turnId(), s.turnId() + ":commit-test", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, s.stage());
        for (String key : new String[]{"canonicalPostConfirmedElapsed","canonicalPostConfirmedAtWall","canonicalPostBootCount",
                "resultSeedDocumentId","resultSeedFingerprint","resultBodyMutationObserved","resultBodyMutationFingerprint",
                "resultBodyMutationObservedElapsed","resultBodyMutationBootCount","resultBodyMutationObservedAtWall"}) {
            assertFalse("must reset " + key, s.json().has(key));
        }
    }

    @Test public void driveAdapterFreshReadsAgainBeforeRepairingStaleSnapshot() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        assertTrue(drive.contains("stage=STALE_CONFIRM"));
        assertTrue(drive.contains("ResultObservation finalObservation = readObservation(token, current)"));
        assertTrue(drive.contains("recordResultBodyObservation(current, finalObservation)"));
        assertTrue(drive.contains("runtimeSettings.resultRepairMs()"));
    }

    @Test public void fingerprintOnlyNormalizesOneProviderTerminalNewline() {
        String seed = "{\"committed\":false}";
        assertEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + "\n"));
        assertEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + "\r\n"));
        assertNotEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + " "));
        assertNotEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(""));
    }

    private static SelfRun3Engine.State waiting(long submittedElapsed, long mutationElapsed, int bootCount) {
        JSONObject config = new JSONObject(); put(config, "mode", "CHAT"); put(config, "reasoning", "medium");
        SelfRun3Engine.State s = SelfRun3Engine.create("watchdog-task", "watchdog-task:turn:1", config);
        s = resource(s, "folderId", "folder"); s = resource(s, "requirementDocumentId", "requirements");
        s = event(s, s.turnId(), s.turnId() + ":setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = resource(s, "resultDocumentId", "result-doc");
        JSONObject baseline = new JSONObject();
        put(baseline, "documentId", "result-doc");
        put(baseline, "fingerprint", SelfRun3ResultWatchdog.fingerprint(SelfRun3Engine.emptyResult(s).toString()));
        s = event(s, s.turnId(), s.turnId() + ":result-baseline", SelfRun3Engine.Kind.RESULT_BASELINE, baseline);
        JSONObject ready = new JSONObject(); put(ready, "prompt", "bootstrap"); put(ready, "inputRevision", 0L);
        s = event(s, s.turnId(), s.turnId() + ":ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); put(claim, "at", 123L);
        s = event(s, s.turnId(), s.turnId() + ":claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
        s = event(s, s.turnId(), s.turnId() + ":started", SelfRun3Engine.Kind.STARTED,
                startedPayload(s, submittedElapsed, 50_000L, bootCount));
        return event(s, s.turnId(), s.turnId() + ":result-body-mutated", SelfRun3Engine.Kind.RESULT_MUTATED,
                mutationPayload(s, SelfRun3ResultWatchdog.fingerprint("partial body A"), mutationElapsed, bootCount));
    }

    private static JSONObject mutationPayload(SelfRun3Engine.State s, String fingerprint, long elapsed, int bootCount) {
        JSONObject p = new JSONObject();
        put(p, "documentId", s.resource("resultDocumentId"));
        put(p, "fingerprint", fingerprint);
        put(p, "atElapsed", elapsed);
        put(p, "atWall", 60_000L + elapsed);
        put(p, "bootCount", bootCount);
        return p;
    }

    private static JSONObject startedPayload(SelfRun3Engine.State s, long elapsed, long wall, int bootCount) {
        JSONObject p = new JSONObject();
        put(p, "requestId", s.requestId()); put(p, "source", "canonical_post"); put(p, "protocolStage", "turn_request");
        put(p, "atElapsed", elapsed); put(p, "atWall", wall); put(p, "bootCount", bootCount);
        return p;
    }

    private static JSONObject committedResult(SelfRun3Engine.State s) {
        JSONObject r = SelfRun3Engine.emptyResult(s);
        put(r, "committed", true); put(r, "status", "CONTINUE");
        put(r, "phase_completed", "PLAN"); put(r, "next_phase", "WORK");
        JSONObject h = new JSONObject(); put(h, "objective", "continue"); put(h, "next_action", "work");
        for (String key : new String[]{"completed","remaining","evidence","constraints","requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"})
            put(h, key, new org.json.JSONArray());
        put(r, "handoff", h);
        return r;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject(); put(p, "key", key); put(p, "value", value);
        return event(s, s.turnId(), s.turnId() + ":resource:" + key, SelfRun3Engine.Kind.RESOURCE, p);
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
