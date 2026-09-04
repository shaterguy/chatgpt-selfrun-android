package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunHealthPolicyTest {
    @Test public void healthHasNoIndependentPollingOrScheduler() throws Exception {
        String health = source("SelfRunHealthObservationStore.java") + source("SelfRunProcessExitDiagnostics.java");
        assertFalse(health.contains("WorkManager"));
        assertFalse(health.contains("JobScheduler"));
        assertFalse(health.contains("AlarmManager"));
        assertFalse(health.contains("postDelayed"));
        assertFalse(health.contains("scheduleAtFixedRate"));
        assertFalse(health.contains("ScheduledExecutor"));
    }

    @Test public void networkReusesSingleExistingCallback() throws Exception {
        String network = source("SelfRunNetworkState.java");
        assertEquals(1, count(network, "new ConnectivityManager.NetworkCallback"));
        assertEquals(1, count(network, "registerDefaultNetworkCallback"));
        assertTrue(network.contains("health.observeNetwork"));
    }

    @Test public void applicationExitInfoIsEventOnlyAndApi30Gated() throws Exception {
        String exit = source("SelfRunProcessExitDiagnostics.java");
        String app = source("SelfRunApplication.java");
        assertTrue(exit.contains("Build.VERSION.SDK_INT < 30"));
        assertTrue(exit.contains("getHistoricalProcessExitReasons(null, 0, 5)"));
        assertTrue(exit.contains("lastProcessedExitTimestamp"));
        assertFalse(exit.contains("getDescription()"));
        assertTrue(app.contains("SelfRunProcessExitDiagnostics.capture(context)"));
    }

    @Test public void observationStoreIsBoundedAndPrivacySafe() throws Exception {
        String store = source("SelfRunHealthObservationStore.java");
        assertTrue(store.contains("MAX_RUNS = 100"));
        assertTrue(store.contains("currentHealth"));
        assertTrue(store.contains("lastImportantHealth"));
        assertTrue(store.contains("finalHealth"));
        assertFalse(store.contains("requestHeader"));
        assertFalse(store.contains("authorization"));
        assertFalse(store.contains("access_token"));
        assertFalse(store.contains("refresh_token"));
        assertFalse(store.contains("cookie"));
    }

    @Test public void healthCanBeDisabledWithoutRuntimeStateMutation() throws Exception {
        String store = source("SelfRunHealthObservationStore.java");
        String health = source("SelfRunHealth.java");
        assertTrue(store.contains("KEY_ENABLED"));
        assertTrue(store.contains("if (!enabled()"));
        assertFalse(health.contains("setPhase("));
        assertFalse(health.contains("setActive("));
        assertFalse(health.contains("setPaused("));
        assertFalse(health.contains("setLastError("));
    }

    @Test public void chatWorkAndDriveSourcesAreConsumersNotChangedSourcesOfTruth() throws Exception {
        String health = source("SelfRunHealth.java");
        String web = source("SelfRunWebDiagnostics.java");
        assertTrue(health.contains("SelfRunWebDiagnostics.phaseKindForHealth"));
        assertTrue(web.contains("static String phaseKindForHealth(String phase) { return phaseKind(phase); }"));
        assertFalse(health.contains("DriveSignalParser.scan"));
        assertFalse(health.contains("WorkProtocolNativeObserver"));
        assertFalse(health.contains("WorkProtocolTransportCapture"));
    }

    @Test public void historyAndDetailRenderWithoutNewRefreshTimer() throws Exception {
        String history = source("SelfRunHistoryActivity.java");
        String detail = source("SelfRunDetailActivity.java");
        assertTrue(history.contains("RUN HEALTH"));
        assertTrue(detail.contains("RUN HEALTH"));
        assertFalse(history.contains("new Handler"));
        assertFalse(history.contains("postDelayed"));
        assertFalse(detail.contains("new Handler"));
        assertFalse(detail.contains("postDelayed"));
    }

    @Test public void developmentIdentityIs232Dev8() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 2020040"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '2.3.2-dev8'"));
        assertTrue(gradle.contains("applicationIdSuffix '.test'"));
    }

    private static String source(String name) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + name,
                "src/main/java/com/shaterguy/chatgptselfrun/" + name);
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int count = 0, from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
