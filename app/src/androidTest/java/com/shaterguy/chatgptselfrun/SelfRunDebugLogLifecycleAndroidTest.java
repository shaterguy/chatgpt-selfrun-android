package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunDebugLogLifecycleAndroidTest {
    private Context context;
    private String task;
    private CountDownLatch release;
    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        task = "SR-debug-" + System.nanoTime();
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        executor(SelfRunDebugLogUploadWorker.class, "UPLOADS").submit(() -> {
            entered.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        context.getSharedPreferences("selfrun_drive", 0).edit().clear()
                .putString("runId", task).putString("phase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("requirement", "debug lifecycle").putString("mode", "CHAT")
                .putString("taskMode", "CHAT").putBoolean("paused", true).commit();
        SelfRunDebugLogSync.archive(context).request(
                new SelfRunDebugLogArchive.Binding(task, "account123", "basefolder123", "taskfolder123"), "WAIT", "turn1");
    }
    @After public void cleanup() throws Exception {
        drainJournal();
        WorkManager.getInstance(context).cancelUniqueWork("selfrun-debug-" + task).getResult().get(5, TimeUnit.SECONDS);
        release.countDown();
        context.getSharedPreferences("selfrun_drive", 0).edit().clear().commit();
    }

    @Test public void terminalTriggersPersistWhileUploadsAreBlockedAndDoNotChangeRunState() throws Exception {
        SelfRunStore store = new SelfRunStore(context);
        Map<String, ?> before = context.getSharedPreferences("selfrun_drive", 0).getAll();
        for (String trigger : new String[]{"PAUSE", "USER_INTERVENTION", "DONE"}) {
            new SelfRunRunLog(context).record(store, "TEST_" + trigger, "auxiliary event");
            SelfRunDebugLogSync.requestStored(context, task, trigger);
        }
        drainJournal();
        SelfRunDebugLogArchive.Snapshot saved = SelfRunDebugLogSync.archive(context).state(task);
        assertEquals(4, saved.requestedSequence);
        assertEquals("DONE", saved.trigger);
        assertEquals(0, saved.uploadedSequence);
        assertTrue(saved.requestedBytes > 0);
        assertEquals(before, context.getSharedPreferences("selfrun_drive", 0).getAll());
        // New facade instances/process initialization must recover the same final outbox identity.
        SelfRunDebugLogSync.recover(context);
        drainJournal();
        assertEquals(saved.requestedBytes, SelfRunDebugLogSync.archive(context).state(task).requestedBytes);
    }

    @Test public void eitherCallbackOrderRequiresBothConversationAndSubmissionConfirmation() throws Exception {
        for (SelfRun3Engine.Kind proof : new SelfRun3Engine.Kind[]{SelfRun3Engine.Kind.STARTED, SelfRun3Engine.Kind.ACCEPTED}) {
            for (boolean conversationFirst : new boolean[]{false, true}) {
                SelfRun3Engine.State state = SelfRun3Engine.create(task, task + ":turn:1",
                        new JSONObject().put("taskMode", "CHAT").put("mode", "CHAT"));
                state = apply(state, SelfRun3Engine.Kind.RESOURCE, new JSONObject().put("key", "folderId").put("value", "taskfolder123"));
                state = apply(state, SelfRun3Engine.Kind.RESOURCE, new JSONObject().put("key", "requirementDocumentId").put("value", "requirement123"));
                state = apply(state, SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
                state = apply(state, SelfRun3Engine.Kind.RESOURCE, new JSONObject().put("key", "resultDocumentId").put("value", "resultdoc123"));
                state = apply(state, SelfRun3Engine.Kind.TURN_READY, new JSONObject().put("prompt", "fixture"));
                state = apply(state, SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject().put("at", 100L));
                JSONObject conversation = new JSONObject().put("key", "conversationUrl").put("value", "https://chatgpt.com/c/conversation-123");
                JSONObject callback = new JSONObject().put("requestId", state.requestId());
                assertFalse(SelfRunDebugLogSync.waitingReady(state));
                state = apply(state, conversationFirst ? SelfRun3Engine.Kind.RESOURCE : proof,
                        conversationFirst ? conversation : callback);
                assertFalse(SelfRunDebugLogSync.waitingReady(state));
                state = apply(state, conversationFirst ? proof : SelfRun3Engine.Kind.RESOURCE,
                        conversationFirst ? callback : conversation);
                assertTrue(SelfRunDebugLogSync.waitingReady(state));
                assertTrue(SelfRunDebugLogSync.waitingReady(new SelfRun3Engine.State(state.json())));
            }
        }
    }

    private static SelfRun3Engine.State apply(SelfRun3Engine.State state, SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("event-" + System.nanoTime(), kind,
                state.taskId(), state.turnId(), payload));
    }

    @Test public void realMainStopRecordsFinalEventWithoutWaitingForUploader() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> {
                try {
                    Method method = MainActivity.class.getDeclaredMethod("stopSelfRun");
                    method.setAccessible(true); method.invoke(activity);
                } catch (Exception e) { throw new AssertionError(e); }
                assertTrue(new SelfRunStore(activity).userStopped());
            });
        }
        drainJournal();
        SelfRunDebugLogArchive.Snapshot saved = SelfRunDebugLogSync.archive(context).state(task);
        assertEquals("STOP", saved.trigger);
        assertEquals(2, saved.requestedSequence);
        File journal = new File(context.getNoBackupFilesDir(), "selfrun-debug-archive/task-" + task + ".jsonl");
        assertTrue(new String(Files.readAllBytes(journal.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("UI_STOP"));
    }

    @Test public void unusableAuxiliaryStorageCannotPropagateOrPauseExecution() throws Exception {
        File notDirectory = File.createTempFile("debug-disk-failure", ".tmp", context.getCacheDir());
        Context broken = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return notDirectory; }
        };
        Map<String, ?> before = context.getSharedPreferences("selfrun_drive", 0).getAll();
        SelfRunDebugLogSync.record(broken, task, "event");
        SelfRunDebugLogSync.requestStored(broken, task, "DONE");
        drainJournal();
        assertEquals(before, context.getSharedPreferences("selfrun_drive", 0).getAll());
        assertTrue(notDirectory.delete());
    }
    @Test public void cumulativeExportSanitizesStatusAndDetail() throws Exception {
        context.getSharedPreferences("selfrun_drive", 0).edit()
                .putString("status", "Bearer private-status-value").commit();
        new SelfRunRunLog(context).record(new SelfRunStore(context), "TEST_REDACTION", "authorization=private-detail-value");
        drainJournal();
        File journal = new File(context.getNoBackupFilesDir(), "selfrun-debug-archive/task-" + task + ".jsonl");
        String text = new String(Files.readAllBytes(journal.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("private-status-value"));
        assertFalse(text.contains("private-detail-value"));
        JSONObject line = new JSONObject(text.trim());
        assertEquals("redacted", line.getString("status"));
        assertEquals("redacted", line.getString("detail"));
    }
    private static void drainJournal() throws Exception {
        executor(SelfRunDebugLogSync.class, "JOURNAL").submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
    private static ExecutorService executor(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return (ExecutorService) field.get(null);
    }
}
