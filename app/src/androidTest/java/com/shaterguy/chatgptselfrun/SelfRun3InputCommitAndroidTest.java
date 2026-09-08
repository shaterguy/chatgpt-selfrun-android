package com.shaterguy.chatgptselfrun;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises actual UI reservation writes against the production terminal commit transaction. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3InputCommitAndroidTest {
    private Context context;
    private SelfRunStore store;
    private SelfRun3Ledger ledger;
    @Before public void before() {
        context = ApplicationProvider.getApplicationContext();
        clearFiles();
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
        store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.start("task", "CHAT", "https://chatgpt.com/", "test");
        store.setPhase("V3_WAITING");
        UserNextInputStore.initialize(context);
        ledger = new SelfRun3Ledger(context);
    }
    @After public void after() {
        if (ledger != null) ledger.close();
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
        clearFiles();
    }
    @Test public void saveAttemptDuringTerminalCommitIsRejectedWithoutBeingAcceptedThenLost() throws Exception {
        SelfRun3Engine.State result = ledger.ensure(doneState());
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            attempting.countDown();
            accepted.set(UserNextInputStore.save("task", "late edit"));
        });
        synchronized (UserNextInputStore.class) {
            writer.start();
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            SelfRun3Engine.State done = SelfRun3UserInput.commit(context, store, ledger, result);
            assertEquals(SelfRun3Engine.Stage.DONE, done.stage());
            assertFalse(store.active());
        }
        writer.join(5000);
        assertFalse(writer.isAlive());
        assertFalse(accepted.get());
        assertEquals(SelfRun3Engine.Stage.DONE, ledger.load("task").stage());
    }
    @Test public void acceptedEditBeforeTerminalCommitSurvivesAndCreatesDurableNextExecution() {
        SelfRun3Engine.State result = ledger.ensure(doneState());
        assertTrue(UserNextInputStore.save("task", "accepted late edit"));
        long revision = SelfRun3UserInput.snapshot(context, "task").revision;
        SelfRun3Engine.State next = SelfRun3UserInput.commit(context, store, ledger, result);
        assertFalse(next.terminal());
        assertNotEquals(result.turnId(), next.turnId());
        assertTrue(store.active());
        ledger.close();
        ledger = new SelfRun3Ledger(context);
        assertEquals(next.turnId(), ledger.load("task").turnId());
        SelfRun3UserInput.Snapshot pending = SelfRun3UserInput.snapshot(context, "task");
        assertEquals("accepted late edit", pending.text);
        assertEquals(revision, pending.revision);
    }
    @Test public void runReplacementWaitsForCommitThenKeepsNewRunActive() throws Exception {
        SelfRun3Engine.State result = ledger.ensure(doneState());
        CountDownLatch attempting = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            attempting.countDown();
            store.start("next-task", "CHAT", "https://chatgpt.com/", "next");
        });
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            replacement.start();
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            SelfRun3UserInput.commit(context, store, ledger, result);
        }
        replacement.join(5000);
        assertFalse(replacement.isAlive());
        assertEquals("next-task", store.runId());
        assertTrue(store.active());
        try {
            SelfRun3UserInput.commit(context, store, ledger, result);
            fail("stale completion must not deactivate replacement");
        } catch (IllegalStateException expected) {
            assertEquals("STALE_INPUT_TASK", expected.getMessage());
        }
        assertTrue(store.active());
    }

    private SelfRun3Engine.State doneState() {
        JSONObject raw = new JSONObject(), config = new JSONObject(), resources = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT"); SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(resources, "resultDocumentId", "resultdoc");
        SelfRun3Engine.put(raw, "schema", SelfRun3Engine.STATE_SCHEMA);
        SelfRun3Engine.put(raw, "taskId", "task"); SelfRun3Engine.put(raw, "turnId", "task:turn:1");
        SelfRun3Engine.put(raw, "requestId", "task:turn:1-request"); SelfRun3Engine.put(raw, "turn", 1);
        SelfRun3Engine.put(raw, "stage", "RECONCILING"); SelfRun3Engine.put(raw, "phase", "VERIFY");
        SelfRun3Engine.put(raw, "config", config); SelfRun3Engine.put(raw, "resources", resources);
        SelfRun3Engine.put(raw, "sendClaimed", true); SelfRun3Engine.put(raw, "inputRevision", 0L);
        SelfRun3Engine.State state = new SelfRun3Engine.State(raw);
        JSONObject result = SelfRun3Engine.emptyResult(state), handoff = new JSONObject();
        for (String key : new String[]{"objective","completed","remaining","evidence","constraints","next_action"}) SelfRun3Engine.put(handoff,key,"test");
        for (String key : new String[]{"requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"}) SelfRun3Engine.put(handoff,key,new org.json.JSONArray());
        SelfRun3Engine.put(result, "committed", true); SelfRun3Engine.put(result, "status", "DONE");
        SelfRun3Engine.put(result, "phase_completed", "VERIFY_DONE"); SelfRun3Engine.put(result, "next_phase", "DONE");
        SelfRun3Engine.put(result, "handoff", handoff); SelfRun3Engine.put(raw, "result", result.toString());
        return new SelfRun3Engine.State(raw);
    }
    private void clearFiles() {
        for (String suffix : new String[]{"","-wal","-shm","-journal"}) {
            File f = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db" + suffix);
            if (f.exists()) assertTrue(f.delete());
        }
    }
}
