package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(AndroidJUnit4.class)
public final class SelfRunStoreWorkProfileAndroidTest {
    private static final String STORE_PREFS = "selfrun_drive";
    private static final String REGISTRY_PREFS = "selfrun_drive_profile_registry";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
    }

    @After public void tearDown() {
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
    }

    @Test public void selectedWorkPairIsPartOfInitialDurableRunState() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.startWork("run-test", SelfRunScript.GENERAL_CHAT_URL, "test",
                "terra", "max");
        assertEquals("terra", store.pendingModel());
        assertEquals("max", store.pendingReasoning());

        SelfRunStore recreated = new SelfRunStore(context);
        assertEquals("run-test", recreated.runId());
        assertEquals("terra", recreated.pendingModel());
        assertEquals("max", recreated.pendingReasoning());
    }

    @Test public void invalidSelectedWorkPairIsRejectedBeforeRunMutation() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        assertThrows(IllegalArgumentException.class,
                () -> store.startWork("run-invalid", SelfRunScript.GENERAL_CHAT_URL, "test",
                        "terra", "missing"));
        assertEquals("", store.runId());
        assertEquals("", store.pendingModel());
        assertEquals("", store.pendingReasoning());
    }

    @Test public void legacyGenericWorkStartKeepsSolXhighDefault() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.start("run-work", SelfRunStore.MODE_WORK, SelfRunScript.GENERAL_CHAT_URL, "test");
        assertEquals("sol", store.pendingModel());
        assertEquals("xhigh", store.pendingReasoning());
    }

    @Test public void chatStartStillHasNoPendingWorkPair() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.start("run-chat", SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL, "test");
        assertEquals("", store.pendingModel());
        assertEquals("", store.pendingReasoning());
    }
}
