package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3SuccessorTransitionAndroidTest {
    private Context context;
    private SelfRun3Ledger ledger;

    @Before public void before() {
        context = ApplicationProvider.getApplicationContext();
        clearLedger();
        ledger = new SelfRun3Ledger(context);
    }

    @After public void after() {
        if (ledger != null) ledger.close();
        clearLedger();
    }

    @Test public void lostProgressionPersistsAcrossLedgerRestartAndTimeoutRedrivesSameSuccessor() {
        SelfRun3Engine.State claimed = claimedState();
        ledger.ensure(claimed);

        JSONObject resultPayload = resultPayload(claimed, 1_000L, 10_000L, 5, 90_000L);
        SelfRun3Engine.State accepted = ledger.apply(new SelfRun3Engine.Event(
                claimed.turnId() + ":result:runtime",
                SelfRun3Engine.Kind.RESULT, claimed.taskId(), claimed.turnId(), resultPayload));
        assertEquals(SelfRun3Engine.Stage.RECONCILING, accepted.stage());
        assertTrue(SelfRun3SuccessorTransitionPolicy.armed(accepted));
        String predecessorResultDocument = accepted.resource("resultDocumentId");

        ledger.close();
        ledger = new SelfRun3Ledger(context);
        SelfRun3Engine.State restored = ledger.load(claimed.taskId());
        assertEquals(SelfRun3Engine.Stage.RECONCILING, restored.stage());
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(restored));
        assertEquals(predecessorResultDocument, restored.resource("resultDocumentId"));
        assertEquals(0L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                restored, 91_000L, 100_000L, 5));

        JSONObject timeout = new JSONObject();
        SelfRun3Engine.put(timeout, "atElapsed", 91_000L);
        SelfRun3Engine.put(timeout, "atWall", 100_000L);
        SelfRun3Engine.put(timeout, "bootCount", 5);
        SelfRun3Engine.State rearmed = ledger.apply(new SelfRun3Engine.Event(
                claimed.turnId() + ":successor-timeout:1",
                SelfRun3Engine.Kind.SUCCESSOR_TIMEOUT,
                claimed.taskId(), claimed.turnId(), timeout));
        assertEquals(SelfRun3Engine.Stage.RECONCILING, rearmed.stage());
        assertEquals(1, rearmed.successorTransition().optInt("recoveryAttempt"));
        assertEquals(predecessorResultDocument, rearmed.resource("resultDocumentId"));

        String commitEvent = claimed.turnId() + ":commit:CONTINUE";
        SelfRun3Engine.State successor = ledger.apply(new SelfRun3Engine.Event(
                commitEvent, SelfRun3Engine.Kind.COMMIT,
                claimed.taskId(), claimed.turnId(), new JSONObject()));
        assertEquals(2, successor.turn());
        assertEquals(SelfRun3Engine.Stage.PREPARING, successor.stage());
        assertTrue(SelfRun3SuccessorTransitionPolicy.armed(successor));
        String successorTurn = successor.turnId();
        String successorRequest = successor.requestId();

        SelfRun3Engine.State duplicateCommit = ledger.apply(new SelfRun3Engine.Event(
                commitEvent, SelfRun3Engine.Kind.COMMIT,
                claimed.taskId(), claimed.turnId(), new JSONObject()));
        assertEquals(successorTurn, duplicateCommit.turnId());
        assertEquals(successorRequest, duplicateCommit.requestId());
        assertEquals(2, duplicateCommit.turn());
        assertEquals(predecessorResultDocument,
                duplicateCommit.successorTransition().optString("predecessorResultDocumentId"));
    }

    @Test public void successorProgressAndCanonicalConfirmationSurviveRealLedgerWrites() {
        SelfRun3Engine.State source = claimedState();
        ledger.ensure(source);
        SelfRun3Engine.State accepted = ledger.apply(new SelfRun3Engine.Event(
                source.turnId() + ":result:runtime",
                SelfRun3Engine.Kind.RESULT, source.taskId(), source.turnId(),
                resultPayload(source, 1_000L, 10_000L, 5, 90_000L)));
        SelfRun3Engine.State successor = ledger.apply(new SelfRun3Engine.Event(
                source.turnId() + ":commit:CONTINUE",
                SelfRun3Engine.Kind.COMMIT, source.taskId(), source.turnId(), new JSONObject()));

        successor = resource(successor, "resultDocumentId", "successor-result");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "successor");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        successor = apply(successor, successor.requestId() + ":turn-ready",
                SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 20_000L);
        successor = apply(successor, successor.requestId() + ":claim",
                SelfRun3Engine.Kind.CLAIM_SEND, claim);
        String request = successor.requestId();
        successor = resource(successor, "conversationUrl", "https://chatgpt.com/c/android-successor");

        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", request);
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.put(started, "atElapsed", 30_000L);
        SelfRun3Engine.put(started, "atWall", 40_000L);
        SelfRun3Engine.put(started, "bootCount", 5);
        SelfRun3Engine.State confirmed = apply(successor, request + ":started",
                SelfRun3Engine.Kind.STARTED, started);

        assertEquals(request, confirmed.requestId());
        assertEquals(SelfRun3Engine.Stage.WAITING, confirmed.stage());
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(confirmed));
        assertEquals("CANONICAL_CONFIRMED", confirmed.successorTransition().optString("stage"));

        ledger.close();
        ledger = new SelfRun3Ledger(context);
        SelfRun3Engine.State restored = ledger.load(source.taskId());
        assertEquals(request, restored.requestId());
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(restored));
        assertEquals("CANONICAL_CONFIRMED", restored.successorTransition().optString("stage"));
    }

    private SelfRun3Engine.State apply(SelfRun3Engine.State state, String eventId,
                                       SelfRun3Engine.Kind kind, JSONObject payload) {
        return ledger.apply(new SelfRun3Engine.Event(
                eventId, kind, state.taskId(), state.turnId(), payload));
    }

    private SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return apply(state, state.turnId() + ":resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static JSONObject resultPayload(SelfRun3Engine.State state,
                                            long elapsed, long wall, int boot, long timeout) {
        JSONObject result = identity(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "phase_completed", "WORK_PARTIAL");
        SelfRun3Engine.put(result, "next_phase", "WORK");
        JSONObject profile = profile();
        JSONObject next = new JSONObject();
        SelfRun3Engine.put(next, "type", "SERIAL");
        SelfRun3Engine.put(next, "objective", "runtime successor regression");
        SelfRun3Engine.put(next, "profile", profile);
        SelfRun3Engine.put(result, "next_execution", next);
        SelfRun3Engine.put(result, "next_profile", profile);

        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.put(payload, "acceptedAtElapsed", elapsed);
        SelfRun3Engine.put(payload, "acceptedAtWall", wall);
        SelfRun3Engine.put(payload, "acceptedBootCount", boot);
        SelfRun3Engine.put(payload, "successorTimeoutMs", timeout);
        return payload;
    }

    private static JSONObject identity(SelfRun3Engine.State state) {
        JSONObject result = new JSONObject();
        SelfRun3Engine.put(result, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(result, "task_id", state.taskId());
        SelfRun3Engine.put(result, "turn_id", state.turnId());
        SelfRun3Engine.put(result, "turn", state.turn());
        SelfRun3Engine.put(result, "document_id", state.resource("resultDocumentId"));
        SelfRun3Engine.put(result, "event_id", state.turnId() + ":result");
        return result;
    }

    private static JSONObject profile() {
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(profile, "reasoning", "medium");
        return profile;
    }

    private static SelfRun3Engine.State claimedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = pureResource(state, "folderId", "folder");
        state = pureResource(state, "requirementDocumentId", "requirement");
        state = pureReduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = pureResource(state, "resultDocumentId", "resultdoc");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "hello");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = pureReduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 100L);
        return pureReduce(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static SelfRun3Engine.State pureResource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return pureReduce(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State pureReduce(SelfRun3Engine.State state, String eventId,
                                                   SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(eventId, kind, state.taskId(), state.turnId(), payload));
    }

    private void clearLedger() {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db" + suffix);
            if (file.exists()) assertTrue(file.delete());
        }
    }
}
