package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class HeadlessDisplayOutputWiringTest {
    @Test public void virtualDisplayDrainAndDetachArePreserved() throws Exception {
        String host=source("HeadlessWebViewHost.java");
        assertTrue(host.contains("ImageReader.newInstance("));
        assertTrue(host.contains("reader.acquireLatestImage()"));
        String detach=host.substring(host.indexOf("boolean detachOutput()"),host.indexOf("boolean attachOutput()"));
        assertTrue(detach.contains("virtualDisplay.setSurface(null)"));
        assertFalse(detach.contains("onPause()"));
        assertFalse(detach.contains("pauseTimers()"));
        assertFalse(detach.contains("stopLoading()"));
        assertFalse(detach.contains("destroy()"));
        assertTrue(host.contains("virtualDisplay.setSurface(surface)"));
    }
    @Test public void protocolWaitDetachesWithoutRecoveryAttachOrDomPolling() throws Exception {
        String service=source("SelfRunService.java");
        String wait=service.substring(service.indexOf("private void runWebStep()"),service.indexOf("private String ensureTurnProtocolToken"));
        assertTrue(wait.contains("detachDisplayOutput(\"protocol_wait\")"));
        assertFalse(wait.contains("evaluateJavascript"));
        assertFalse(wait.contains("attachDisplayOutput"));
        assertTrue(service.contains("detachDisplayOutput(\"submission_confirmed\")"));
        assertFalse(service.contains("recoverDetachedObserverOutput"));
        assertFalse(service.contains("TURN_OBSERVER_HEALTHCHECK_MS"));
    }
    @Test public void rendererGoneDuringProtocolWaitCancelsNoStartWithoutRollover() throws Exception {
        String service=source("SelfRunService.java");
        int start=service.indexOf("@Override public boolean onRenderProcessGone");
        int end=service.indexOf("webView.loadUrl(target)",start);
        assertTrue(start>=0&&end>start);
        String renderer=service.substring(start,end);
        assertTrue(renderer.contains("boolean waiting=SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())"));
        assertTrue(renderer.contains("if(waiting){\n                    handler.removeCallbacks(turnStartGuardRunnable);\n                    resetPostDispatchNoStartState();"));
        int waitingStart=renderer.indexOf("if(waiting){",renderer.indexOf("cleanupWebView()"));
        int nonWaiting=renderer.indexOf("}else if(SelfRunRolloverPolicy.rolloverRenderer",waitingStart);
        assertTrue(waitingStart>=0&&nonWaiting>waitingStart);
        assertFalse(renderer.substring(waitingStart,nonWaiting).contains("rolloverConversation"));
        assertTrue(renderer.substring(waitingStart,nonWaiting).contains("ensureWebView"));
        String detach=service.substring(service.indexOf("private boolean detachDisplayOutput"),
                service.indexOf("private boolean attachDisplayOutput"));
        assertFalse(detach.contains("turnStartGuardRunnable"));
        assertFalse(detach.contains("resetPostDispatchNoStartState"));
        assertFalse(detach.contains("RENDERER_GONE"));
        assertTrue(service.contains("restoreWaitingProtocol"));
        assertTrue(service.contains("ChatGptTurnProtocolScript.restoreTurn"));
    }

    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return Files.readString(path,StandardCharsets.UTF_8);
    }
}
