package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LastUsedExecutionProfileAndroidTest {
    private static final String PREFS = "selfrun_drive";
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

    private SelfRunStore readyStore() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        return store;
    }

    private static void assertPair(LastUsedExecutionProfileStore.Selection selection,
                                   String actualMode, String model, String reasoning) {
        assertNotNull(selection);
        assertEquals(actualMode, selection.mode);
        assertEquals(model, selection.model);
        assertEquals(reasoning, selection.reasoning);
    }

    @Test public void acceptedStartsRememberThreeIndependentModes() {
        SelfRunStore store = readyStore();
        assertTrue(ChatReasoningPreferenceStore.save(context, "run-chat-a", "medium", "medium"));
        store.start("run-chat-a", SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "chat", new ArrayList<>(), SelfRunStore.MODE_CHAT);
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_CHAT), "CHAT", "gpt-5-6-thinking", "medium");

        store.startWork("run-work-b", SelfRunScript.GENERAL_CHAT_URL, "work",
                new ArrayList<>(), "5.6luna", "max", SelfRunStore.MODE_WORK);
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_WORK), "WORK", "5.6luna", "max");

        assertTrue(ChatReasoningPreferenceStore.save(context, "run-hybrid-c", "xhigh", "xhigh"));
        store.start("run-hybrid-c", SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "hybrid", new ArrayList<>(), SelfRunStore.MODE_HYBRID);
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_HYBRID), "CHAT", "gpt-5-6-thinking", "xhigh");

        store.startWork("run-hybrid-d", SelfRunScript.GENERAL_CHAT_URL, "hybrid-work",
                new ArrayList<>(), "5.6sol", "xhigh", SelfRunStore.MODE_HYBRID);
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_HYBRID), "WORK", "5.6sol", "xhigh");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_CHAT), "CHAT", "gpt-5-6-thinking", "medium");
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_WORK), "WORK", "5.6luna", "max");

        SelfRunStore reopened = new SelfRunStore(context);
        assertPair(reopened.lastUsedProfile(SelfRunStore.MODE_HYBRID), "WORK", "5.6sol", "xhigh");
    }

    @Test public void failedOrAbandonedStartDoesNotWriteAndContinuationCannotOverwrite() {
        SelfRunStore store = readyStore();
        assertNull(store.lastUsedProfile(SelfRunStore.MODE_CHAT));
        assertTrue(ChatReasoningPreferenceStore.save(context, "run-not-started", "medium", "medium"));
        assertNull(store.lastUsedProfile(SelfRunStore.MODE_CHAT));

        try {
            store.startWork("run-bad", SelfRunScript.GENERAL_CHAT_URL,
                    "bad", new ArrayList<>(), "5.6luna", "missing", SelfRunStore.MODE_WORK);
            fail("unregistered profile must fail");
        } catch (IllegalArgumentException expected) { }
        assertNull(store.lastUsedProfile(SelfRunStore.MODE_WORK));

        store.startWork("run-good", SelfRunScript.GENERAL_CHAT_URL,
                "good", new ArrayList<>(), "5.6luna", "max", SelfRunStore.MODE_WORK);
        String before = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LastUsedExecutionProfileStore.PREFIX + "WORK", "");
        store.setPendingModel("later-model");
        store.setPendingReasoning("later-reasoning");
        store.setPaused(true);
        store.stopByUser();
        assertEquals(before, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LastUsedExecutionProfileStore.PREFIX + "WORK", ""));
    }

    private static Object field(Object owner, String name) {
        try {
            Field member = owner.getClass().getDeclaredField(name);
            member.setAccessible(true);
            return member.get(owner);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void call(Object owner, String method) {
        try {
            Method target = owner.getClass().getDeclaredMethod(method);
            target.setAccessible(true);
            target.invoke(owner);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void chooseOuterMode(SelfRunNewActivity activity, int index) {
        Ui.SelectionField selector = (Ui.SelectionField) field(activity, "mode");
        selector.setSelection(index);
        call(activity, "restoreLastUsedForTaskMode");
    }

    @Test public void modeChangesRestoreLastAcceptedTaskNotOtherModes() {
        SelfRunStore store = readyStore();
        assertTrue(ChatReasoningPreferenceStore.save(context, "run-chat-a", "medium", "medium"));
        store.start("run-chat-a", SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "chat", new ArrayList<>(), SelfRunStore.MODE_CHAT);
        store.startWork("run-work-b", SelfRunScript.GENERAL_CHAT_URL, "work",
                new ArrayList<>(), "5.6sol", "xhigh", SelfRunStore.MODE_WORK);
        store.startWork("run-hybrid-c", SelfRunScript.GENERAL_CHAT_URL, "hybrid",
                new ArrayList<>(), "5.6luna", "max", SelfRunStore.MODE_HYBRID);

        try (ActivityScenario<SelfRunNewActivity> scenario =
                ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("medium", (String) invoke(activity, "selectedChatReasoning"));
                chooseOuterMode(activity, 1);
                ProfileRegistry.Profile work = (ProfileRegistry.Profile) invoke(activity,
                        "selectedWorkBootstrapProfile");
                assertEquals("5.6sol", work.signalModel);
                assertEquals("xhigh", work.signalReasoning);

                chooseOuterMode(activity, 2);
                assertEquals(1, ((Ui.SelectionField) field(activity, "firstMode"))
                        .getSelectedItemPosition());
                work = (ProfileRegistry.Profile) invoke(activity, "selectedWorkBootstrapProfile");
                assertEquals("5.6luna", work.signalModel);
                assertEquals("max", work.signalReasoning);

                chooseOuterMode(activity, 0);
                assertEquals("medium", (String) invoke(activity, "selectedChatReasoning"));
            });
        }
    }

    private static Object invoke(Object owner, String method) {
        try {
            Method target = owner.getClass().getDeclaredMethod(method);
            target.setAccessible(true);
            return target.invoke(owner);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void removedProfilesStayUnsupportedAndRequireReselection() {
        SelfRunStore store = readyStore();
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        LastUsedExecutionProfileStore.put(edit, SelfRunStore.MODE_WORK,
                new LastUsedExecutionProfileStore.Selection("WORK", "removed-work", "max"));
        LastUsedExecutionProfileStore.put(edit, SelfRunStore.MODE_CHAT,
                new LastUsedExecutionProfileStore.Selection("CHAT", "removed-chat", "medium"));
        assertTrue(edit.commit());

        try (ActivityScenario<SelfRunNewActivity> scenario =
                ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(ChatReasoningPreferenceStore.KEEP, invoke(activity, "selectedChatReasoning"));
                chooseOuterMode(activity, 1);
                assertNull(invoke(activity, "selectedWorkBootstrapProfile"));
                assertEquals("removed-work", invoke(activity, "currentWorkModel"));
                chooseOuterMode(activity, 0);
                assertEquals("removed-chat", invoke(activity, "currentChatModel"));
            });
        }
    }

    @Test public void savedDraftOverridesHistoryOnActivityRecreation() {
        SelfRunStore store = readyStore();
        store.startWork("run-work-b", SelfRunScript.GENERAL_CHAT_URL, "work",
                new ArrayList<>(), "5.6luna", "max", SelfRunStore.MODE_WORK);
        try (ActivityScenario<SelfRunNewActivity> scenario =
                ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                chooseOuterMode(activity, 1);
                ((EditText) field(activity, "requirement")).setText("Draft only - not accepted");
                Ui.SelectionField profile = (Ui.SelectionField) field(activity, "workBootstrapProfile");
                profile.setSelection(1); // The edited draft differs from the accepted last-used pair.
                assertEquals("5.6sol",
                        ((ProfileRegistry.Profile) invoke(activity, "selectedWorkBootstrapProfile")).signalModel);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(1, ((Ui.SelectionField) field(activity, "mode")).getSelectedItemPosition());
                assertEquals("Draft only - not accepted",
                        ((EditText) field(activity, "requirement")).getText().toString());
                assertEquals("5.6sol",
                        ((ProfileRegistry.Profile) invoke(activity, "selectedWorkBootstrapProfile")).signalModel);
            });
        }
        assertPair(store.lastUsedProfile(SelfRunStore.MODE_WORK), "WORK", "5.6luna", "max");
    }

    @Test public void startRefreshLocksAllProfileAndModeControls() {
        readyStore();
        try (ActivityScenario<SelfRunNewActivity> scenario =
                ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field flag = activity.getClass().getDeclaredField("profileRefreshPending");
                    flag.setAccessible(true);
                    flag.setBoolean(activity, true);
                    call(activity, "updateChatReasoningAvailability");
                    assertFalse(((Ui.SelectionField) field(activity, "mode")).isEnabled());
                    assertFalse(((Ui.SelectionField) field(activity, "firstMode")).isEnabled());
                    assertFalse(((Ui.SelectionField) field(activity, "chatReasoning")).isEnabled());
                    assertFalse(((Ui.SelectionField) field(activity, "workBootstrapProfile")).isEnabled());
                    flag.setBoolean(activity, false);
                    call(activity, "updateChatReasoningAvailability");
                    assertTrue(((Ui.SelectionField) field(activity, "mode")).isEnabled());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }
}
