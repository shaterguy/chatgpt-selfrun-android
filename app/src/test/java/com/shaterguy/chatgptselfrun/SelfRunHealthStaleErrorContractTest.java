package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunHealthStaleErrorContractTest {
    @Test public void staleRetryCodeCannotOverrideNewPhase() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_WAIT_TURN_COMPLETION);
        in.phaseStartedAt = 5_000L;
        in.lastErrorCode = "DRIVE_OPERATION_RETRY";
        in.lastErrorObservedAt = 3_000L;
        in.status = "ChatGPT 응답 대기";
        SelfRunHealthSnapshot h = SelfRunHealthEvaluator.evaluate(in, 10_000L);
        assertEquals(SelfRunHealthSnapshot.WAITING, h.level);
        assertEquals("WAITING_CHATGPT", h.category);
    }

    @Test public void freshRetryCodeStillShowsRecovery() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC);
        in.phaseStartedAt = 2_000L;
        in.lastErrorCode = "DRIVE_OPERATION_RETRY";
        in.lastErrorObservedAt = 3_000L;
        in.status = "Drive 요청을 자동 재시도합니다. · 자동 재시도 대기";
        SelfRunHealthSnapshot h = SelfRunHealthEvaluator.evaluate(in, 10_000L);
        assertEquals(SelfRunHealthSnapshot.RECOVERING, h.level);
    }

    @Test public void observationStoreTracksErrorChangeWithoutRuntimeMutation() throws Exception {
        String store = source("SelfRunHealthObservationStore.java");
        assertTrue(store.contains("lastErrorObservedAt"));
        assertTrue(store.contains("errorObservationInitialized"));
        assertTrue(store.contains("observeErrorState"));
        assertFalse(store.contains("store.clearLastError"));
        assertFalse(store.contains("store.setLastError"));
    }

    private static SelfRunHealthInput base(String phase) {
        SelfRunHealthInput in = new SelfRunHealthInput();
        in.runId = "run-test";
        in.phase = phase;
        in.mode = SelfRunStore.MODE_CHAT;
        in.status = "정상";
        in.createdAt = 1_000L;
        in.phaseStartedAt = 2_000L;
        in.updatedAt = 1_500L;
        in.active = true;
        return in;
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
