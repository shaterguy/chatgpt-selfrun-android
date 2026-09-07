package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** An observed effect must not be confused with permission to repeat that effect. */
public final class SelfRun3EarlyObservationTest {
    @Test public void observedPostBeforeClaimWaitsAndLateClaimCannotResubmit() {
        SelfRun3Engine.State ready = ready();
        SelfRun3Engine.State started = apply(ready, SelfRun3Engine.Kind.STARTED, request(ready));
        assertTrue(started.flag("sendClaimed"));
        assertTrue(started.flag("dispatchObserved"));
        assertEquals(SelfRun3Engine.Stage.WAITING, started.stage());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(started));
        assertSame(started, apply(started, SelfRun3Engine.Kind.STARTED, request(started)));
        assertSame(started, apply(started, SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject()));
        JSONObject unsent = request(started); SelfRun3Engine.put(unsent, "status", "SEND_DISABLED");
        assertSame(started, apply(started, SelfRun3Engine.Kind.UNSENT, unsent));
    }

    @Test public void staleIdentityOrUnpreparedTaskCannotAdoptPost() {
        SelfRun3Engine.State ready = ready();
        JSONObject stale = request(ready); SelfRun3Engine.put(stale, "requestId", "old-request");
        assertSame(ready, apply(ready, SelfRun3Engine.Kind.STARTED, stale));
        SelfRun3Engine.Event wrongTurn = new SelfRun3Engine.Event("late", SelfRun3Engine.Kind.STARTED,
                ready.taskId(), "task:turn:0", request(ready));
        assertSame(ready, SelfRun3Engine.reduce(ready, wrongTurn));
        SelfRun3Engine.State setup = initial();
        assertSame(setup, apply(setup, SelfRun3Engine.Kind.STARTED, request(setup)));
        SelfRun3Engine.State stopped = apply(ready, SelfRun3Engine.Kind.STOP, new JSONObject());
        assertSame(stopped, apply(stopped, SelfRun3Engine.Kind.STARTED, request(stopped)));
    }

    @Test public void pauseIsPreservedWhenLateObservedPostArrives() {
        SelfRun3Engine.State ready = ready();
        JSONObject reason = new JSONObject(); SelfRun3Engine.put(reason, "reason", "USER_PAUSE");
        SelfRun3Engine.State paused = apply(ready, SelfRun3Engine.Kind.PAUSE, reason);
        SelfRun3Engine.State observed = apply(paused, SelfRun3Engine.Kind.STARTED, request(paused));
        assertEquals(SelfRun3Engine.Stage.PAUSED, observed.stage());
        assertEquals("WAITING", observed.text("resumeStage"));
        assertTrue(observed.flag("dispatchObserved"));
        SelfRun3Engine.State resumed = apply(observed, SelfRun3Engine.Kind.RESUME, new JSONObject());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void resultOrUnobservedCompletionCannotInventSubmission() {
        SelfRun3Engine.State ready = ready();
        JSONObject ended = request(ready); SelfRun3Engine.put(ended, "source", "message_stream_complete");
        assertSame(ready, apply(ready, SelfRun3Engine.Kind.ENDED, ended));
        JSONObject result = new JSONObject(); SelfRun3Engine.put(result, "text", "{}");
        assertSame(ready, apply(ready, SelfRun3Engine.Kind.RESULT, result));
        assertFalse(ready.flag("sendClaimed"));
    }

    @Test public void earlyObservationWiringIsPresentInActualRuntime() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of("src/main/java/com/shaterguy/chatgptselfrun");
        if (!java.nio.file.Files.isDirectory(source)) source = java.nio.file.Path.of("app").resolve(source);
        String web = read(source.resolve("SelfRun3WebAdapter.java"));
        String bridge = read(source.resolve("TurnProtocolLogBridge.java"));
        String coordinator = read(source.resolve("SelfRun3Coordinator.java"));
        assertTrue(web.contains("evaluate(observeBeforeAndAfter(state, script)"));
        assertTrue(web.contains("if (consumeObservation(result) || !preparing) return"));
        assertTrue(bridge.contains("SelfRun3WebAdapter.protocolEvent(view, item)"));
        assertTrue(bridge.indexOf("SelfRun3WebAdapter.protocolEvent(view, item)")
                < bridge.indexOf("else if (!turnToken.equals(store.turnProtocolToken()))"));
        assertTrue(coordinator.contains("SelfRun3Engine.Kind.STARTED"));
        assertTrue(coordinator.contains("SelfRun3Engine.Kind.ENDED"));
    }

    private static String read(java.nio.file.Path file) throws java.io.IOException {
        return new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static SelfRun3Engine.State initial() {
        JSONObject c = new JSONObject(); SelfRun3Engine.put(c, "mode", "CHAT");
        return SelfRun3Engine.create("task", "task:turn:1", c);
    }
    private static SelfRun3Engine.State ready() {
        SelfRun3Engine.State s = initial();
        s = resource(s, "folderId", "folder");
        s = resource(s, "requirementDocumentId", "requirements");
        s = apply(s, SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = resource(s, "resultDocumentId", "result");
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "prompt", "test prompt");
        return apply(s, SelfRun3Engine.Kind.TURN_READY, p);
    }
    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", key); SelfRun3Engine.put(p, "value", value);
        return apply(s, SelfRun3Engine.Kind.RESOURCE, p);
    }
    private static JSONObject request(SelfRun3Engine.State s) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "requestId", s.requestId()); return p;
    }
    private static SelfRun3Engine.State apply(SelfRun3Engine.State s, SelfRun3Engine.Kind k, JSONObject p) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("test-event", k, s.taskId(), s.turnId(), p));
    }
}
