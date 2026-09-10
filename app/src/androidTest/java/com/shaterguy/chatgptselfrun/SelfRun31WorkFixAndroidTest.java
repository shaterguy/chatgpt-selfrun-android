package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PatternMatcher;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun31WorkFixAndroidTest {
    private static final String REGISTRY_PREFS = "selfrun_drive_profile_registry";
    private static final String STORE_PREFS = "selfrun_drive";
    private static final String TEST_RUN_ID = "SR-20260910-221032-JVVH29";
    private static final String CONVERSATION_ID = "11111111-2222-4333-8444-555555555555";
    private Context context;
    private NotificationManager notifications;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
        notifications = context.getSystemService(NotificationManager.class);
        notifications.cancelAll();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @After public void tearDown() {
        if (notifications != null) notifications.cancelAll();
        context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
    }

    @Test public void recoveredDriveCommunicationClearsOnlyTransientWarnings() {
        SelfRunStore store = new SelfRunStore(context);

        store.setLastError("V3_DRIVE_NETWORK_RETRY", "temporary");
        SelfRun3Coordinator.clearRecoveredDriveWarning(store);
        assertEquals("", store.lastErrorCode());
        assertEquals("", store.lastErrorMessage());

        store.setLastError("V3_DRIVE_HTTP_RETRY_503", "temporary");
        SelfRun3Coordinator.clearRecoveredDriveWarning(store);
        assertEquals("", store.lastErrorCode());

        store.setLastError("V3_DRIVE_AUTH_REQUIRED", "manual action required");
        SelfRun3Coordinator.clearRecoveredDriveWarning(store);
        assertEquals("V3_DRIVE_AUTH_REQUIRED", store.lastErrorCode());
        assertEquals("manual action required", store.lastErrorMessage());
    }

    @Test public void workLabelsIncludeModelAndPersistForCapturedAndImportedProfiles() {
        ProfileRegistry.Profile sol = ProfileRegistry.resolveWork("sol", "high");
        assertNotNull(sol);
        assertEquals("Sol · high", sol.displayLabel());
        assertEquals("Instant", ProfileRegistry.resolveChat("instant").displayLabel());

        ProfileRegistry.CapturedProfile captured = ProfileRegistry.parseCaptured(
                "{\"mode\":\"work\",\"operations\":["
                        + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"gpt-6-astra-wm\"},"
                        + "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"extended\"},"
                        + "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"},"
                        + "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}]}");
        ProfileRegistry.RegisterResult added = ProfileRegistry.registerCaptured(captured, "astra", "high");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, added.status);
        assertEquals("Astra · high", added.profile.displayLabel());
        assertEquals("gpt-6-astra-wm / extended", added.profile.actualCombination());

        String imported = "{"
                + "\"schema\":\"selfrun-work-profile-registry-v1\","
                + "\"registrySchemaVersion\":1,"
                + "\"appVersion\":\"3.1.0-dev7\","
                + "\"profiles\":[{"
                + "\"signal\":{\"model\":\"orion\",\"reasoning\":\"balanced\"},"
                + "\"request\":{\"model\":\"gpt-6-orion-wm\",\"thinking_effort\":\"balanced\",\"conversation_origin\":\"tpp\",\"service_tier\":\"standard\"},"
                + "\"operations\":["
                + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"gpt-6-orion-wm\"},"
                + "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"balanced\"},"
                + "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"},"
                + "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}],"
                + "\"builtIn\":false}]}";
        ProfileRegistry.ImportResult importResult = ProfileRegistry.importJson(ProfileRegistry.Mode.WORK, imported);
        assertEquals(1, importResult.added);

        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(context);
        assertEquals("Astra · high", ProfileRegistry.resolveWork("astra", "high").displayLabel());
        assertEquals("Orion · balanced", ProfileRegistry.resolveWork("orion", "balanced").displayLabel());
    }

    @Test public void mainRunControlsKeepStableSpacingAcrossHistoryRoundTrip() {
        seedRunningProjection("https://chatgpt.com/c/" + CONVERSATION_ID);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            RunControlSnapshot before = snapshotRunControls(scenario);

            try (ActivityScenario<SelfRunHistoryActivity> ignored = ActivityScenario.launch(SelfRunHistoryActivity.class)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            RunControlSnapshot after = snapshotRunControls(scenario);
            assertEquals(before.actionStripOrientation, after.actionStripOrientation);
            assertEquals(before.pauseToConversationGap, after.pauseToConversationGap);
            assertEquals(before.slotToStopGap, after.slotToStopGap);
        }
    }

    @Test public void currentConversationButtonUsesCanonicalActionViewAndDisablesWithoutUrl() {
        seedRunningProjection("https://www.chatgpt.com/c/" + CONVERSATION_ID + "?temporary=1");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(currentConversationButton(activity).isEnabled()));

            IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
            filter.addDataScheme("https");
            filter.addDataAuthority("chatgpt.com", null);
            filter.addDataPath("/c/" + CONVERSATION_ID, PatternMatcher.PATTERN_LITERAL);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    filter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
            try {
                scenario.onActivity(activity -> currentConversationButton(activity).performClick());
                instrumentation.waitForIdleSync();
                assertEquals(1, monitor.getHits());
            } finally {
                instrumentation.removeMonitor(monitor);
            }

            scenario.onActivity(activity -> {
                SelfRunStore store = new SelfRunStore(activity);
                store.setExecutionProjection(SelfRunStore.MODE_CHAT, "", "", "");
                invokeRefreshCurrent(activity);
                assertFalse(currentConversationButton(activity).isEnabled());
            });
        }
    }

    @Test public void completionAlertUsesExistingImportantChannel() throws Exception {
        NotificationHelper.notifyUser(context, "작업 완료", "SelfRun 작업이 완료되었습니다.");

        StatusBarNotification completion = null;
        for (int attempt = 0; attempt < 20 && completion == null; attempt++) {
            for (StatusBarNotification active : notifications.getActiveNotifications()) {
                CharSequence title = active.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                if (title != null && title.toString().contains("작업 완료")) {
                    completion = active;
                    break;
                }
            }
            if (completion == null) Thread.sleep(50L);
        }
        assertNotNull(completion);
        assertEquals("selfrun-drive-alerts-v2", completion.getNotification().getChannelId());
        NotificationChannel channel = notifications.getNotificationChannel(completion.getNotification().getChannelId());
        assertNotNull(channel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.getImportance());
        assertEquals("SelfRun Drive · 작업 완료",
                completion.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
    }

    private void seedRunningProjection(String conversationUrl) {
        long now = System.currentTimeMillis();
        assertTrue(context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("runId", TEST_RUN_ID)
                .putLong("createdAt", now)
                .putLong("phaseStartedAt", now)
                .putString("taskMode", SelfRunStore.MODE_CHAT)
                .putString("mode", SelfRunStore.MODE_CHAT)
                .putString("requirement", "run controls runtime contract")
                .putString("conversationUrl", conversationUrl)
                .putString("phase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("status", "실행 중")
                .putInt("turn", 7)
                .putBoolean("active", true)
                .putBoolean("paused", false)
                .putBoolean("userStopped", false)
                .commit());
    }

    private static RunControlSnapshot snapshotRunControls(ActivityScenario<MainActivity> scenario) {
        AtomicReference<RunControlSnapshot> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            LinearLayout slot = field(activity, "runControlSlot", LinearLayout.class);
            Button pause = field(activity, "pauseButton", Button.class);
            Button resume = field(activity, "resumeButton", Button.class);
            Button conversation = currentConversationButton(activity);
            Button stop = field(activity, "stopButton", Button.class);
            LinearLayout actionStrip = (LinearLayout) slot.getParent();

            assertSame(slot, pause.getParent());
            assertSame(slot, resume.getParent());
            assertSame(slot, conversation.getParent());
            assertSame(actionStrip, stop.getParent());
            assertNotSame(pause.getParent(), stop.getParent());
            assertEquals(View.VISIBLE, pause.getVisibility());
            assertEquals(View.GONE, resume.getVisibility());
            assertEquals(View.VISIBLE, conversation.getVisibility());
            assertEquals(View.VISIBLE, stop.getVisibility());
            assertTrue(pause.isEnabled());
            assertTrue(conversation.isEnabled());
            assertTrue(stop.isEnabled());
            assertTrue(pause.getWidth() > 0);
            assertTrue(conversation.getWidth() > 0);
            assertTrue(slot.getWidth() > 0);
            assertTrue(slot.getHeight() > 0);
            assertTrue(stop.getWidth() > 0);
            assertTrue(stop.getHeight() > 0);

            int[] pauseLocation = new int[2];
            int[] conversationLocation = new int[2];
            int[] slotLocation = new int[2];
            int[] stopLocation = new int[2];
            pause.getLocationOnScreen(pauseLocation);
            conversation.getLocationOnScreen(conversationLocation);
            slot.getLocationOnScreen(slotLocation);
            stop.getLocationOnScreen(stopLocation);
            int pauseToConversationGap = conversationLocation[0] - (pauseLocation[0] + pause.getWidth());
            int actionStripOrientation = actionStrip.getOrientation();
            assertTrue(actionStripOrientation == LinearLayout.HORIZONTAL
                    || actionStripOrientation == LinearLayout.VERTICAL);
            int slotToStopGap = actionStripOrientation == LinearLayout.HORIZONTAL
                    ? stopLocation[0] - (slotLocation[0] + slot.getWidth())
                    : stopLocation[1] - (slotLocation[1] + slot.getHeight());
            assertTrue(pauseToConversationGap >= Ui.dp(activity, 8));
            assertTrue(slotToStopGap >= Ui.dp(activity, 8));
            result.set(new RunControlSnapshot(actionStripOrientation, pauseToConversationGap, slotToStopGap));
        });
        assertNotNull(result.get());
        return result.get();
    }

    private static Button currentConversationButton(MainActivity activity) {
        return field(activity, "currentConversationButton", Button.class);
    }

    private static void invokeRefreshCurrent(MainActivity activity) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("refreshCurrent");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static <T> T field(MainActivity activity, String name, Class<T> type) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class RunControlSnapshot {
        final int actionStripOrientation;
        final int pauseToConversationGap;
        final int slotToStopGap;

        RunControlSnapshot(int actionStripOrientation, int pauseToConversationGap, int slotToStopGap) {
            this.actionStripOrientation = actionStripOrientation;
            this.pauseToConversationGap = pauseToConversationGap;
            this.slotToStopGap = slotToStopGap;
        }
    }
}
