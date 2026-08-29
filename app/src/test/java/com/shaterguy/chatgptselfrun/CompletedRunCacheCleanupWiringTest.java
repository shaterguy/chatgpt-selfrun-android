package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CompletedRunCacheCleanupWiringTest {
    @Test public void terminalDoneAcknowledgesBeforeCleanupAndTeardown() throws Exception {
        String service = src("SelfRunService.java");
        int start = service.indexOf("private void finishDoneSideEffect");
        int end = service.indexOf("private void cleanupAfterCompletedRun", start);
        assertTrue(start >= 0 && end > start);
        String block = service.substring(start, end);
        int acknowledge = block.indexOf("acknowledgeTerminalSideEffect");
        int cleanup = block.indexOf("cleanupAfterCompletedRun(ownerRunId)");
        int stop = block.indexOf("stopRuntime()");
        assertTrue(acknowledge >= 0 && cleanup > acknowledge && stop > cleanup);
    }

    @Test public void genericDestroyAndGenericCleanupDoNotClearApplicationCache() throws Exception {
        String service = src("SelfRunService.java");
        String host = src("HeadlessWebViewHost.java");
        int genericStart = service.indexOf("private void cleanupWebView()");
        int genericEnd = service.indexOf("private void stopRuntime()", genericStart);
        assertTrue(genericStart >= 0 && genericEnd > genericStart);
        assertFalse(service.substring(genericStart, genericEnd).contains("clearResourceCacheAfterCompletedRun"));

        int destroyStart = host.indexOf("void destroy()");
        assertTrue(destroyStart >= 0);
        assertFalse(host.substring(destroyStart).contains("clearCache("));
        assertTrue(host.contains("boolean clearResourceCacheAfterCompletedRun()"));
        assertTrue(host.contains("webView.clearCache(true)"));
    }

    @Test public void cleanupPathContainsNoBroadBrowsingDataDeletionApi() throws Exception {
        String combined = src("SelfRunService.java") + "\n" + src("HeadlessWebViewHost.java");
        assertFalse(combined.contains("removeAllCookies("));
        assertFalse(combined.contains("removeSessionCookies("));
        assertFalse(combined.contains("WebStorage.deleteAllData("));
        assertFalse(combined.contains("deleteBrowsingData("));
        assertFalse(combined.contains("getCacheDir()"));
        assertFalse(combined.contains("getExternalCacheDir()"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
