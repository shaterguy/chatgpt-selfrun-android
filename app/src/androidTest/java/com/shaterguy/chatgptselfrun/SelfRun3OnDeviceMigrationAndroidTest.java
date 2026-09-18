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
public final class SelfRun3OnDeviceMigrationAndroidTest {
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

    @Test public void existingServerModeMigratesOnceWithoutClearingOtherSettings() {
        assertTrue(prefs.edit()
                .putString(SelfRun3RuntimeSettings.KEY_WORK_MODE, SelfRun3RuntimeSettings.WorkMode.SERVER.name())
                .putLong(SelfRun3RuntimeSettings.KEY_RESULT_POLL_SECONDS, 47L)
                .putString("driveBindingSentinel", "keep")
                .commit());

        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);

        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, settings.workMode());
        assertEquals("ON_DEVICE", prefs.getString(SelfRun3RuntimeSettings.KEY_WORK_MODE, ""));
        assertEquals(SelfRun3RuntimeSettings.ON_DEVICE_DEFAULT_MIGRATION_VERSION,
                prefs.getInt(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, 0));
        assertEquals(47L, settings.resultPollSeconds());
        assertEquals("keep", prefs.getString("driveBindingSentinel", ""));
    }

    @Test public void corruptMigrationMarkerIsRetriedAndNormalizedWithModeInOneState() {
        assertTrue(prefs.edit()
                .putString(SelfRun3RuntimeSettings.KEY_WORK_MODE, SelfRun3RuntimeSettings.WorkMode.SERVER.name())
                .putString(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, "broken")
                .commit());

        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);

        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, settings.workMode());
        assertEquals("ON_DEVICE", prefs.getString(SelfRun3RuntimeSettings.KEY_WORK_MODE, ""));
        assertEquals(SelfRun3RuntimeSettings.ON_DEVICE_DEFAULT_MIGRATION_VERSION,
                prefs.getInt(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, 0));
    }

    @Test public void completedMigrationIsIdempotentAndPreservesExplicitOnDevice() {
        assertTrue(prefs.edit()
                .putString(SelfRun3RuntimeSettings.KEY_WORK_MODE, SelfRun3RuntimeSettings.WorkMode.ON_DEVICE.name())
                .putInt(SelfRun3RuntimeSettings.KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION,
                        SelfRun3RuntimeSettings.ON_DEVICE_DEFAULT_MIGRATION_VERSION)
                .putString("sentinel", "unchanged")
                .commit());

        SelfRun3RuntimeSettings first = new SelfRun3RuntimeSettings(context);
        SelfRun3RuntimeSettings second = new SelfRun3RuntimeSettings(context);

        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, first.workMode());
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, second.workMode());
        assertEquals("unchanged", prefs.getString("sentinel", ""));
    }
}
