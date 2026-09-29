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

import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WorkBootstrapPreferenceStoreAndroidTest {
    private static final String PREFS = "selfrun_drive_work_bootstrap";
    private static final String REGISTRY_PREFS = "selfrun_drive_profile_registry";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        CanonicalProfileAndroidTestFixtures.install(context);
    }

    @After public void tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        CanonicalProfileAndroidTestFixtures.clear(context);
    }

    @Test public void firstLoadUsesCurrentCanonicalRegistryOnly() {
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
        assertFalse(profiles.isEmpty());
        ProfileRegistry.Profile expected = profiles.get(0);
        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertEquals(expected.signalModel, selected.model);
        assertEquals(expected.signalReasoning, selected.reasoning);
        assertTrue(selected.valid());
    }

    @Test public void mostRecentlySavedValidPairIsRestored() {
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
        assertTrue(profiles.size() >= 2);
        ProfileRegistry.Profile expected = profiles.get(profiles.size() - 1);
        assertTrue(WorkBootstrapPreferenceStore.save(context, expected.signalModel, expected.signalReasoning));
        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertEquals(expected.signalModel, selected.model);
        assertEquals(expected.signalReasoning, selected.reasoning);
        assertTrue(selected.valid());
    }

    @Test public void removedSavedProfileFallsBackOnlyWithinLatestRegistry() throws Exception {
        List<ProfileRegistry.Profile> current = ProfileRegistry.listWork();
        assertTrue(current.size() >= 2);
        ProfileRegistry.Profile saved = current.get(0);
        assertTrue(WorkBootstrapPreferenceStore.save(context, saved.signalModel, saved.signalReasoning));

        JSONObject root = new JSONObject(ProfileRegistry.exportWorkJson("test"));
        JSONArray profiles = root.getJSONArray("profiles");
        profiles.remove(0);
        ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.WORK, root.toString(), "version-removed");
        assertNull(ProfileRegistry.resolveWork(saved.signalModel, saved.signalReasoning));

        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertTrue(selected.valid());
        assertNotEquals(saved.signalModel + "|" + saved.signalReasoning, selected.model + "|" + selected.reasoning);
        assertNotNull(ProfileRegistry.resolveWork(selected.model, selected.reasoning));
    }
}
