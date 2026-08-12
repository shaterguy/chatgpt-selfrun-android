package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeTest {
    @Test
    public void userActionPausePreservesCurrentWebView() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.USER_ACTION));
    }

    @Test
    public void protocolPauseUsesTheSamePreservedWebViewPolicy() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.PAUSE));
    }

    @Test
    public void terminalSignalsDoNotUseResumablePausePolicy() {
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.DONE));
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.NEXT));
    }

    @Test
    public void preservedPauseInvalidatesPrePauseAutomationBeforeWebViewPause() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void enterPreservedPause(String cause, String status)");
        int nextMethod = text.indexOf("private void pauseCurrentWebView", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int queueClear = body.indexOf("handler.removeCallbacksAndMessages(null);");
        int generationAdvance = body.indexOf("generation++;");
        int webViewPause = body.indexOf("pauseCurrentWebView(cause);");
        assertTrue(queueClear >= 0);
        assertTrue(generationAdvance >= 0);
        assertTrue(webViewPause >= 0);
        assertTrue(queueClear < webViewPause);
        assertTrue(generationAdvance < webViewPause);
    }

    @Test
    public void staleEvaluateGuardRunsBeforeSharedInFlightMutation() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void evaluate(String phase, String script)");
        int nextMethod = text.indexOf("private void handleResult", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int callback = body.indexOf("active.evaluateJavascript(script, raw -> {");
        int staleGuard = body.indexOf("if (active != webView || activeGeneration != generation) return;", callback);
        int clearInFlight = body.indexOf("evaluationInFlight = false;", callback);
        int canRunGuard = body.indexOf("if (!canRun()) return;", callback);
        assertTrue(callback >= 0);
        assertTrue(staleGuard > callback);
        assertTrue(clearInFlight > staleGuard);
        assertTrue(canRunGuard > clearInFlight);
    }
}
