package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun31WorkFixAndroidTest {
    private static final String REGISTRY_PREFS = "selfrun_drive_profile_registry";
    private static final String STORE_PREFS = "selfrun_drive";
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
}
