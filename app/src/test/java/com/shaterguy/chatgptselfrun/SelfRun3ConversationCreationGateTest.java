package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SelfRun3ConversationCreationGateTest {
    @Test public void dispatchedTurnWithoutCanonicalConversationReentersWebCreationAndCannotPollDrive() {
        SelfRun3Engine.State claimed = claimedState();
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, claimed.stage());
        assertEquals(SelfRun3Engine.Action.PREPARE_WEB, SelfRun3Engine.nextAction(claimed));
        assertTrue(SelfRun3Engine.waitingExecutions(claimed).isEmpty());
    }

    @Test public void canonicalConversationMakesDispatchEligibleForNormalWaitAndDriveResultObservation() {
        SelfRun3Engine.State claimed = claimedState();
        SelfRun3Engine.State bound = resource(claimed, "conversationUrl", "https://chatgpt.com/c/conv-123");
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, bound.stage());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(bound));
        List<SelfRun3Engine.State> waiting = SelfRun3Engine.waitingExecutions(bound);
        assertEquals(1, waiting.size());
        assertEquals(bound.turnId(), waiting.get(0).turnId());
    }

    @Test public void confirmedConversationThenStartedPreservesExistingWaitingContract() {
        SelfRun3Engine.State bound = resource(claimedState(), "conversationUrl", "https://chatgpt.com/c/conv-456");
        JSONObject started = request(bound);
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.put(started, "atElapsed", 100L);
        SelfRun3Engine.put(started, "atWall", 200L);
        SelfRun3Engine.put(started, "bootCount", 1);
        SelfRun3Engine.State waiting = reduce(bound, "started", SelfRun3Engine.Kind.STARTED, started);
        assertEquals(SelfRun3Engine.Stage.WAITING, waiting.stage());
        assertEquals("https://chatgpt.com/c/conv-456", waiting.resource("conversationUrl"));
        assertTrue(waiting.flag("dispatchObserved"));
        assertTrue(waiting.flag("accepted"));
    }

    private static SelfRun3Engine.State claimedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "resultdoc");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "hello");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = reduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject();
        SelfRun3Engine.put(claim, "at", 1L);
        return reduce(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static JSONObject request(SelfRun3Engine.State state) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "requestId", state.requestId());
        return payload;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return reduce(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State state, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }
}
