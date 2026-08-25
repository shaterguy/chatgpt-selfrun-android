package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRunRendererCallbackFenceTest {
    @Test public void rendererCallbackOwnershipRequiresSameViewAndRun() {
        Object current = new Object();
        Object stale = new Object();
        assertTrue(SelfRunService.ownsRendererCallback(current, current, "SR-CURRENT", "SR-CURRENT"));
        assertFalse(SelfRunService.ownsRendererCallback(stale, current, "SR-CURRENT", "SR-CURRENT"));
        assertFalse(SelfRunService.ownsRendererCallback(current, current, "SR-OLD", "SR-CURRENT"));
        assertFalse(SelfRunService.ownsRendererCallback(null, current, "SR-CURRENT", "SR-CURRENT"));
    }

    @Test public void rendererOriginFencePrecedesEverySharedSideEffect() throws Exception {
        String service = source("SelfRunService.java");
        int callback = service.indexOf("@Override public boolean onRenderProcessGone");
        int guard = service.indexOf("if(!ownsRendererCallback(v,webView,launchedRunId,store.runId()))return true;", callback);
        int transientMark = service.indexOf("markPostDispatchTransient(\"RENDERER_KILLED\")", callback);
        int cleanup = service.indexOf("cleanupWebView();", callback);
        int rollover = service.indexOf("rolloverConversation(SelfRunRolloverPolicy.RENDERER_CRASH)", callback);
        assertTrue(callback >= 0);
        assertTrue(guard > callback);
        assertTrue(transientMark > guard);
        assertTrue(cleanup > guard);
        assertTrue(rollover > cleanup);
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
