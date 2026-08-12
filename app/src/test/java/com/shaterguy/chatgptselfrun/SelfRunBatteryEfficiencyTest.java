package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunBatteryEfficiencyTest {
    @Test
    public void domObserverUsesMutationEventsAndSelfRunOwnedDebounceOnly() {
        String install = SelfRunDomObserver.install("test-token");
        String detach = SelfRunDomObserver.detach();
        assertTrue(install.contains("new MutationObserver"));
        assertTrue(install.contains("postMessage('changed')"));
        assertTrue(install.contains("setTimeout"));
        assertFalse(install.contains("setInterval"));
        assertFalse(install.contains("pauseTimers"));
        assertTrue(detach.contains("observer?.disconnect()"));
        assertTrue(detach.contains("clearTimeout"));
        assertTrue(detach.contains("removeEventListener"));
        assertTrue(detach.contains("port?.close()"));
    }

    @Test
    public void serviceUsesOriginScopedMessageChannelAndLowFrequencyWatchdog() throws Exception {
        String text = source("SelfRunService.java");
        assertTrue(text.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(text.contains("createWebMessageChannel()"));
        assertTrue(text.contains("postWebMessage(new WebMessage(token"));
        assertTrue(text.contains("return Uri.parse(\"https://\" + host);"));
        assertFalse(text.contains("addJavascriptInterface"));
        assertFalse(text.contains("WorkManager"));
        assertFalse(text.contains("AlarmManager"));
        assertFalse(text.contains("setRendererPriorityPolicy"));
    }

    @Test
    public void normalWaitsArmObserverAndWatchdogInsteadOfFastPolling() throws Exception {
        String text = source("SelfRunService.java");
        int method = text.indexOf("private void uiWait(String detail)");
        int nextMethod = text.indexOf("private void submittedWait", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        assertTrue(body.contains("ensureDomObserver();"));
        assertTrue(body.contains("scheduleWatchdog();"));
        assertFalse(body.contains("scheduleStep(1_500L)"));
        assertFalse(body.contains("Math.min(5_000L"));
    }

    @Test
    public void wakeLockIsStateManagedAndPreservedPauseNeverPausesWholeWebView() throws Exception {
        String text = source("SelfRunService.java");
        assertTrue(text.contains("private void updateWakeLockForState()"));
        assertTrue(text.contains("if (canRun() && !isRateLimited()) acquireWakeLock();"));
        assertTrue(text.contains("wakeLock.acquire();"));
        assertFalse(text.contains("wakeLock.acquire(10 * 60_000L)"));
        assertFalse(text.contains("webView.onPause()"));
        assertFalse(text.contains("webView.onResume()"));
        assertFalse(text.contains("pauseTimers"));
    }

    @Test
    public void repeatedPersistenceWritesAreGuardedAndPhaseClockResetDoesNotSyncHistory() throws Exception {
        String store = source("SelfRunStore.java");
        String history = source("SelfRunHistoryStore.java");
        assertTrue(store.contains("if (next.equals(prefs.getString(key, \"\"))) return;"));
        assertTrue(store.contains("if (value == prefs.getBoolean(key, false)) return;"));
        int clock = store.indexOf("void restartPhaseClock()");
        int status = store.indexOf("void setStatus", clock);
        assertTrue(clock >= 0 && status > clock);
        assertFalse(store.substring(clock, status).contains("syncHistory"));
        assertTrue(history.contains("if (sameSnapshot(previousSnapshot, nextSnapshot)) return true;"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
