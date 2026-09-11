package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun3RuntimeSettingsTest {
    @Test public void legacyDefaultsArePreservedInOperatorUnits() {
        assertEquals(120L, SelfRun3RuntimeSettings.DEFAULT_RESULT_REPAIR_MINUTES);
        assertEquals(125L, SelfRun3RuntimeSettings.DEFAULT_STALL_ALERT_MINUTES);
        assertEquals(30L, SelfRun3RuntimeSettings.DEFAULT_RESULT_POLL_SECONDS);
        assertEquals(90L, SelfRun3RuntimeSettings.DEFAULT_WEB_PREPARATION_SECONDS);
    }

    @Test public void positiveIntegerParserRejectsBlankZeroNegativeTextAndOverflow() {
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits(null, 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("", 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("   ", 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("0", 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("-1", 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("1.5", 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("abc", 60_000L));
        long largestSafe = Long.MAX_VALUE / 60_000L;
        assertEquals(Long.valueOf(largestSafe),
                SelfRun3RuntimeSettings.parsePositiveUnits(String.valueOf(largestSafe), 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits(String.valueOf(largestSafe + 1L), 60_000L));
        assertNull(SelfRun3RuntimeSettings.parsePositiveUnits("999999999999999999999999999999", 1_000L));
    }

    @Test public void settingsAreStoredOutsidePerRunProjectionAndConsumedByEveryRuntimePath() throws Exception {
        String settings = source("SelfRun3RuntimeSettings.java");
        String store = source("SelfRunStore.java");
        String coordinator = source("SelfRun3Coordinator.java");
        String notifier = source("SelfRun3TurnStallNotifier.java");
        String web = source("SelfRun3WebAdapter.java");
        String ui = source("SelfRunLogMenuActivity.java");

        assertTrue(settings.contains("PREFS = \"selfrun3_runtime_settings\""));
        assertTrue(store.contains("private static final String PREFS = \"selfrun_drive\""));
        assertFalse(store.contains("selfrun3_runtime_settings"));
        assertTrue(coordinator.contains("runtimeSettings.resultRepairMs()"));
        assertTrue(coordinator.contains("runtimeSettings.resultPollMs()"));
        assertTrue(notifier.contains("runtimeSettings.stallAlertMs()"));
        assertTrue(notifier.contains("settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)"));
        assertTrue(web.contains("prepareTimeoutMs = runtimeSettings.webPreparationMs()"));
        assertTrue(ui.contains("runtimeSettings::saveResultRepairMinutes"));
        assertTrue(ui.contains("runtimeSettings::saveStallAlertMinutes"));
        assertTrue(ui.contains("runtimeSettings::saveResultPollSeconds"));
        assertTrue(ui.contains("runtimeSettings::saveWebPreparationSeconds"));
    }

    @Test public void removedTimingsNoLongerHideInPowerPolicy() throws Exception {
        String power = source("SelfRun3PowerPolicy.java");
        assertFalse(power.contains("NORMAL_WAIT_POLL_MS"));
        assertFalse(power.contains("WEB_PREPARATION_MAX_MS"));
        assertTrue(power.contains("CALLBACK_TIMEOUT_MS = 5_000L"));
        assertTrue(power.contains("WAKE_LOCK_MAX_MS = 90_000L"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
