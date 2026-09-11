package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3RuntimeSettingsAndroidTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().commit());
    }

    @After public void tearDown() {
        assertTrue(prefs.edit().clear().commit());
    }

    @Test public void freshInstallDefaultsMatchCurrentBehavior() {
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertEquals(10L, settings.resultRepairMinutes());
        assertEquals(125L, settings.stallAlertMinutes());
        assertEquals(30L, settings.resultPollSeconds());
        assertEquals(90L, settings.webPreparationSeconds());
        assertFalse(prefs.contains(SelfRun3RuntimeSettings.KEY_RESULT_REPAIR_MINUTES));
        assertFalse(prefs.contains(SelfRun3RuntimeSettings.KEY_STALL_ALERT_MINUTES));
        assertFalse(prefs.contains(SelfRun3RuntimeSettings.KEY_RESULT_POLL_SECONDS));
        assertFalse(prefs.contains(SelfRun3RuntimeSettings.KEY_WEB_PREPARATION_SECONDS));
    }

    @Test public void committedSettingsSurviveNewSettingsInstanceAndUseRequestedUnits() {
        SelfRun3RuntimeSettings first = new SelfRun3RuntimeSettings(context);
        assertTrue(first.saveResultRepairMinutes("121"));
        assertTrue(first.saveStallAlertMinutes("126"));
        assertTrue(first.saveResultPollSeconds("31"));
        assertTrue(first.saveWebPreparationSeconds("91"));

        SelfRun3RuntimeSettings reopened = new SelfRun3RuntimeSettings(context);
        assertEquals(121L, reopened.resultRepairMinutes());
        assertEquals(126L, reopened.stallAlertMinutes());
        assertEquals(31L, reopened.resultPollSeconds());
        assertEquals(91L, reopened.webPreparationSeconds());
        assertEquals(121L * 60_000L, reopened.resultRepairMs());
        assertEquals(126L * 60_000L, reopened.stallAlertMs());
        assertEquals(31_000L, reopened.resultPollMs());
        assertEquals(91_000L, reopened.webPreparationMs());
    }

    @Test public void invalidSaveNeverOverwritesLastGoodValueAndCorruptStoredTypeFallsBack() {
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveResultPollSeconds("45"));
        for (String invalid : new String[]{"", "0", "-1", "1.5", "abc", "999999999999999999999999999"}) {
            assertFalse(invalid, settings.saveResultPollSeconds(invalid));
            assertEquals(45L, new SelfRun3RuntimeSettings(context).resultPollSeconds());
        }
        assertTrue(prefs.edit().putString(SelfRun3RuntimeSettings.KEY_RESULT_POLL_SECONDS, "corrupt").commit());
        assertEquals(30L, new SelfRun3RuntimeSettings(context).resultPollSeconds());
    }

    @Test public void perRunProjectionClearDoesNotClearRuntimeSettings() {
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveWebPreparationSeconds("123"));
        Context app = context.getApplicationContext();
        assertTrue(app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putString("temporary", "value").clear().commit());
        assertEquals(123L, new SelfRun3RuntimeSettings(context).webPreparationSeconds());
    }
}
