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

    @Test public void buttonClassifierSeparatesStopSendDisabledAndUnknown() {
        String js = SelfRunContinuationDom.buttonState(URL);
        assertTrue(js.contains("SEND_ENABLED"));
        assertTrue(js.contains("STOP"));
        assertTrue(js.contains("SEND_DISABLED"));
        assertTrue(js.contains("UNKNOWN"));
        assertTrue(js.contains("continuation composer unavailable"));
        assertTrue(js.contains("button,[role=\"button\"]"));
        assertTrue(js.contains("generating|streaming|responding"));
        assertTrue(js.indexOf("const stop=controls.find(isStop)") < js.indexOf("const send=calibrated"));
        assertTrue(js.contains("isSend(calibrated)"));
        assertFalse(js.contains("!isStop(calibrated))?calibrated"));
        assertTrue(js.contains("if(stop)return{state:'STOP'"));
    }

    @Test public void continuationAlwaysClearsThenReinputsAndRequiresExactReadback() {
        String js = SelfRunContinuationDom.prepareDriveTurn(URL, PROMPT, "marker-1");
        assertTrue(js.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(js.contains("c0.state!=='SEND_DISABLED'"));
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
    }

    @Test public void clickIsOnlyStartOfVerificationNotSubmissionConfirmation() {
        String js = SelfRunContinuationDom.clickPreparedDriveTurn(URL, PROMPT, "marker-2");
        assertTrue(js.contains("baselineUserCount=userMessageCount()"));
        assertTrue(js.contains("state:'clicked'"));
        assertTrue(js.contains("c.send.click()"));
        assertTrue(js.contains("CONTINUE_CLICKED"));
        assertFalse(js.contains("SUBMISSION_CONFIRMED"));
    }

    @Test public void postClickVerifierUsesRequiredSuccessAndFailureProofs() {
        String js = SelfRunContinuationDom.verifyDriveTurnSubmission(URL, PROMPT, "marker-3", 2500L);
        assertTrue(js.contains("c.state==='STOP'"));
        assertTrue(js.contains("proof:'STOP'"));
        assertTrue(js.contains("users>baseline&&isEmpty"));
        assertTrue(js.contains("proof:'USER_MESSAGE'"));
        assertTrue(js.contains("elapsed>=2500"));
        assertTrue(js.contains("c.state==='SEND_ENABLED'&&stillSame&&users<=baseline"));
        assertTrue(js.contains("state:'failed'"));
        assertTrue(js.contains("SUBMISSION_FAILED"));
        assertTrue(js.contains("SUBMISSION_PENDING"));
    }

    @Test public void bootstrapUsesTheSameVerifiedSendStopAndFullRetryProtocol() {
        String project = "https://chatgpt.com/g/g-p-test";
        String prepare = SelfRunContinuationDom.prepareBootstrap(project, PROMPT, "bootstrap-marker");
        String click = SelfRunContinuationDom.clickPreparedBootstrap(project, PROMPT, "bootstrap-marker");
        String verify = SelfRunContinuationDom.verifyBootstrapSubmission(project, PROMPT, "bootstrap-marker", 2500L);
        assertTrue(prepare.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(prepare.contains("c0.state!=='SEND_DISABLED'"));
        assertTrue(prepare.contains("state:'clearing'"));
        assertTrue(prepare.contains("state:'inputting'"));
        assertTrue(prepare.contains("exact bootstrap prepared"));
        assertTrue(click.contains("BOOTSTRAP_CLICKED"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
        assertTrue(verify.contains("c.state==='STOP'"));
        assertTrue(verify.contains("users>baseline&&isEmpty"));
        assertTrue(verify.contains("elapsed>=2500"));
        assertTrue(verify.contains("state:'failed'"));
    }

    @Test public void serviceDoesNotUseCommandReceivedAsContinuationAck() throws Exception {
        String service = source("SelfRunService.java");
        String continuationSubmitted = section(service, "private void continuationSubmitted", "private String commandPrompt");
        assertFalse(continuationSubmitted.contains("command_received_ack"));
        assertTrue(continuationSubmitted.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(continuationSubmitted.contains("markCommandSubmitted"));
        assertFalse(continuationSubmitted.contains("SUBMISSION_RETRY_MS"));
        assertTrue(service.contains("CONTINUATION_VERIFY_INTERVAL_MS = 250L"));
        assertTrue(service.contains("CONTINUATION_FAILURE_MS = 2_500L"));
        assertTrue(service.contains("store.phaseStartedAt()"));
    }

    @Test public void workFlowRechecksSendAfterReasoningBeforeComposerMutation() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        assertTrue(handler.contains("PHASE_APPLY_REASONING.equals(phase)&&\"READY\".equals(status)"));
        assertTrue(handler.contains("reasoning_ready_for_send_recheck"));
        assertTrue(handler.contains("PHASE_SEND_CONTINUE"));
        String js = SelfRunContinuationDom.prepareDriveTurn(URL, PROMPT, "work-marker");
        assertTrue(js.contains("c0.state!=='SEND_ENABLED'"));
        assertTrue(js.contains("c0.state!=='SEND_DISABLED'"));
        assertTrue(js.indexOf("c0.state!=='SEND_ENABLED'") < js.indexOf("clearComposer()"));
    }

    @Test public void idleDisabledSendAdvancesOutOfInternalWaitWithoutClickingStop() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        assertTrue(handler.contains("SelfRunContinuationDom.SEND_ENABLED.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)"));
        assertTrue(handler.contains("send_control_ready"));
        assertFalse(handler.contains("SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)"));
        String js = SelfRunContinuationDom.prepareDriveTurn(URL, PROMPT, "idle-marker");
        assertTrue(js.contains("c0.state!=='SEND_ENABLED'&&c0.state!=='SEND_DISABLED'"));
        assertTrue(js.contains("const c=controlState();if(c.state!=='SEND_ENABLED')"));
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
