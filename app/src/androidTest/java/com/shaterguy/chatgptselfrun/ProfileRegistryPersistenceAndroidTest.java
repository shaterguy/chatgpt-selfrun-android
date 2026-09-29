package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
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

    @Test public void canonicalSnapshotSurvivesProcessLocalRegistryRecreation() {
        String canonical = ProfileRegistry.exportWorkJson("test");
        ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.WORK, canonical, "version-1");
        int expectedCount = ProfileRegistry.profileCount(ProfileRegistry.Mode.WORK);
        assertTrue(expectedCount > 0);

        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);

        assertEquals(expectedCount, ProfileRegistry.profileCount(ProfileRegistry.Mode.WORK));
        assertEquals("version-1", ProfileRegistry.sourceVersion(ProfileRegistry.Mode.WORK));
    }

    @Test public void canonicalAdditionRemovalAndMalformedRefreshPreserveLastKnownGood() throws Exception {
        String full = ProfileRegistry.exportWorkJson("test");
        JSONObject subsetRoot = new JSONObject(full);
        JSONArray profiles = subsetRoot.getJSONArray("profiles");
        assertTrue(profiles.length() >= 2);
        JSONObject removed = profiles.getJSONObject(0);
        String removedModel = removed.getJSONObject("signal").getString("model");
        String removedReasoning = removed.getJSONObject("signal").getString("reasoning");
        profiles.remove(0);
        String subset = subsetRoot.toString();

        ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.WORK, subset, "version-1");
        assertNull(ProfileRegistry.resolveWork(removedModel, removedReasoning));

        ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.WORK, full, "version-2");
        assertNotNull(ProfileRegistry.resolveWork(removedModel, removedReasoning));

        assertThrows(IllegalArgumentException.class, () ->
                ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.WORK,
                        "{\"schema\":\"selfrun-work-profile-registry-v1\",\"registrySchemaVersion\":1,\"appVersion\":\"test\",\"profiles\":[]}",
                        "version-bad"));
        assertNotNull(ProfileRegistry.resolveWork(removedModel, removedReasoning));
        assertEquals("version-2", ProfileRegistry.sourceVersion(ProfileRegistry.Mode.WORK));

        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
        assertNotNull(ProfileRegistry.resolveWork(removedModel, removedReasoning));
        assertEquals("version-2", ProfileRegistry.sourceVersion(ProfileRegistry.Mode.WORK));
    }
}
