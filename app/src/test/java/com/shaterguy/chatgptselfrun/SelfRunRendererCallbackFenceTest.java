package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** V3 callbacks are fenced by task/turn/request identities and adapter-owned WebView instance. */
public final class SelfRunRendererCallbackFenceTest {
    @Test public void staleRequestCallbackCannotMutateCurrentLedgerState() {
        JSONObject config = new JSONObject(); SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "result");
        JSONObject ready = new JSONObject(); SelfRun3Engine.put(ready, "prompt", "p");
        state = reduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 1L);
        state = reduce(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
        JSONObject stale = new JSONObject(); SelfRun3Engine.put(stale, "requestId", "old-request");
        SelfRun3Engine.State after = reduce(state, "stale", SelfRun3Engine.Kind.STARTED, stale);
        assertSame(state, after);
        assertFalse(after.flag("dispatchObserved"));
    }

    @Test public void rendererDeathDisposesOnlyCurrentAdapterHostThenReportsFailure() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String callback = between(web, "onRenderProcessGone", "private void advance");
        assertTrue(callback.contains("if (view == web)"));
        assertTrue(callback.contains("disposeHost()"));
        assertTrue(callback.contains("fail(\"RENDERER_GONE\")"));
        assertTrue(callback.indexOf("disposeHost()") < callback.indexOf("fail(\"RENDERER_GONE\")"));
    }

    @Test public void protocolBridgeRejectsEventsFromDifferentViewOrRequestIdentity() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String protocol = between(web, "static boolean protocolEvent", "void prepare(");
        assertTrue(protocol.contains("a.web != view"));
        assertTrue(protocol.contains("!s.taskId().equals(event.optString(\"runId\"))"));
        assertTrue(protocol.contains("!s.turnId().equals(event.optString(\"turnId\"))"));
        assertTrue(protocol.contains("!s.requestId().equals(event.optString(\"turnToken\"))"));
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", key); SelfRun3Engine.put(p, "value", value);
        return reduce(s, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, p);
    }
    private static SelfRun3Engine.State reduce(SelfRun3Engine.State s, String id, SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event(id, kind, s.taskId(), s.turnId(), payload));
    }
    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }
    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
