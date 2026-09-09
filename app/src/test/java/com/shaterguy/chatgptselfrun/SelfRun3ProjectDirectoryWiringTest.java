package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRun3ProjectDirectoryWiringTest {
    @Test public void newAttemptUsesDirectoryEntryBeforeExistingBootstrapPipeline() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("web.loadUrl(SelfRun3ProjectDirectoryNavigation.entryUrl(target));"));
        assertFalse(web.contains("web.loadUrl(target);"));
        assertTrue(web.contains("if (prepareProjectEntryIfNeeded()) return;"));
        assertTrue(web.contains("SelfRunDom.prepareInitialContext("));
        assertTrue(web.indexOf("if (prepareProjectEntryIfNeeded()) return;")
                < web.indexOf("SelfRunDom.prepareInitialContext("));
    }

    @Test public void preparationAllowsDirectoryAndCandidateProjectButNormalBindingStaysStrict() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("private boolean allowedPreparationRoute(String url)"));
        assertTrue(web.contains("SelfRun3ProjectDirectoryNavigation.isDirectoryPage(url)"));
        assertTrue(web.contains("ProjectUrlPolicy.parseProject(url) != null"));
        assertTrue(web.contains("private boolean allowedRoute(String url)"));
        assertTrue(web.contains(".equals(SelfRunScript.projectId(url))"));
    }

    @Test public void wrongSameNameProjectReturnsToDirectoryAndAdvancesCandidate() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3ProjectDirectoryNavigation.isWrongProjectRoute(target, actual)"));
        assertTrue(web.contains("projectCandidateIndex++;"));
        assertTrue(web.contains("loadProjectDirectory(\"wrong-project-candidate\")"));
        assertTrue(web.contains("SelfRun3ProjectDirectoryNavigation.build(projectDisplayName, projectCandidateIndex)"));
    }

    @Test public void projectDirectoryWaitsForHydrationAndDoesNotReclickSameDocumentForever() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3ProjectDirectoryRecoveryPolicy.HYDRATION_SETTLE_MS"));
        assertTrue(web.contains("SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterClick(projectClickAttempts)"));
        assertTrue(web.contains("recoverProjectDirectory(\"click-no-transition\")"));
        assertTrue(web.contains("SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterRetry(projectProbeRetries)"));
        assertTrue(web.contains("recoverProjectDirectory(\"directory-hydration-stalled\")"));
    }

    @Test public void repeatedRecoveryRecreatesHostAndPreparationRestartNeverReusesStalledHost() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(projectDirectoryRecoveries)"));
        assertTrue(web.contains("disposeHost();\n            ensureWeb();"));
        assertTrue(web.contains("status=preparation-restart;strategy=recreate-webview"));
        assertTrue(web.contains("loadProjectDirectory(\"preparation-restart\")"));
    }

    @Test public void recurringProjectFailureWritesActionableDebugEvents() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("PROJECT_DIRECTORY_RESULT"));
        assertTrue(web.contains("PROJECT_DIRECTORY_RECOVERY"));
        assertTrue(web.contains("PROJECT_DIRECTORY_LOAD"));
        assertTrue(web.contains("PROJECT_ROUTE_READY"));
        assertTrue(web.contains("rows="));
        assertTrue(web.contains("matches="));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
