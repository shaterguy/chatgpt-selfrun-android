package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRun3PreparationRecoveryWiringTest {
    @Test public void preparationTimeoutHasIndependentDeadlineEvenWhilePageIsLoading() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("handler.postDelayed(() -> {"));
        assertTrue(web.contains("WEB_PREPARATION_WATCHDOG"));
        assertTrue(web.contains("fail(\"WEB_PREPARATION_TIMEOUT\")"));
        assertTrue(web.contains("if (loading) return;"));
        assertTrue(web.indexOf("SystemClock.elapsedRealtime() - prepareStarted >= prepareTimeoutMs")
                < web.indexOf("if (loading) return;"));
    }

    @Test public void sameRequestPreparationRetryRecreatesPhysicalWebViewAndReentersCanonicalRoute() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("boolean newRequest = state == null || !state.requestId().equals(s.requestId());"));
        assertTrue(web.contains("} else if (restartPreparation) {"));
        assertTrue(web.contains("strategy=recreate-webview"));
        assertTrue(web.contains("disposeHost();"));
        assertTrue(web.contains("web.loadUrl(SelfRun3ProjectDirectoryNavigation.entryUrl(target));"));
        assertTrue(web.contains("status=reentry"));
        assertTrue(web.contains("status=recovered"));
    }

    @Test public void replacementWebViewRejectsLateCallbacksFromDestroyedHost() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("if (view != web || closed) return;\n                generation++;"));
        assertTrue(web.contains("response.cancel();\n                if (view == web && !closed) fail(\"TLS_REJECTED\");"));
    }

    @Test public void automaticRetryIsLimitedToReadyUnsentPreparationState() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("state.stage() == SelfRun3Engine.Stage.READY && !state.flag(\"sendClaimed\")"));
        assertTrue(coordinator.contains("V3_WEB_PREPARATION_RETRY"));
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("state = claimed;\n        preparing = false;"));
    }

    @Test public void provenUnsentRetryKeepsLogicalIdentityAndCanClaimAgain() {
        SelfRun3Engine.State ready = readyState();
        String task = ready.taskId();
        String turn = ready.turnId();
        String request = ready.requestId();
        String result = ready.resource("resultDocumentId");

        JSONObject firstClaim = new JSONObject();
        SelfRun3Engine.put(firstClaim, "at", 100L);
        SelfRun3Engine.State claimed = reduce(ready, "claim:1", SelfRun3Engine.Kind.CLAIM_SEND, firstClaim);
        JSONObject unsent = request(claimed);
        SelfRun3Engine.put(unsent, "status", "SEND_DISABLED");
        SelfRun3Engine.State reopened = reduce(claimed, "unsent:1", SelfRun3Engine.Kind.UNSENT, unsent);

        JSONObject secondClaim = new JSONObject();
        SelfRun3Engine.put(secondClaim, "at", 200L);
        SelfRun3Engine.State reclaimed = reduce(reopened, "claim:2", SelfRun3Engine.Kind.CLAIM_SEND, secondClaim);
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, reclaimed.stage());
        assertTrue(reclaimed.flag("sendClaimed"));
        assertEquals(task, reclaimed.taskId());
        assertEquals(turn, reclaimed.turnId());
        assertEquals(request, reclaimed.requestId());
        assertEquals(result, reclaimed.resource("resultDocumentId"));
    }

    @Test public void coordinatorUsesFreshClaimReceiptWithoutChangingRequestId() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("request + \":claim:\" + UUID.randomUUID()"));
        assertFalse(coordinator.contains("event(before, request + \":claim\","));
    }

    private static SelfRun3Engine.State readyState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "xhigh");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "resultdoc");
        JSONObject turnReady = new JSONObject();
        SelfRun3Engine.put(turnReady, "prompt", "hello");
        SelfRun3Engine.put(turnReady, "inputText", "");
        SelfRun3Engine.put(turnReady, "inputRevision", 7L);
        return reduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, turnReady);
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return reduce(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static JSONObject request(SelfRun3Engine.State state) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "requestId", state.requestId());
        return payload;
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State state, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
