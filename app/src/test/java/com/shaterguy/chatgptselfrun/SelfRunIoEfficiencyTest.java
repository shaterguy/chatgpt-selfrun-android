package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunIoEfficiencyTest {
    @Test
    public void longStableWatchdogCollapsesOneThousandRawLogsIntoSamples() {
        SelfRunLogSampler sampler = new SelfRunLogSampler();
        SelfRunLogSampler.Context context = new SelfRunLogSampler.Context(7, 11, "wv-a", "AUTOMATION");
        List<SelfRunLogSampler.Emission> emitted = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            emitted.addAll(sampler.accept("DOM_WATCHDOG_HEALTH",
                    "count=" + (i + 1) + ";observer=alive;suppressed=0",
                    SelfRunStore.PHASE_WAIT_ASSISTANT, context, i * 100L));
        }
        emitted.addAll(sampler.flush("state_transition", 100_000L));

        assertTrue("dev5-style individual logs would be 1000", emitted.size() <= 4);
        assertTrue("sampling must cut the stable-path log count by >99%", emitted.size() * 100 < 1_000);
        assertTrue(emitted.stream().anyMatch(item -> item.abnormalBurst));
        assertTrue(emitted.stream().anyMatch(item -> item.summary && item.repeatCount == 1_000L));
        SelfRunLogSampler.Metrics metrics = sampler.metrics();
        assertEquals(1_000L, metrics.repeatableCalls);
        assertEquals(999L, metrics.aggregatedRepeats);
    }

    @Test
    public void staleCallbackBurstKeepsFirstBurstAndFinalSummary() {
        SelfRunLogSampler sampler = new SelfRunLogSampler();
        SelfRunLogSampler.Context context = new SelfRunLogSampler.Context(9, 20, "wv-stale", "AUTOMATION");
        List<SelfRunLogSampler.Emission> emitted = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            emitted.addAll(sampler.accept("STALE_CALLBACK", "source=observer_message",
                    SelfRunStore.PHASE_WAIT_ASSISTANT, context, i * 20L));
        }
        emitted.addAll(sampler.flush("pause", 2_500L));

        assertTrue(emitted.size() <= 3);
        assertFalse(emitted.get(0).summary);
        assertTrue(emitted.stream().anyMatch(item -> item.abnormalBurst));
        assertTrue(emitted.stream().anyMatch(item -> item.summary && item.repeatCount == 100L));
    }

    @Test
    public void errorKindAndExecutionContextChangesAreNeverMergedTogether() {
        SelfRunLogSampler sampler = new SelfRunLogSampler();
        SelfRunLogSampler.Context first = new SelfRunLogSampler.Context(1, 2, "wv-a", "AUTOMATION");
        SelfRunLogSampler.Context nextGeneration = new SelfRunLogSampler.Context(2, 3, "wv-b", "RECOVERY");

        assertEquals(1, sampler.accept("WEBVIEW_ERROR", "type=network;code=-2",
                "BOOTSTRAP", first, 0L).size());
        assertEquals(0, sampler.accept("WEBVIEW_ERROR", "type=network;code=-2",
                "BOOTSTRAP", first, 100L).size());
        assertEquals(1, sampler.accept("WEBVIEW_ERROR", "type=ssl",
                "BOOTSTRAP", first, 200L).size());
        assertEquals(1, sampler.accept("WEBVIEW_ERROR", "type=network;code=-2",
                "BOOTSTRAP", nextGeneration, 300L).size());
    }

    @Test
    public void volatileCountersDoNotDefeatCauseBasedAggregation() {
        assertEquals("count=*;observer=alive;suppressed=*",
                SelfRunLogSampler.normalizeDetail("count=999;observer=alive;suppressed=42"));
        assertEquals("source=request;trigger=observer_state",
                SelfRunLogSampler.normalizeDetail("source=request;trigger=observer_state"));
    }

    @Test
    public void runtimeLoggingAndHistoryPersistenceAvoidHighFrequencySynchronousIo() throws Exception {
        String runLog = source("SelfRunRunLog.java");
        String history = source("SelfRunHistoryStore.java");
        String store = source("SelfRunStore.java");
        String service = source("SelfRunService.java");

        assertTrue(runLog.contains("ScheduledExecutorService"));
        assertTrue(runLog.contains("WRITE_BATCH_MS = 250L"));
        assertTrue(runLog.contains("REPEAT_SUMMARY"));
        assertTrue(runLog.contains("first_occurrence_kst"));
        assertTrue(runLog.contains("last_occurrence_kst"));
        assertTrue(runLog.contains("repeat_count"));
        assertTrue(runLog.contains("generation"));
        assertTrue(runLog.contains("observer_epoch"));
        assertTrue(runLog.contains("wakelock_state"));
        assertFalse(runLog.contains("output.flush()"));

        assertTrue(history.contains("SYNC_DEBOUNCE_MS = 250L"));
        assertTrue(history.contains("pendingSnapshots"));
        assertTrue(history.contains("syncCritical"));
        assertTrue(history.contains("physicalWrites"));
        assertFalse(history.contains(".commit()"));
        assertTrue(history.contains(".apply()"));

        assertTrue(store.contains("setPhaseAndStatus"));
        assertTrue(store.contains("enterPausedState"));
        assertTrue(store.contains("enterErrorPausedState"));
        assertTrue(store.contains("resumeState"));
        assertTrue(store.contains("complete("));
        assertTrue(store.contains("stopByUser"));

        assertTrue(service.contains("STALE_CALLBACK"));
        assertTrue(service.contains("DOM_OBSERVER_DUPLICATE"));
        assertTrue(service.contains("DOM_EVALUATION_COALESCED"));
        assertTrue(service.contains("DOM_WATCHDOG_SKIPPED"));
        assertTrue(service.contains("IO_EFFICIENCY"));
        assertTrue(service.contains("updateLogExecutionContext"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
