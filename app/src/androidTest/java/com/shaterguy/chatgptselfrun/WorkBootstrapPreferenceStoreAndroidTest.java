package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class WorkBootstrapPreferenceStoreAndroidTest {
    private static final String PREFS = "selfrun_drive_work_bootstrap";
    private static final String REGISTRY_PREFS = "selfrun_drive_profile_registry";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
    }

    @After public void tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
    }

    @Test public void firstLoadPreservesLegacySolXhighDefaultWhenAvailable() {
        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertEquals("sol", selected.model);
        assertEquals("xhigh", selected.reasoning);
        assertTrue(selected.valid());
    }

    @Test public void mostRecentlySavedValidPairIsRestored() {
        assertTrue(WorkBootstrapPreferenceStore.save(context, "terra", "max"));
        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertEquals("terra", selected.model);
        assertEquals("max", selected.reasoning);
        assertTrue(selected.valid());
    }

    @Test public void deletedSavedProfileFallsBackToAnotherValidRegistryPair() {
        ProfileRegistry.Profile saved = ProfileRegistry.resolveWork("sol", "max");
        assertNotNull(saved);
        assertTrue(WorkBootstrapPreferenceStore.save(context, saved.signalModel, saved.signalReasoning));
        assertTrue(ProfileRegistry.delete(saved.fingerprint));

        WorkBootstrapPreferenceStore.Selection selected = WorkBootstrapPreferenceStore.load(context);
        assertTrue(selected.valid());
        assertNotNull(ProfileRegistry.resolveWork(selected.model, selected.reasoning));
    }
}
