package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun3ResultWatchdogTest {
    @Test public void thresholdIsExactAndCanonicalPostAnchorNeverMoves() {
        SelfRun3Engine.State s = waiting(1_000L, 7);
        long anchor = s.time("canonicalPostConfirmedElapsed");
        assertEquals(120L, SelfRun3RuntimeSettings.DEFAULT_RESULT_REPAIR_MINUTES);
        assertEquals(7_200_000L, SelfRun3ResultWatchdog.STALE_AFTER_MS);
        assertEquals(1_000L, anchor);
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s,
                anchor + SelfRun3ResultWatchdog.STALE_AFTER_MS - 1L, 7));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(s,
                anchor + SelfRun3ResultWatchdog.STALE_AFTER_MS, 7));

        JSONObject duplicate = startedPayload(s, 99_000L, 100_000L, 7);
        s = event(s, s.turnId(), s.turnId() + ":duplicate-start", SelfRun3Engine.Kind.STARTED, duplicate);
        assertEquals(anchor, s.time("canonicalPostConfirmedElapsed"));
    }

    @Test public void customRepairThresholdUsesExistingAnchorAndEligibilityEvidence() {
        SelfRun3Engine.State s = waiting(10_000L, 8);
        long custom = 60_000L;
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, 69_999L, 8, custom));
        assertTrue(SelfRun3ResultWatchdog.shouldRepair(s, 70_000L, 8, custom));
        assertEquals(10_000L, s.time("canonicalPostConfirmedElapsed"));
        assertTrue(s.flag("resultBodyMutationObserved"));
    }

    @Test public void mutationLatchSurvivesRestartAndBodyRestoration() {
        SelfRun3Engine.State s = waiting(10L, 3);
        assertTrue(s.flag("resultBodyMutationObserved"));
        String seed = s.text("resultSeedFingerprint");
        s = new SelfRun3Engine.State(s.json());
        assertTrue(s.flag("resultBodyMutationObserved"));
        assertEquals(seed, s.text("resultSeedFingerprint"));

        JSONObject repeated = new JSONObject();
        put(repeated, "documentId", s.resource("resultDocumentId"));
        put(repeated, "fingerprint", SelfRun3ResultWatchdog.fingerprint("different-again"));
        SelfRun3Engine.State same = event(s, s.turnId(), s.turnId() + ":mutation-repeat",
                SelfRun3Engine.Kind.RESULT_MUTATED, repeated);
        assertEquals(s.text("resultBodyMutationFingerprint"), same.text("resultBodyMutationFingerprint"));
    }

    @Test public void legacyOrClockUncertaintyCannotTriggerWatchdog() {
        SelfRun3Engine.State s = waiting(5_000L, 12);
        long due = 5_000L + SelfRun3ResultWatchdog.STALE_AFTER_MS;
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, due, 13));
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(s, 4_999L, 12));

        JSONObject raw = s.json();
        raw.remove("canonicalPostConfirmedElapsed");
        SelfRun3Engine.State missingAnchor = new SelfRun3Engine.State(raw);
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(missingAnchor, due, 12));

        raw = s.json(); raw.remove("resultBodyMutationObserved");
        assertFalse(SelfRun3ResultWatchdog.shouldRepair(new SelfRun3Engine.State(raw), due, 12));
    }

    @Test public void pauseAndStopBlockRepairEligibility() {
        SelfRun3Engine.State s = waiting(100L, 2);
        long due = 100L + SelfRun3ResultWatchdog.STALE_AFTER_MS;
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
        SelfRun3Engine.State s = waiting(1L, 1);
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
        SelfRun3Engine.State s = waiting(20L, 4);
        JSONObject resultPayload = new JSONObject(); put(resultPayload, "text", committedResult(s).toString());
        s = event(s, s.turnId(), s.turnId() + ":result-test", SelfRun3Engine.Kind.RESULT, resultPayload);
        s = event(s, s.turnId(), s.turnId() + ":commit-test", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, s.stage());
        for (String key : new String[]{"canonicalPostConfirmedElapsed","canonicalPostConfirmedAtWall","canonicalPostBootCount",
                "resultSeedDocumentId","resultSeedFingerprint","resultBodyMutationObserved","resultBodyMutationFingerprint","resultBodyMutationObservedAtWall"}) {
            assertFalse("must reset " + key, s.json().has(key));
        }
    }

    @Test public void fingerprintOnlyNormalizesOneProviderTerminalNewline() {
        String seed = "{\"committed\":false}";
        assertEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + "\n"));
        assertEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + "\r\n"));
        assertNotEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(seed + " "));
        assertNotEquals(SelfRun3ResultWatchdog.fingerprint(seed), SelfRun3ResultWatchdog.fingerprint(""));
    }

    private static SelfRun3Engine.State waiting(long submittedElapsed, int bootCount) {
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
        JSONObject mutation = new JSONObject();
        put(mutation, "documentId", s.resource("resultDocumentId"));
        put(mutation, "fingerprint", SelfRun3ResultWatchdog.fingerprint("partial malformed body"));
        put(mutation, "atWall", 60_000L);
        return event(s, s.turnId(), s.turnId() + ":result-body-mutated", SelfRun3Engine.Kind.RESULT_MUTATED, mutation);
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

    private static void put(JSONObject object, String key, Object value) { SelfRun3Engine.put(object, key, value); }
}
