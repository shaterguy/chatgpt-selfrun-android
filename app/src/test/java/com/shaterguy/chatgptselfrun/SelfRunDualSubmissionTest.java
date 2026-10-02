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

    @Test public void legacyRequestsRetainServerTransportAfterUpgrade() {
        JSONObject raw = ready("SERVER").json();
        raw.remove("submissionMode");
        assertEquals(SelfRun3RuntimeSettings.WorkMode.SERVER,
                SelfRunSubmissionAdapter.mode(new SelfRun3Engine.State(raw)));
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE,
                SelfRunSubmissionAdapter.mode(ready("ON_DEVICE")));
    }

    @Test public void stoppedUnsubmittedRequestChoosesCurrentModeWhenPreparedAgain() {
        SelfRun3Engine.State first = ready("ON_DEVICE");
        SelfRun3Engine.State stopped = event(first, SelfRun3Engine.Kind.STOP, new JSONObject());
        SelfRun3Engine.State resumed = event(stopped, SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject());
        assertEquals("", resumed.text("submissionMode"));
        assertEquals(SelfRun3Engine.Stage.PREPARING, resumed.stage());
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "prompt", "next fixture");
        SelfRun3Engine.put(payload, "submissionMode", "SERVER");
        SelfRun3Engine.State second = event(resumed, SelfRun3Engine.Kind.TURN_READY, payload);
        assertEquals(SelfRun3RuntimeSettings.WorkMode.SERVER, SelfRunSubmissionAdapter.mode(second));
        assertNotEquals(first.requestId(), second.requestId());
    }

    @Test public void stoppedAcceptedRequestKeepsItsModeAndNeverResubmits() {
        SelfRun3Engine.State first = ready("ON_DEVICE");
        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", first.requestId());
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.State waiting = event(first, SelfRun3Engine.Kind.STARTED, started);
        SelfRun3Engine.State stopped = event(waiting, SelfRun3Engine.Kind.STOP, new JSONObject());
        SelfRun3Engine.State resumed = event(stopped, SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject());
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, SelfRunSubmissionAdapter.mode(resumed));
        assertEquals(first.requestId(), resumed.requestId());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void modeChangeDoesNotCancelCompletionDeliveryOrActiveSubmission() throws Exception {
        String c = source("SelfRun3Coordinator.java");
        String change = c.substring(c.indexOf("private void onWorkModeChanged()"),
                c.indexOf("private enum DriveStep"));
        assertFalse(change.contains("clearAll"));
        assertFalse(change.contains("serverRegisteredTurns.clear"));
        assertFalse(change.contains("web.quiesce"));
        assertTrue(change.contains("scheduleNext(0L)"));
    }

    @Test public void phoneTransportRetainsV3OneShotDispatchAndDisposalContract() throws Exception {
        String adapter = source("SelfRunSubmissionAdapter.java");
        assertTrue(adapter.contains("onDevice.prepare(state)"));
        assertTrue(adapter.contains("onDevice.submit(state)"));
        assertTrue(adapter.contains("server.prepare(state)"));
        assertTrue(adapter.contains("server.submit(state)"));
        assertTrue(adapter.contains("onDevice.disposeForConfirmedResultWait(state)"));
        String dispatch = source("SelfRun3DispatchScript.java");
        assertFalse(dispatch.contains("response.clone"));
        assertFalse(dispatch.contains("getReader"));
        assertFalse(dispatch.contains("MutationObserver"));
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("conversationCaptured = true"));
        assertTrue(web.contains("listener.onConversation"));
        assertTrue(web.contains("disposeHost();"));
    }

    @Test public void uncertainOnDeviceSendNeverSelectsASecondWebSubmission() {
        SelfRun3Engine.State sending = event(ready("ON_DEVICE"),
                SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(sending));
    }

    @Test public void uncertainOnDeviceSendStillReceivesSharedCompletionSignals() {
        SelfRun3Engine.State sending = event(ready("ON_DEVICE"),
                SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
        assertEquals(1, SelfRun3Engine.waitingExecutions(sending).size());
    }

    @Test public void cancelledRegistryRefreshCannotRevivePhoneSubmission() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("registryGeneration"));
        assertTrue(web.contains("expectedRegistryGeneration != registryGeneration"));
    }

    @Test public void stopIntentImmediatelyFencesLocalOperations() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("stopRequested = true;"));
        assertTrue(coordinator.contains("!stopRequested"));
    }

    @Test public void outgoingPostEvidenceSurvivesRestartBeforeConversationUrl() throws Exception {
        SelfRun3Engine.State claimed = event(ready("ON_DEVICE"), SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
        JSONObject observed = new JSONObject();
        SelfRun3Engine.put(observed, "requestId", claimed.requestId());
        SelfRun3Engine.State sent = event(claimed, SelfRun3Engine.Kind.DISPATCH_OBSERVED, observed);
        SelfRun3Engine.State restored = new SelfRun3Engine.State(new JSONObject(sent.json().toString()));
        assertTrue(restored.flag("dispatchObserved"));
        assertFalse(restored.flag("accepted"));
        assertEquals("", restored.resource("conversationUrl"));
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(restored));
        SelfRun3Engine.State stopped = event(restored, SelfRun3Engine.Kind.STOP, new JSONObject());
        SelfRun3Engine.State resumed = event(stopped, SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject());
        assertEquals(restored.requestId(), resumed.requestId());
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void serverRecoveryStillPreparesItsExistingDispatchWithoutUrl() {
        SelfRun3Engine.State claimed = event(ready("SERVER"), SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
        assertEquals(SelfRun3Engine.Action.PREPARE_WEB, SelfRun3Engine.nextAction(claimed));
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
