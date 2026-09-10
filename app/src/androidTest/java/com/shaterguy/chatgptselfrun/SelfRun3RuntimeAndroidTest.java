package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.*;

/** Device-level persistence checks for the V3 ledger, lineage marker and user-input bridge. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3RuntimeAndroidTest {
    private Context context;
    private File ledgerFile;

    @Before public void before() {
        context = ApplicationProvider.getApplicationContext();
        ledgerFile = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db");
        deleteLedgerFiles();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() {
        deleteLedgerFiles();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
    }

    @Test public void ledgerPersistsClaimAcrossCloseAndIgnoresDuplicateEvent() {
        SelfRun3Engine.State initial = initial();
        SelfRun3Ledger first = new SelfRun3Ledger(context);
        SelfRun3Engine.State state = first.ensure(initial);
        state = resource(first, state, "folderId", "folder");
        state = resource(first, state, "requirementDocumentId", "requirements");
        state = first.apply(event(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
        state = resource(first, state, "resultDocumentId", "result");
        JSONObject ready = new JSONObject(); SelfRun3Engine.put(ready, "prompt", "hello");
        state = first.apply(event(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready));
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 123L);
        state = first.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));
        int count = first.eventCount(state.taskId());
        SelfRun3Engine.State duplicate = first.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));
        assertEquals(count, first.eventCount(state.taskId()));
        assertTrue(duplicate.flag("sendClaimed"));
        first.close();

        SelfRun3Ledger reopened = new SelfRun3Ledger(context);
        SelfRun3Engine.State recovered = reopened.load(initial.taskId());
        assertNotNull(recovered);
        assertTrue(recovered.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, recovered.stage());
        assertEquals(initial.requestId(), recovered.requestId());
        reopened.close();
    }

    @Test public void runMarkerSeparatesV3LineageAndSurvivesNewStoreObjects() {
        String run = "SR-V3-MARKER";
        assertFalse(SelfRun3RunMarker.current(context, run));
        assertTrue(SelfRun3RunMarker.mark(context, run));
        assertTrue(SelfRun3RunMarker.current(context, run));
        SelfRun3RunMarker.clearIfCurrent(context, run);
        assertFalse(SelfRun3RunMarker.current(context, run));
    }

    @Test public void userInputConsumeIsRevisionConditionalSoLateEditSurvives() {
        String run = "SR-V3-INPUT";
        assertTrue(context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit()
                .putString("runId", run).putString("text", "first").putLong("revision", 7L).commit());
        SelfRun3UserInput.Snapshot first = SelfRun3UserInput.snapshot(context, run);
        assertEquals("first", first.text);
        assertEquals(7L, first.revision);
        assertTrue(context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit()
                .putString("text", "late").putLong("revision", 8L).commit());
        assertFalse(SelfRun3UserInput.consumeIfRevision(context, run, 7L));
        SelfRun3UserInput.Snapshot late = SelfRun3UserInput.snapshot(context, run);
        assertEquals("late", late.text);
        assertEquals(8L, late.revision);
    }

    @Test public void ledgerLivesInNoBackupStorage() {
        SelfRun3Ledger ledger = new SelfRun3Ledger(context);
        ledger.ensure(initial());
        String databasePath = ledger.getReadableDatabase().getPath();
        ledger.close();
        assertTrue(databasePath.startsWith(context.getNoBackupFilesDir().getAbsolutePath()));
    }

    @Test public void importantUserAlertPostsWhenNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
        assertTrue(Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        manager.cancelAll();
        NotificationHelper.notifyUser(context, "사용자 조치 필요",
                "SelfRun 실행을 계속하려면 사용자 조치가 필요합니다.");
        if (Build.VERSION.SDK_INT >= 26) {
            assertNotNull(manager.getNotificationChannel("selfrun-drive-alerts-v2"));
            assertEquals(NotificationManager.IMPORTANCE_HIGH,
                    manager.getNotificationChannel("selfrun-drive-alerts-v2").getImportance());
        }
        assertTrue("important SelfRun alert was not posted",
                awaitNotificationTitle(manager, "SelfRun Drive · 사용자 조치 필요"));
    }

    private static boolean awaitNotificationTitle(NotificationManager manager, String expected) {
        long deadline = SystemClock.elapsedRealtime() + 2_000L;
        do {
            for (StatusBarNotification item : manager.getActiveNotifications()) {
                CharSequence title = item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                if (title != null && expected.contentEquals(title)) return true;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private SelfRun3Engine.State initial() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        return SelfRun3Engine.create("task", "task:turn:1", config);
    }

    private static SelfRun3Engine.State resource(SelfRun3Ledger ledger, SelfRun3Engine.State state,
                                                  String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(event(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload));
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload);
    }

    private void deleteLedgerFiles() {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(ledgerFile.getAbsolutePath() + suffix);
            if (file.exists()) assertTrue("failed to delete " + file, file.delete());
        }
    }
}
