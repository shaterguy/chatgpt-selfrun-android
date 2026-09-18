package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class SelfRun3WebViewWaitLifecycleTest {
    @Test public void persistedCanonicalStartIsTheOnlyFullDisposeGate() throws Exception {
        String web=source("SelfRun3WebAdapter.java"), coordinator=source("SelfRun3Coordinator.java");
        String gate=between(web,"boolean disposeForConfirmedResultWait","void close()");
        String callback=between(coordinator,"private void recordCallback","private void pause");
        assertTrue(gate.contains("state.taskId().equals(persisted.taskId())"));
        assertTrue(gate.contains("state.turnId().equals(persisted.turnId())"));
        assertTrue(gate.contains("state.requestId().equals(persisted.requestId())"));
        assertTrue(gate.contains("dispatchConfirmed"));
        assertTrue(gate.contains("conversationCaptured"));
        assertTrue(gate.contains("persisted.flag(\"sendClaimed\")"));
        assertTrue(gate.contains("persisted.flag(\"dispatchObserved\")"));
        assertTrue(gate.contains("persisted.flag(\"accepted\")"));
        assertTrue(gate.contains("persisted.resource(\"conversationUrl\")"));
        assertTrue(gate.contains("Stage.WAITING"));
        assertTrue(gate.contains("Stage.RECONCILING"));
        assertTrue(gate.indexOf("quiesce();") < gate.indexOf("disposeHost();"));
        assertTrue(callback.contains("SelfRun3Engine.State persisted = after.execution(turn)"));
        assertTrue(callback.contains("kind == SelfRun3Engine.Kind.STARTED"));
        assertTrue(callback.contains("web.disposeForConfirmedResultWait(persisted)"));
        assertTrue(callback.contains("if (!disposedForWait) web.detach()"));
    }

    @Test public void serverAndOnDeviceWaitPathsNeverRequireBrowserRecreation() throws Exception {
        String coordinator=source("SelfRun3Coordinator.java"), engine=source("SelfRun3Engine.java");
        String wait=between(coordinator,"case WAIT, READ_RESULT, CHECK_RECEIPT ->","case COMMIT ->");
        String server=between(coordinator,"private boolean startServerWatchRegistration","private void requestFcmAndRegister");
        assertTrue(wait.contains("web.detach()"));
        assertFalse(wait.contains("web.prepare("));
        assertFalse(wait.contains("ensureWeb("));
        assertFalse(server.contains("web.prepare("));
        assertFalse(server.contains("ensureWeb("));
        assertTrue(engine.contains("case WAITING, WAITING_USER_INTERVENTION -> Action.WAIT"));
        assertTrue(engine.contains("case DISPATCHING -> s.resource(\"conversationUrl\").isEmpty() ? Action.PREPARE_WEB : Action.WAIT"));
        assertTrue(coordinator.contains("drive.observeResult(token, current)"));
    }

    @Test public void nextTurnPreparationCanCreateFreshHostWithoutDeletingPersistentSession() throws Exception {
        String web=source("SelfRun3WebAdapter.java"), host=source("HeadlessWebViewHost.java");
        String prepare=between(web,"void prepare(","private void startPreparationTimer");
        String ensure=between(web,"private void ensureWeb()","private void advance()");
        String destroy=between(host,"void destroy()","}\n}");
        assertTrue(prepare.contains("ensureWeb();"));
        assertTrue(ensure.contains("host = HeadlessWebViewHost.create(context)"));
        assertTrue(destroy.contains("webView.destroy()"));
        assertFalse(destroy.contains("CookieManager"));
        assertFalse(destroy.contains("WebStorage"));
        assertFalse(destroy.contains("clearCache(true)"));
        assertTrue(destroy.contains("if (destroyed) return"));
        assertTrue(destroy.contains("drainHandler.removeCallbacksAndMessages(null)"));
    }

    private static String between(String source,String start,String end){int a=source.indexOf(start),b=source.indexOf(end,Math.max(0,a));return a>=0&&b>a?source.substring(a,b):"";}
    private static String source(String name) throws Exception {
        Path p=Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(p)) p=Path.of("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
