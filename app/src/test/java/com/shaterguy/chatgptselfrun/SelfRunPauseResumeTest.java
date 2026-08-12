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
    public void preservedPauseStopsOnlySelfRunAutomationAndKeepsWebViewAlive() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void enterPreservedPause(String cause, String status)");
        int nextMethod = text.indexOf("private void updateWakeLockForState", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int queueClear = body.indexOf("handler.removeCallbacksAndMessages(null);");
        int generationAdvance = body.indexOf("generation++;");
        int observerDetach = body.indexOf("detachDomObserver(cause);");
        int wakeRelease = body.indexOf("releaseWakeLock();");
        assertTrue(queueClear >= 0);
        assertTrue(generationAdvance >= 0);
        assertTrue(observerDetach >= 0);
        assertTrue(wakeRelease >= 0);
        assertTrue(queueClear < observerDetach);
        assertTrue(generationAdvance < observerDetach);
        assertTrue(observerDetach < wakeRelease);
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("stopRelay()"));
        assertFalse(body.contains("loadUrl("));
        assertFalse(body.contains("reload("));
        assertFalse(body.contains("onPause()"));
        assertFalse(body.contains("pauseTimers"));
    }

    @Test
    public void preservedResumeReattachesObserverWithoutWebViewLifecycleOrNavigation() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void resumeFromUi()");
        int nextMethod = text.indexOf("private void enterPreservedPause", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        assertTrue(body.contains("boolean preserved = webView != null;"));
        assertTrue(body.contains("if (preserved)"));
        assertTrue(body.contains("ensureDomObserver();"));
        assertTrue(body.contains("scheduleWatchdog();"));
        assertFalse(body.contains("scheduleStep("));
        assertFalse(body.contains("requestDomEvaluation("));
        assertFalse(body.contains("webView.onResume()"));
        assertFalse(body.contains("loadUrl("));
        assertFalse(body.contains("reload("));
        assertFalse(body.contains("cleanupWebView()"));
    }

    @Test
    public void observerReadyDrivesTheFirstPostResumeDomEvaluation() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int observer = text.indexOf("private void ensureDomObserver()");
        int nextMethod = text.indexOf("private static Uri chatGptOrigin", observer);
        assertTrue(observer >= 0 && nextMethod > observer);
        String body = text.substring(observer, nextMethod);
        int ready = body.indexOf("if (data.startsWith(\"ready|\"))");
        int evaluation = body.indexOf("requestDomEvaluation(0L, \"observer_ready\");", ready);
        assertTrue(ready >= 0);
        assertTrue(evaluation > ready);
    }

    @Test
    public void staleEvaluateGuardRunsBeforeSharedInFlightMutation() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void evaluate(String phase, String script)");
        int nextMethod = text.indexOf("private static boolean isWaitingStatus", method);
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
