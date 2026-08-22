package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRunContinuationSubmissionVerificationTest {
    private static final String URL = "https://chatgpt.com/g/g-p-test/c/conversation123";
    private static final String PROMPT = "[2026.08.20 | 19:14:40] [SELF_RUN_CONTINUE SR-TEST]";

    @Test public void buttonClassifierSeparatesStopSendComposerIdleAndUnknown() {
        String js = SelfRunContinuationDom.observeTurnCompletion(URL, "SR-TEST", "classifier-token", 5000L, false);
        assertTrue(js.contains("SEND_ENABLED"));
        assertTrue(js.contains("STOP"));
        assertTrue(js.contains("SEND_DISABLED"));
        assertTrue(js.contains("COMPOSER_IDLE"));
        assertTrue(js.contains("UNKNOWN"));
        assertTrue(js.contains("completion composer unavailable"));
        assertTrue(js.contains("button,[role=\"button\"]"));
        assertTrue(js.contains("generating|streaming|responding"));
        assertTrue(js.indexOf("const stop=controls.find(isStop)") < js.indexOf("const send=calibrated"));
        assertTrue(js.contains("isSend(calibrated)"));
        assertTrue(js.contains("speech|voice|mic|microphone|dictation"));
        assertTrue(js.contains("isStop(e)||isVoice(e)||!inComposer(e)"));
        assertTrue(js.indexOf("const isVoice=") < js.indexOf("const isSend="));
        assertTrue(js.contains("composerEditable()"));
        assertTrue(js.contains("if(composerEditable())return{state:'COMPOSER_IDLE'"));
        assertFalse(js.contains("!isStop(calibrated))?calibrated"));
        assertTrue(js.contains("if(stop)return{state:'STOP'"));
    }

    @Test public void continuationAlwaysClearsThenReinputsAndRequiresExactReadback() {
        String js = SelfRunContinuationDom.prepareDriveTurn(URL, PROMPT, "marker-1");
        assertTrue(js.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(js.contains("c0.state!=='SEND_DISABLED'"));
        assertTrue(js.contains("c0.state!=='COMPOSER_IDLE'"));
        assertTrue(js.indexOf("c0.state!=='SEND_ENABLED'") < js.indexOf("clearComposer()"));
        assertTrue(js.contains("state:'clearing'"));
        assertTrue(js.contains("clearComposer()"));
        assertTrue(js.contains("if(!empty())"));
        assertTrue(js.contains("state:'inputting'"));
        assertTrue(js.contains("inputComposer()"));
        assertTrue(js.contains("canonical(raw())===canonical(expected)"));
        assertFalse(js.contains("comparableExpected"));
        assertFalse(js.contains("continuationComparablePrompt"));
        assertTrue(js.contains("state:'prepared'"));
        assertTrue(js.contains("READY_TO_SUBMIT"));
        assertTrue(js.contains("window.__selfRunDriveMarkers"));
    }

    @Test public void clickIsOnlyStartOfVerificationNotSubmissionConfirmation() {
        String js = SelfRunContinuationDom.clickPreparedDriveTurn(URL, PROMPT, "marker-2", "SR-TEST", "token-2", 5000L);
        assertTrue(js.contains("baselineUserCount=userMessageCount()"));
        assertTrue(js.contains("state:'clicked'"));
        assertTrue(js.contains("c.send.click()"));
        assertTrue(js.contains("CONTINUE_CLICKED"));
        assertFalse(js.contains("SUBMISSION_CONFIRMED"));
    }

    @Test public void bootstrapUsesTheSameVerifiedSendStopAndFullRetryProtocol() {
        String project = "https://chatgpt.com/g/g-p-test";
        String prepare = SelfRunContinuationDom.prepareBootstrap(project, PROMPT, "bootstrap-marker");
        String click = SelfRunContinuationDom.clickPreparedBootstrap(project, PROMPT, "bootstrap-marker", "SR-TEST", "bootstrap-token", 5000L);
        assertTrue(prepare.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(prepare.contains("c0.state!=='SEND_DISABLED'"));
        assertTrue(prepare.contains("c0.state!=='COMPOSER_IDLE'"));
        assertTrue(prepare.contains("state:'clearing'"));
        assertTrue(prepare.contains("state:'inputting'"));
        assertTrue(prepare.contains("exact bootstrap prepared"));
        assertTrue(prepare.contains("window.__selfRunDriveMarkers"));
        assertTrue(click.contains("BOOTSTRAP_CLICKED"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
    }

    @Test public void serviceDoesNotUseCommandReceivedAsContinuationAck() throws Exception {
        String service = source("SelfRunService.java");
        String continuationSubmitted = section(service, "private void continuationSubmitted", "private String commandPrompt");
        assertFalse(continuationSubmitted.contains("command_received_ack"));
        assertTrue(continuationSubmitted.contains("store.beginTurnCompletionWait"));
        assertFalse(continuationSubmitted.contains("markCommandSubmitted"));
        assertFalse(continuationSubmitted.contains("SUBMISSION_RETRY_MS"));
        assertTrue(service.contains("CONTINUATION_VERIFY_INTERVAL_MS = 250L"));
        assertFalse(service.contains("CONTINUATION_FAILURE_MS"));
        assertTrue(service.contains("TURN_COMPLETION_STABILITY_MS = 5_000L"));
    }

    @Test public void workFlowRechecksSendAfterReasoningBeforeComposerMutation() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        assertTrue(handler.contains("PHASE_APPLY_REASONING.equals(phase)&&\"READY\".equals(status)"));
        assertTrue(handler.contains("reasoning_ready_for_send"));
        assertTrue(handler.contains("PHASE_SEND_CONTINUE"));
        String js = SelfRunContinuationDom.prepareDriveTurn(URL, PROMPT, "work-marker");
        assertTrue(js.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(js.contains("c0.state!=='SEND_DISABLED'"));
        assertTrue(js.contains("c0.state!=='COMPOSER_IDLE'"));
        assertTrue(js.indexOf("c0.state!=='SEND_ENABLED'") < js.indexOf("clearComposer()"));
    }

    @Test public void observerRequiresStopThenStableIdleAndNeverClicksStop() {
        String js = SelfRunContinuationDom.observeTurnCompletion(
                URL, "SR-TEST", "observer-token", 5000L, false);
        assertTrue(js.contains("new MutationObserver"));
        assertTrue(js.contains("observerStableMs=5000"));
        assertTrue(js.contains("const noteStop="));
        assertTrue(js.contains("state.sawStop=true"));
        assertTrue(js.contains("stopSeenCallback"));
        assertTrue(js.contains("const confirmed=controlState()"));
        assertFalse(js.contains("setInterval"));
        assertFalse(js.contains("c.stop.click"));
    }

    @Test public void normalContinuationDoesNotRestoreCanonicalOnDiagnosticMismatch() throws Exception {
        String service = source("SelfRunService.java");
        String web = section(service, "private void runWebStep", "private void evaluate");
        assertTrue(web.contains("recordContinuationRouteMismatch(webView.getUrl())"));
        assertTrue(web.contains("scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS)"));
        assertFalse(web.contains("restoreCanonical()"));
        assertFalse(web.contains("loadUrl("));
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue(a >= 0 && b > a);
        return text.substring(a, b);
    }
}
