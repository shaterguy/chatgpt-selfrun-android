package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRun3DirectProjectNavigationWiringTest {
    @Test public void newAttemptLoadsStoredProjectUrlDirectlyBeforeBootstrapPipeline() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("web.loadUrl(target);"));
        assertFalse(web.contains("SelfRun3ProjectDirectoryNavigation"));
        assertFalse(web.contains("https://chatgpt.com/projects"));
        assertFalse(web.contains("prepareProjectEntryIfNeeded"));
        assertTrue(web.contains("SelfRunDom.prepareInitialContext("));
    }

    @Test public void projectBindingRemainsStrictDuringPreparationAndConversationCapture() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("if (!allowedRoute(String.valueOf(u)))"));
        assertTrue(web.contains("private boolean allowedRoute(String url)"));
        assertTrue(web.contains(".equals(SelfRunScript.projectId(url))"));
        assertTrue(web.contains("ProjectUrlPolicy.sameProject(target, url)"));
        assertFalse(web.contains("private boolean allowedPreparationRoute"));
    }

    @Test public void pageReadinessUsesNormalSettleWithoutDirectoryHydrationOrClickRecovery() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("settleMs=500"));
        assertTrue(web.contains("later(SelfRun3WebAdapter.this::advance, 500L)"));
        assertFalse(web.contains("PROJECT_DIRECTORY_"));
        assertFalse(web.contains("projectCandidateIndex"));
        assertFalse(web.contains("projectProbeRetries"));
        assertFalse(web.contains("projectClickAttempts"));
        assertFalse(web.contains("projectDirectoryRecoveries"));
    }

    @Test public void preparationRetryRecreatesWebViewAndReentersTheSameDirectTarget() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("strategy=recreate-webview"));
        assertTrue(web.contains("disposeHost();"));
        assertTrue(web.contains("web.loadUrl(target);"));
        assertTrue(web.contains("status=reentry"));
        assertTrue(web.contains("String launchRoute = SelfRunScript.projectId(target).isEmpty() ? \"general\" : \"project\";"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
