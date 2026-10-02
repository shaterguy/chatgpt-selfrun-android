package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class SelfRunDualSubmissionTest {
    @Test public void onDeviceUsesTheSameVercelCompletionWaitAsServer() {
        assertTrue(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, false));
        assertTrue(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.SERVER, false));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, true));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.SERVER, true));
    }

    @Test public void readyEventPersistsSubmissionChoiceBeforeAnySend() throws Exception {
        SelfRun3Engine.State ready = ready("ON_DEVICE");
        assertEquals("ON_DEVICE", ready.text("submissionMode"));
        assertEquals(SelfRun3Engine.Stage.READY, ready.stage());
        SelfRun3Engine.State restored = new SelfRun3Engine.State(new JSONObject(ready.json().toString()));
        assertEquals("ON_DEVICE", restored.text("submissionMode"));
        JSONObject claim = new JSONObject();
        SelfRun3Engine.put(claim, "at", 1L);
        SelfRun3Engine.State sending = event(restored, SelfRun3Engine.Kind.CLAIM_SEND, claim);
        assertEquals("ON_DEVICE", sending.text("submissionMode"));
        assertSame(sending, event(sending, SelfRun3Engine.Kind.CLAIM_SEND, claim));
    }

    @Test public void completionAndSettingsAreSeparatedFromSubmissionTransport() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("new SelfRunSubmissionAdapter(service, this)"));
        assertFalse(coordinator.contains("runtimeSettings.workMode() != SelfRun3RuntimeSettings.WorkMode.SERVER"));
        assertFalse(coordinator.contains("runtimeSettings.workMode() == SelfRun3RuntimeSettings.WorkMode.SERVER"));
    }

    @Test public void bothSubmissionModesHaveExplicitSettingsDescriptions() throws Exception {
        String settings = source("SelfRunLogMenuActivity.java");
        assertTrue(settings.contains("휴대폰 WebView"));
        assertTrue(settings.contains("Vercel"));
        assertTrue(settings.contains("다음"));
    }

    static SelfRun3Engine.State ready(String mode) {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State s = SelfRun3Engine.create("dual-task", "dual-task:turn:1", config);
        s = pin(s, "folderId", "folder");
        s = pin(s, "requirementDocumentId", "requirement");
        s = event(s, SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = pin(s, "resultDocumentId", "result");
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "prompt", "fixture prompt");
        SelfRun3Engine.put(payload, "submissionMode", mode);
        return event(s, SelfRun3Engine.Kind.TURN_READY, payload);
    }

    static SelfRun3Engine.State pin(SelfRun3Engine.State s, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return event(s, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    static SelfRun3Engine.State event(SelfRun3Engine.State s, SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event(
                kind.name() + s.turnId(), kind, s.taskId(), s.turnId(), payload));
    }

    private static String source(String name) throws Exception {
        Path p = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
