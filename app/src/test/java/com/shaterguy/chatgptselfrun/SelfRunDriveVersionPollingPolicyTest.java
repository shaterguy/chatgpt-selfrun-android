package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** V3 waits on exact execution results; response completion is irrelevant. */
public final class SelfRunDriveVersionPollingPolicyTest {
    @Test public void normalWaitingKeepsThirtySecondDefaultButConsumesRuntimeSetting() throws Exception {
        assertEquals(30L, SelfRun3RuntimeSettings.DEFAULT_RESULT_POLL_SECONDS);
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("long resultPollMs = runtimeSettings.resultPollMs()"));
        assertTrue(coordinator.contains("SystemClock.elapsedRealtime() + runtimeSettings.resultPollMs()"));
        assertFalse(coordinator.contains("SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS"));
    }

    @Test public void waitingStateIsEligibleForIndependentResultPolling() {
        SelfRun3Engine.State waiting = waitingState();
        assertEquals(SelfRun3Engine.Stage.WAITING, waiting.stage());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(waiting));
        assertEquals(waiting.turnId(), SelfRun3Engine.waitingExecutions(waiting).get(0).turnId());
        assertEquals("conversation_url", waiting.text("acceptanceProof"));
    }

    @Test public void responseCompletionCannotChangeExecutionState() {
        SelfRun3Engine.State waiting = waitingState();
        JSONObject end = new JSONObject();
        SelfRun3Engine.put(end, "requestId", waiting.requestId());
        SelfRun3Engine.put(end, "source", "message_stream_complete");
        SelfRun3Engine.State ended = SelfRun3Engine.reduce(waiting,
                new SelfRun3Engine.Event("ended", SelfRun3Engine.Kind.ENDED,
                        waiting.taskId(), waiting.turnId(), end));
        assertEquals(SelfRun3Engine.Stage.WAITING, ended.stage());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(ended));
    }

    private static SelfRun3Engine.State waitingState() {
        JSONObject config = new JSONObject(); SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.State s = SelfRun3Engine.create("task", "task:turn:1", config);
        s = resource(s, "folderId", "folder");
        s = resource(s, "requirementDocumentId", "requirements");
        s = SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("setup", SelfRun3Engine.Kind.SETUP_DONE,
                s.taskId(), s.turnId(), new JSONObject()));
        s = resource(s, "resultDocumentId", "result");
        JSONObject ready = new JSONObject(); SelfRun3Engine.put(ready, "prompt", "prompt");
        s = SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("ready", SelfRun3Engine.Kind.TURN_READY,
                s.taskId(), s.turnId(), ready));
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 1L);
        s = SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("claim", SelfRun3Engine.Kind.CLAIM_SEND,
                s.taskId(), s.turnId(), claim));
        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", s.requestId());
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.put(started, "atElapsed", 10L);
        SelfRun3Engine.put(started, "atWall", 10L);
        SelfRun3Engine.put(started, "bootCount", 1);
        s = SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("started", SelfRun3Engine.Kind.STARTED,
                s.taskId(), s.turnId(), started));
        s = resource(s, "conversationUrl", "https://chatgpt.com/c/result-polling");
        JSONObject accepted = new JSONObject();
        SelfRun3Engine.put(accepted, "requestId", s.requestId());
        SelfRun3Engine.put(accepted, "proof", "conversation_url");
        SelfRun3Engine.put(accepted, "atElapsed", 20L);
        SelfRun3Engine.put(accepted, "atWall", 20L);
        SelfRun3Engine.put(accepted, "bootCount", 1);
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("accepted", SelfRun3Engine.Kind.ACCEPTED,
                s.taskId(), s.turnId(), accepted));
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", key); SelfRun3Engine.put(p, "value", value);
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, s.taskId(), s.turnId(), p));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
