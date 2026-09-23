package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3SuccessorProcessRestartAndroidTest {
    private static final String TASK = "runtime-successor-process-restart";
    private static final String PREDECESSOR_TURN = TASK + ":turn:1";

    @Test public void seedAcceptedPredecessorBeforeProcessRestart() {
        Context context = ApplicationProvider.getApplicationContext();
        reset(context);

        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.start(TASK, "CHAT", SelfRunScript.GENERAL_CHAT_URL,
                "runtime successor process restart regression");
        assertTrue(SelfRun3RunMarker.mark(context, TASK));

        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            SelfRun3Engine.State predecessor = runtimeWaitingState(context);
            ledger.ensure(predecessor);
            String committed = committedContinueResult(predecessor);
            SelfRun3RuntimeTestBridge.installScenario(
                    TASK, predecessor.turnId(), committed, true);
            assertTrue(new SelfRun3RuntimeSettings(context).saveWebPreparationSeconds("10"));

            startServiceAction(context, new Intent(context, SelfRunService.class)
                    .setAction(SelfRunService.ACTION_RUN));

            await("production coordinator did not persist accepted predecessor before process restart",
                    7_000L, () -> {
                        SelfRun3Engine.State root = ledger.load(TASK);
                        SelfRun3Engine.State source =
                                root == null ? null : root.execution(PREDECESSOR_TURN);
                        return source != null
                                && source.stage() == SelfRun3Engine.Stage.RECONCILING
                                && source.hasResult()
                                && SelfRun3SuccessorTransitionPolicy.armed(source)
                                && SelfRun3RuntimeTestBridge.resultReadCount() == 1;
                    });

            SelfRun3Engine.State accepted = ledger.load(TASK).execution(PREDECESSOR_TURN);
            assertNotNull(accepted);
            assertEquals("ACCEPTED", accepted.successorTransition().optString("stage"));
            assertEquals(0, accepted.successorTransition().optInt("recoveryAttempt"));
            assertEquals(1, SelfRun3RuntimeTestBridge.resultReadCount());

            context.stopService(new Intent(context, SelfRunService.class));
            SystemClock.sleep(250L);

            SelfRun3Engine.State persistedRoot = ledger.load(TASK);
            SelfRun3Engine.State persisted = persistedRoot.execution(PREDECESSOR_TURN);
            assertEquals(SelfRun3Engine.Stage.RECONCILING, persisted.stage());
            assertTrue(persisted.hasResult());
            assertTrue(SelfRun3SuccessorTransitionPolicy.armed(persisted));
            assertEquals(1, persistedRoot.number("maxTurn"));
        }
    }

    @Test public void verifyAcceptedPredecessorRecoversAfterProcessRestart() {
        Context context = ApplicationProvider.getApplicationContext();
        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            SelfRun3Engine.State restoredRoot = ledger.load(TASK);
            assertNotNull("seeded successor transition missing after process restart", restoredRoot);
            SelfRun3Engine.State predecessor = restoredRoot.execution(PREDECESSOR_TURN);
            assertNotNull(predecessor);
            assertEquals(SelfRun3Engine.Stage.RECONCILING, predecessor.stage());
            assertTrue(predecessor.hasResult());
            assertTrue(SelfRun3SuccessorTransitionPolicy.armed(predecessor));
            assertEquals("ACCEPTED", predecessor.successorTransition().optString("stage"));
            assertEquals(0, predecessor.successorTransition().optInt("recoveryAttempt"));

            SelfRun3RuntimeTestBridge.installScenario(
                    TASK, PREDECESSOR_TURN, predecessor.text("result"), false);

            Intent deadlineWake = new Intent(context, SelfRunService.class)
                    .setAction(SelfRunService.ACTION_SUCCESSOR_WATCHDOG_WAKE)
                    .putExtra(SelfRun3SuccessorWakeScheduler.EXTRA_PREDECESSOR_TURN_ID,
                            PREDECESSOR_TURN)
                    .putExtra(SelfRun3SuccessorWakeScheduler.EXTRA_RECOVERY_ATTEMPT, 0);
            startServiceAction(context, deadlineWake);

            await("process-restarted service did not recover to canonical successor POST",
                    20_000L, () -> {
                        SelfRun3Engine.State root = ledger.load(TASK);
                        SelfRun3Engine.State successor =
                                root == null ? null : root.execution(TASK + ":turn:2");
                        return successor != null
                                && successor.stage() == SelfRun3Engine.Stage.WAITING
                                && !SelfRun3SuccessorTransitionPolicy.armed(successor)
                                && "CANONICAL_CONFIRMED".equals(
                                successor.successorTransition().optString("stage"))
                                && "https://chatgpt.com/c/runtime-successor".equals(
                                successor.resource("conversationUrl"));
                    });

            SelfRun3Engine.State recoveredRoot = ledger.load(TASK);
            SelfRun3Engine.State source = recoveredRoot.execution(PREDECESSOR_TURN);
            SelfRun3Engine.State successor = recoveredRoot.execution(TASK + ":turn:2");
            assertNotNull(source);
            assertNotNull(successor);
            assertTrue(source.flag("committed"));
            assertEquals(2, recoveredRoot.number("maxTurn"));
            assertEquals(TASK + ":turn:2", successor.turnId());
            assertEquals(TASK + ":turn:2-request", successor.requestId());
            assertEquals(source.resource("resultDocumentId"),
                    successor.successorTransition().optString("predecessorResultDocumentId"));
            assertTrue(successor.successorTransition().optInt("recoveryAttempt") >= 1);
            assertEquals("fixture_result_2", successor.resource("resultDocumentId"));
            assertEquals("https://chatgpt.com/c/runtime-successor",
                    successor.resource("conversationUrl"));

            // The new process must recover from the persisted accepted predecessor without
            // reading or revalidating that predecessor Result again.
            assertEquals(0, SelfRun3RuntimeTestBridge.resultReadCount());
            assertEquals(1, SelfRun3RuntimeTestBridge.resultDocumentCreationCount());
            assertEquals(1, SelfRun3RuntimeTestBridge.canonicalPostConfirmationCount());
        } finally {
            context.stopService(new Intent(context, SelfRunService.class));
            SystemClock.sleep(250L);
            reset(context);
        }
    }

    private static SelfRun3Engine.State runtimeWaitingState(Context context) {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", SelfRunScript.GENERAL_CHAT_URL);
        SelfRun3Engine.put(config, "requirement",
                "runtime successor process restart regression");
        SelfRun3Engine.put(config, "accountId", "acct_123");
        SelfRun3Engine.put(config, "baseFolderId", "abcdefgh");
        SelfRun3Engine.put(config, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "chatBootstrap", "medium");

        SelfRun3Engine.State state =
                SelfRun3Engine.create(TASK, PREDECESSOR_TURN, config);
        state = pureResource(state, "folderId", "runtime_folder");
        state = pureResource(state, "requirementDocumentId", "runtime_requirement");
        state = pureReduce(state, "runtime-setup",
                SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = pureResource(state, "resultDocumentId", "runtime_predecessor_result");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "runtime predecessor");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = pureReduce(state, "runtime-ready",
                SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject();
        SelfRun3Engine.put(claim, "at", System.currentTimeMillis());
        state = pureReduce(state, "runtime-claim",
                SelfRun3Engine.Kind.CLAIM_SEND, claim);
        state = pureResource(state, "conversationUrl",
                "https://chatgpt.com/c/runtime-predecessor");

        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", state.requestId());
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.put(started, "atElapsed", SystemClock.elapsedRealtime());
        SelfRun3Engine.put(started, "atWall", System.currentTimeMillis());
        SelfRun3Engine.put(started, "bootCount", currentBootCount(context));
        return pureReduce(state, "runtime-started",
                SelfRun3Engine.Kind.STARTED, started);
    }

    private static String committedContinueResult(SelfRun3Engine.State state) {
        JSONObject result = SelfRun3Engine.emptyResult(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "phase_completed", "WORK_PARTIAL");
        SelfRun3Engine.put(result, "next_phase", "WORK");
        JSONObject profile = profile();
        JSONObject next = new JSONObject();
        SelfRun3Engine.put(next, "type", "SERIAL");
        SelfRun3Engine.put(next, "objective", "runtime successor process restart regression");
        SelfRun3Engine.put(next, "profile", profile);
        SelfRun3Engine.put(result, "next_execution", next);
        SelfRun3Engine.put(result, "next_profile", profile);
        return result.toString();
    }

    private static JSONObject profile() {
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(profile, "reasoning", "medium");
        return profile;
    }

    private static void startServiceAction(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static void await(String message, long timeoutMs, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    private static int currentBootCount(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Throwable unavailable) {
            return 0;
        }
    }

    private static SelfRun3Engine.State pureResource(
            SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return pureReduce(state, "resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State pureReduce(
            SelfRun3Engine.State state, String eventId,
            SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(
                        eventId, kind, state.taskId(), state.turnId(), payload));
    }

    private static void reset(Context context) {
        context.stopService(new Intent(context, SelfRunService.class));
        SelfRun3RuntimeTestBridge.clear();
        ProfileRegistry.resetForTests();
        deleteLedgerFiles(context);
        new SelfRunStore(context).clear();
        context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_profile_registry", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ProfileRegistry.initialize(context);
    }

    private static void deleteLedgerFiles(Context context) {
        File ledger = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db");
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(ledger.getAbsolutePath() + suffix);
            if (file.exists()) assertTrue("failed to delete " + file, file.delete());
        }
    }
}
