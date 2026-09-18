package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3RuntimeSettingsProcessAndroidTest {
    @Test public void seedSettingsBeforeProcessRestart() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear()
                .putString(SelfRun3RuntimeSettings.KEY_WORK_MODE, SelfRun3RuntimeSettings.WorkMode.SERVER.name())
                .putLong(SelfRun3RuntimeSettings.KEY_RESULT_REPAIR_MINUTES, 121L)
                .putLong(SelfRun3RuntimeSettings.KEY_STALL_ALERT_MINUTES, 126L)
                .putLong(SelfRun3RuntimeSettings.KEY_RESULT_POLL_SECONDS, 31L)
                .putLong(SelfRun3RuntimeSettings.KEY_WEB_PREPARATION_SECONDS, 91L)
                .putBoolean("processRestartSeeded", true)
                .commit());
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, settings.workMode());
        assertEquals(SelfRun3RuntimeSettings.ON_DEVICE_DEFAULT_MIGRATION_VERSION,
                prefs.getInt(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, 0));
    }

    @Test public void verifySettingsAfterProcessRestart() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE);
        assertTrue(prefs.getBoolean("processRestartSeeded", false));
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertEquals(121L, settings.resultRepairMinutes());
        assertEquals(126L, settings.stallAlertMinutes());
        assertEquals(31L, settings.resultPollSeconds());
        assertEquals(91L, settings.webPreparationSeconds());
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, settings.workMode());
        assertEquals("ON_DEVICE", prefs.getString(SelfRun3RuntimeSettings.KEY_WORK_MODE, ""));
        assertEquals(SelfRun3RuntimeSettings.ON_DEVICE_DEFAULT_MIGRATION_VERSION,
                prefs.getInt(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, 0));
        assertTrue(prefs.edit().clear().commit());
    }
}
