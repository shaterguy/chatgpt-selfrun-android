package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProfileRegistryPersistenceAndroidTest {
    private static final String PREFS = "selfrun_drive_profile_registry";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
    }

    @After public void tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
    }

    @Test public void userProfileSurvivesProcessLocalRegistryRecreation() {
        ProfileRegistry.CapturedProfile captured = ProfileRegistry.parseCaptured(
                "{\"mode\":\"work\",\"operations\":["
                        + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"gpt-5.7-nova-wm\"},"
                        + "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"extreme\"},"
                        + "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"},"
                        + "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}]}");
        ProfileRegistry.RegisterResult result = ProfileRegistry.registerCaptured(captured, "nova", "extreme");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, result.status);
        String fingerprint = result.profile.fingerprint;

        ProfileRegistry.resetForTests(); ProfileRegistry.initialize(context);
        ProfileRegistry.Profile restored = ProfileRegistry.resolveWork("nova", "extreme");
        assertNotNull(restored); assertEquals(fingerprint, restored.fingerprint);
    }

    @Test public void builtInDeletionTombstoneSurvivesRecreationAndAllowsExplicitReregistration() {
        ProfileRegistry.Profile builtIn = ProfileRegistry.resolveWork("sol", "max");
        assertNotNull(builtIn); String fingerprint = builtIn.fingerprint;
        assertTrue(ProfileRegistry.delete(fingerprint));
        ProfileRegistry.resetForTests(); ProfileRegistry.initialize(context);
        assertNull(ProfileRegistry.resolveWork("sol", "max"));

        ProfileRegistry.CapturedProfile recaptured = ProfileRegistry.parseCaptured(
                "{\"mode\":\"work\",\"operations\":["
                        + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"gpt-5.6-sol-wm\"},"
                        + "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"max\"},"
                        + "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"},"
                        + "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}]}");
        ProfileRegistry.RegisterResult added = ProfileRegistry.registerCaptured(recaptured, "solar", "maximum");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, added.status);
        assertNotNull(ProfileRegistry.resolveWork("solar", "maximum"));
        assertNull(ProfileRegistry.resolveWork("sol", "max"));

        assertTrue(ProfileRegistry.delete(added.profile.fingerprint));
        ProfileRegistry.resetForTests(); ProfileRegistry.initialize(context);
        assertNull(ProfileRegistry.resolveWork("sol", "max"));
        assertNull(ProfileRegistry.resolveWork("solar", "maximum"));
    }
}
