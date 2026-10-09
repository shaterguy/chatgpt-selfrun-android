package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.os.Process;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ColdProcessPersistenceAndroidTest {
    private static final String PREFS = "selfrun_drive";

    private Context appContext() {
        return ApplicationProvider.getApplicationContext();
    }

    private static void assertPair(LastUsedExecutionProfileStore.Selection s,
                                   String mode, String model, String reasoning) {
        assertNotNull("missing persisted history", s);
        assertEquals(mode, s.mode);
        assertEquals(model, s.model);
        assertEquals(reasoning, s.reasoning);
    }

    @Test public void seedAcceptedSelectionsBeforeOSProcessDeath() {
        Context context = appContext();
        assertTrue(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit());
        CanonicalProfileAndroidTestFixtures.install(context);
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);

        assertTrue(ChatReasoningPreferenceStore.save(
                context, "sr59-cold-chat", "medium", "medium"));
        store.start("sr59-cold-chat", SelfRunStore.MODE_CHAT,
                SelfRunScript.GENERAL_CHAT_URL, "cold-chat",
                new ArrayList<>(), SelfRunStore.MODE_CHAT);
        store.startWork("sr59-cold-work", SelfRunScript.GENERAL_CHAT_URL,
                "cold-work", new ArrayList<>(), "5.6sol", "xhigh",
                SelfRunStore.MODE_WORK);
        store.startWork("sr59-cold-hybrid", SelfRunScript.GENERAL_CHAT_URL,
                "cold-hybrid", new ArrayList<>(), "5.6luna", "max",
                SelfRunStore.MODE_HYBRID);

        assertPair(store.lastUsedProfile(SelfRunStore.MODE_CHAT),
                "CHAT", "gpt-5-6-thinking", "medium");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_WORK),
                "WORK", "5.6sol", "xhigh");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_HYBRID),
                "WORK", "5.6luna", "max");
        System.out.println("SR59_COLD_SEED_ACCEPTED pid=" + Process.myPid());
    }

    @Test public void verifyAfterActualOSForceStopNewProcess() {
        Context context = appContext();
        ProfileRegistry.initialize(context);
        SelfRunStore store = new SelfRunStore(context);
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_CHAT),
                "CHAT", "gpt-5-6-thinking", "medium");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_WORK),
                "WORK", "5.6sol", "xhigh");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_HYBRID),
                "WORK", "5.6luna", "max");

        try (ActivityScenario<SelfRunNewActivity> scenario =
                ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("medium", invoke(activity, "selectedChatReasoning"));
                chooseOuterMode(activity, 1);
                ProfileRegistry.Profile work = (ProfileRegistry.Profile)
                        invoke(activity, "selectedWorkBootstrapProfile");
                assertNotNull(work);
                assertEquals("5.6sol", work.signalModel);
                assertEquals("xhigh", work.signalReasoning);

                chooseOuterMode(activity, 2);
                assertEquals(1, ((Ui.SelectionField)
                        field(activity, "firstMode")).getSelectedItemPosition());
                ProfileRegistry.Profile hybrid = (ProfileRegistry.Profile)
                        invoke(activity, "selectedWorkBootstrapProfile");
                assertNotNull(hybrid);
                assertEquals("5.6luna", hybrid.signalModel);
                assertEquals("max", hybrid.signalReasoning);

                chooseOuterMode(activity, 0);
                assertEquals("medium", invoke(activity, "selectedChatReasoning"));
            });
        }
        System.out.println("SR59_COLD_READBACK_AFTER_OS_FORCE_STOP pid=" + Process.myPid());
    }

    private static void chooseOuterMode(SelfRunNewActivity activity, int index) {
        ((Ui.SelectionField) field(activity, "mode")).setSelection(index);
        call(activity, "restoreLastUsedForTaskMode");
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static Object invoke(Object owner, String method) {
        try {
            Method m = owner.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(owner);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void call(Object owner, String method) {
        invoke(owner, method);
    }
}
