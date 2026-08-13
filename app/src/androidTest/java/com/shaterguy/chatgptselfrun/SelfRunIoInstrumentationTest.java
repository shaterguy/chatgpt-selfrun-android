package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SelfRunIoInstrumentationTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Thread.sleep(SelfRunHistoryStore.SYNC_DEBOUNCE_MS + 100L);
        context.getSharedPreferences("selfrun", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_history", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void criticalTerminalSnapshotCannotBeOverwrittenByOlderPendingSnapshot() throws Exception {
        String runId = newRunId("history-order");
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "history ordering");
        waitForAsyncWrites();

        store.setConversationUrl(conversationUrl());
        store.setPhaseAndStatus(SelfRunStore.PHASE_WAIT_ASSISTANT, "waiting");
        SelfRunHistoryStore separateActivityHistory = new SelfRunHistoryStore(context);
        separateActivityHistory.sync(store);

        store.stopByUser();
        waitForAsyncWrites();

        JSONObject restored = new SelfRunHistoryStore(context).get(runId);
        assertNotNull(restored);
        assertTrue(restored.optBoolean("terminal"));
        assertTrue(restored.optBoolean("userStopped"));
        assertFalse(restored.optBoolean("active"));
        assertEquals(SelfRunStore.PHASE_IDLE, restored.optString("phase"));
    }

    @Test
    public void rapidHistoryChangesCoalesceIntoFarFewerWriteTransactions() throws Exception {
        String runId = newRunId("history-coalesce");
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "coalescing");
        waitForAsyncWrites();

        SelfRunHistoryStore.Metrics before = store.persistenceMetrics().history;
        for (int i = 0; i < 100; i++) store.setStatus("rapid-" + i);
        waitForAsyncWrites();
        SelfRunHistoryStore.Metrics after = store.persistenceMetrics().history;

        long requests = after.syncRequests - before.syncRequests;
        long coalesced = after.coalescedRequests - before.coalescedRequests;
        long writes = after.physicalWrites - before.physicalWrites;
        assertEquals(100L, requests);
        assertTrue("most rapid history requests should coalesce", coalesced >= 95L);
        assertTrue("100 logical history changes should require only a few SharedPreferences writes", writes <= 3L);
    }

    @Test
    public void repeatedIdenticalPrimaryStateDoesNotCreateMoreStateWrites() throws Exception {
        String runId = newRunId("state-noop");
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "no-op writes");
        store.setStatus("stable");
        SelfRunStore.PersistenceMetrics before = store.persistenceMetrics();

        for (int i = 0; i < 1_000; i++) store.setStatus("stable");
        SelfRunStore.PersistenceMetrics after = store.persistenceMetrics();

        assertEquals(before.stateWriteTransactions, after.stateWriteTransactions);
        assertEquals(1_000L, after.duplicateStateWritesSkipped - before.duplicateStateWritesSkipped);
    }

    @Test
    public void userActionPauseRecoversFromNewStoreInstanceAtContinue() throws Exception {
        assertProtocolPauseRoundTrip(true);
    }

    @Test
    public void selfRunPauseRecoversFromNewStoreInstanceAtContinue() throws Exception {
        assertProtocolPauseRoundTrip(false);
    }

    @Test
    public void manualPauseRecoversFromNewStoreInstanceAtOriginalPhase() throws Exception {
        String runId = newRunId("manual-pause");
        SelfRunStore store = preparedConversationStore(runId);
        store.setLastSignal("NEXT");
        store.enterPausedState("manual paused");

        SelfRunStore recovered = new SelfRunStore(context);
        assertTrue(recovered.paused());
        assertEquals(SelfRunStore.PHASE_PAUSED, recovered.phase());
        assertEquals(SelfRunStore.PHASE_WAIT_ASSISTANT, recovered.pauseResumePhase());

        recovered.resumeState(SelfRunStore.PHASE_SEND_CONTINUE, "manual resumed");
        SelfRunStore afterResume = new SelfRunStore(context);
        assertFalse(afterResume.paused());
        assertEquals(SelfRunStore.PHASE_WAIT_ASSISTANT, afterResume.phase());
        assertFalse(afterResume.conversationUrl().isEmpty());
    }

    @Test
    public void watchdogAndStaleBurstsProduceFewActualLogLinesAndWriteBatches() throws Exception {
        String runId = newRunId("log-burst");
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "log burst");
        SelfRunRunLog log = new SelfRunRunLog(context);
        SelfRunRunLog.Metrics before = log.metrics();

        for (int i = 0; i < 1_000; i++) {
            log.record(store, "DOM_WATCHDOG_HEALTH",
                    "count=" + (i + 1) + ";observer=alive;suppressed=0");
        }
        log.record(store, "STATE_TRANSITION", "from=WAIT_ASSISTANT;to=WAIT_ASSISTANT;reason=test_flush");

        for (int i = 0; i < 100; i++) {
            log.record(store, "STALE_CALLBACK", "source=observer_message");
        }
        log.record(store, "STATE_TRANSITION", "from=WAIT_ASSISTANT;to=WAIT_ASSISTANT;reason=stale_flush");

        List<String> lines = log.readDebug(runId, 2_000);
        int watchdogEvidence = countEventOrSummary(lines, "DOM_WATCHDOG_HEALTH");
        int staleEvidence = countEventOrSummary(lines, "STALE_CALLBACK");
        SelfRunRunLog.Metrics after = log.metrics();

        assertTrue("1000 stable watchdog checks should collapse to <=4 log lines", watchdogEvidence <= 4);
        assertTrue("100 stale callbacks should collapse to <=3 log lines", staleEvidence <= 3);
        assertEquals(1_100L, after.repeatableCalls - before.repeatableCalls);
        assertEquals(1_098L, after.aggregatedRepeats - before.aggregatedRepeats);
        assertTrue("actual file append batches should remain far below raw callbacks",
                after.writeBatches - before.writeBatches < 20L);
    }

    @Test
    public void repeatedWebViewErrorsRemainIndividuallyVisible() {
        String runId = newRunId("webview-error");
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "webview errors");
        SelfRunRunLog log = new SelfRunRunLog(context);

        log.record(store, "WEBVIEW_ERROR", "type=network;code=-2");
        log.record(store, "WEBVIEW_ERROR", "type=network;code=-2");
        log.record(store, "WEBVIEW_ERROR", "type=ssl");

        List<String> lines = log.readDebug(runId, 100);
        int errors = 0;
        for (String raw : lines) {
            try {
                if ("WEBVIEW_ERROR".equals(new JSONObject(raw).optString("event"))) errors++;
            } catch (Exception ignored) {
            }
        }
        assertEquals(3, errors);
    }

    private void assertProtocolPauseRoundTrip(boolean userAction) {
        String runId = newRunId(userAction ? "user-action" : "protocol-pause");
        SelfRunStore store = preparedConversationStore(runId);
        String signal = userAction
                ? "[SELF_RUN_USER_ACTION_REQUIRED " + runId + " AUTH]"
                : "[SELF_RUN_PAUSE " + runId + " REASON=WAIT]";
        store.setLastSignal(signal);
        store.enterPausedState("protocol paused");

        SelfRunStore recovered = new SelfRunStore(context);
        assertTrue(recovered.paused());
        assertEquals(SelfRunStore.PHASE_PAUSED, recovered.phase());
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE, recovered.pauseResumePhase());
        assertFalse(recovered.conversationUrl().isEmpty());

        recovered.resumeState(SelfRunStore.PHASE_SEND_CONTINUE, "protocol resumed");
        SelfRunStore afterResume = new SelfRunStore(context);
        assertFalse(afterResume.paused());
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE, afterResume.phase());
        assertFalse(afterResume.conversationUrl().isEmpty());
    }

    private SelfRunStore preparedConversationStore(String runId) {
        SelfRunStore store = new SelfRunStore(context);
        store.start(runId, SelfRunStore.MODE_CHAT, projectUrl(), "pause recovery");
        store.setConversationUrl(conversationUrl());
        store.setPhaseAndStatus(SelfRunStore.PHASE_WAIT_ASSISTANT, "waiting");
        return store;
    }

    private static int countEventOrSummary(List<String> lines, String sourceEvent) {
        int count = 0;
        for (String raw : lines) {
            try {
                JSONObject item = new JSONObject(raw);
                String event = item.optString("event");
                if (sourceEvent.equals(event)) {
                    count++;
                } else if ("REPEAT_SUMMARY".equals(event)
                        && item.optString("detail").contains("source_event=" + sourceEvent)) {
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    private static void waitForAsyncWrites() throws InterruptedException {
        Thread.sleep(SelfRunHistoryStore.SYNC_DEBOUNCE_MS + 400L);
    }

    private static String newRunId(String prefix) {
        return "SR-test-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String projectUrl() {
        return "https://chatgpt.com/g/g-p-selfrun-test/project";
    }

    private static String conversationUrl() {
        return "https://chatgpt.com/c/" + UUID.randomUUID();
    }
}
