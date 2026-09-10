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
        assertTrue(prefs.edit().clear().commit());
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveResultRepairMinutes("121"));
        assertTrue(settings.saveStallAlertMinutes("126"));
        assertTrue(settings.saveResultPollSeconds("31"));
        assertTrue(settings.saveWebPreparationSeconds("91"));
        assertTrue(prefs.edit().putBoolean("processRestartSeeded", true).commit());
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
        assertTrue(prefs.edit().clear().commit());
    }
}
