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
public final class SelfRun3OnDeviceCoordinatorAndroidTest {
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

    @Test public void generalCandidateCanActivateServerWhenModeIsManuallySelected() {
        assertTrue(BuildConfig.SELFRUN_SERVER_FEATURES_ENABLED);
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveWorkMode(SelfRun3RuntimeSettings.WorkMode.SERVER));
        assertEquals(SelfRun3RuntimeSettings.WorkMode.SERVER, settings.workMode());
        assertTrue(SelfRunServerFeaturePolicy.enabled(context));
    }

    @Test public void normalOnDeviceModeUsesLocalPollingPolicy() {
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, settings.workMode());
        assertFalse(SelfRunServerFeaturePolicy.enabled(context));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(settings.workMode(), false));
    }
}
