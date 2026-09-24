package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** V3 completion releases the browser host instead of retaining V2 post-completion cache state. */
public class CompletedRunCacheCleanupPolicyTest {
    @Test public void terminalDoneClosesWebHostAndReleasesWakeLock() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String commit = between(coordinator, "private void commitTurn", "private void scheduleNetworkRetry");
        assertTrue(commit.contains("if (after.stage() == SelfRun3Engine.Stage.DONE)"));
        assertTrue(commit.contains("web.close()"));
        assertTrue(commit.contains("releaseWakeLock()"));
        assertTrue(commit.indexOf("web.close()") < commit.indexOf("releaseWakeLock()"));
    }

    @Test public void hostDestroyReleasesAllDisplayAndWebResources() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String destroy = between(host, "void destroy()", "}\n}");
        assertTrue(destroy.contains("webView.destroy()"));
        assertTrue(destroy.contains("presentation.dismiss()"));
        assertTrue(destroy.contains("virtualDisplay.release()"));
        assertTrue(destroy.contains("imageReader.setOnImageAvailableListener(null, null)"));
        assertTrue(destroy.contains("drainHandler.removeCallbacksAndMessages(null)"));
        assertTrue(destroy.contains("imageReader.close()"));
        assertTrue(destroy.contains("drainThread.quitSafely()"));
        assertTrue(destroy.contains("if (destroyed) return"));
        assertTrue(destroy.contains("destroyed = true"));
    }

    @Test public void confirmedDispatchDisposesHostAndWaitDoesNotRecreateBrowser() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String web = source("SelfRun3WebAdapter.java");
        String callback = between(coordinator, "private void recordCallback", "private void pause");
        String wait = between(coordinator, "case WAIT, READ_RESULT, CHECK_RECEIPT ->", "case COMMIT ->");
        assertTrue(callback.contains("after.execution(turn)"));
        assertTrue(callback.contains("web.disposeForConfirmedResultWait(persisted)"));
        assertTrue(web.contains("disposeHost();"));
        assertTrue(wait.contains("web.detach()"));
        assertFalse(wait.contains("web.prepare("));
        assertFalse(wait.contains("ensureWeb("));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }
    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
