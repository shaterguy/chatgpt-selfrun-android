package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Regression for SR-20260914-184841-ZY2OLQ: a committed candidate must win before mutation bookkeeping. */
public final class SelfRun3CommittedResultPriorityTest {
    @Test public void legacyFallbackWithoutClockReproducesObservedIllegalStateAfterRecoveredCandidate() {
        SelfRun3Engine.State state = dispatchedState();
        String valid = committedContinueResult(state).toString();
        String raw = valid + "}\n";
        String candidate = SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(raw, state);

        assertEquals(valid, candidate);
        assertNotNull(SelfRun3Engine.parseResult(candidate, state));

        JSONObject legacyMutation = new JSONObject();
        put(legacyMutation, "documentId", state.resource("resultDocumentId"));
        put(legacyMutation, "fingerprint", SelfRun3ResultWatchdog.fingerprint(raw));
        put(legacyMutation, "atWall", 60_000L);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                event(state, state.turnId(), state.turnId() + ":legacy-result-body-mutated",
                        SelfRun3Engine.Kind.RESULT_MUTATED, legacyMutation));
        assertTrue(error.getMessage().contains("result mutation clock required"));
    }

    @Test public void coordinatorChecksCommittedCandidateBeforeMutationOrRepairFallback() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        int observe = coordinator.indexOf("drive.observeResult(token, current)");
        int parsed = coordinator.indexOf("SelfRun3Engine.parseResult(observation.candidateBody, current)", observe);
        int watchdog = coordinator.indexOf("SelfRun3ResultWatchdog.shouldRepair(current,", observe);
        int rawFingerprint = coordinator.indexOf("SelfRun3ResultWatchdog.fingerprint(observation.rawBody)", observe);

        assertTrue(observe >= 0);
        assertTrue(parsed > observe);
        assertTrue(watchdog > parsed);
        assertTrue(rawFingerprint < 0 || rawFingerprint > parsed);
        assertFalse(coordinator.contains("repairResult(state, \"READ_RESULT_FAILURE_\""));
    }

    @Test public void recoveredCommittedCandidateTransitionsExactlyOnceToCommitAndNextTurn() {
        SelfRun3Engine.State state = dispatchedState();
        String valid = committedContinueResult(state).toString();
        String recovered = SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(valid + "}", state);
        assertNotNull(SelfRun3Engine.parseResult(recovered, state));

        JSONObject resultPayload = new JSONObject();
        put(resultPayload, "text", recovered);
        SelfRun3Engine.State reconciled = event(state, state.turnId(), state.turnId() + ":result:fixture",
                SelfRun3Engine.Kind.RESULT, resultPayload);
        assertEquals(SelfRun3Engine.Stage.RECONCILING, reconciled.stage());
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(reconciled));
        assertFalse(reconciled.flag("resultBodyMutationObserved"));

        SelfRun3Engine.State duplicate = event(reconciled, reconciled.turnId(), reconciled.turnId() + ":result:fixture-repeat",
                SelfRun3Engine.Kind.RESULT, resultPayload);
        assertEquals(reconciled.json().toString(), duplicate.json().toString());

        SelfRun3Engine.State next = event(duplicate, duplicate.turnId(), duplicate.turnId() + ":commit:fixture",
                SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(2, next.turn());
        assertEquals("WORK", next.text("phase"));
        assertEquals("AUTO_NEXT_TURN", next.text("signalType"));
        assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
    }

    private static SelfRun3Engine.State dispatchedState() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "taskMode", "CHAT");
        put(config, "reasoning", "xhigh");
        SelfRun3Engine.State s = SelfRun3Engine.create("repair-loop-task", "repair-loop-task:turn:1", config);
        s = resource(s, "folderId", "folder");
        s = resource(s, "requirementDocumentId", "requirements");
        s = event(s, s.turnId(), s.turnId() + ":setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = resource(s, "resultDocumentId", "result-doc");

        JSONObject baseline = new JSONObject();
        put(baseline, "documentId", s.resource("resultDocumentId"));
        put(baseline, "fingerprint", SelfRun3ResultWatchdog.fingerprint(SelfRun3Engine.emptyResult(s).toString()));
        s = event(s, s.turnId(), s.turnId() + ":baseline", SelfRun3Engine.Kind.RESULT_BASELINE, baseline);

        JSONObject ready = new JSONObject();
        put(ready, "prompt", "fixture");
        put(ready, "inputRevision", 0L);
        s = event(s, s.turnId(), s.turnId() + ":ready", SelfRun3Engine.Kind.TURN_READY, ready);

        JSONObject claim = new JSONObject();
        put(claim, "at", 1L);
        return event(s, s.turnId(), s.turnId() + ":claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static JSONObject committedContinueResult(SelfRun3Engine.State s) {
        JSONObject r = SelfRun3Engine.emptyResult(s);
        put(r, "committed", true);
        put(r, "status", "CONTINUE");
        put(r, "phase_completed", "WORK");
        put(r, "next_phase", "WORK");
        put(r, "next_input", "");
        JSONObject nextProfile = new JSONObject();
        put(nextProfile, "mode", "CHAT"); put(nextProfile, "model", "gpt-5-6-thinking"); put(nextProfile, "reasoning", "medium");
        put(r, "next_profile", nextProfile);
        return r;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject payload = new JSONObject();
        put(payload, "key", key);
        put(payload, "value", value);
        return event(s, s.turnId(), s.turnId() + ":resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State root, String turn, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(root,
                new SelfRun3Engine.Event(id, kind, root.taskId(), turn, payload));
    }

    private static String source(String file) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
