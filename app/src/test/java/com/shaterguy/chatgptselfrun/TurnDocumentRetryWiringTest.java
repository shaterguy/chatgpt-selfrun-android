package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** V3 result recovery is pinned-document reconciliation, never a title-signal retry cycle. */
public final class TurnDocumentRetryWiringTest {
    @Test public void resultReadRetriesAreFiniteAndThenOneRepairRequestIsAllowed() throws Exception {
        assertEquals(5_000L, SelfRun3PowerPolicy.resultRetryDelay(0));
        assertEquals(90_000L, SelfRun3PowerPolicy.resultRetryDelay(4));
        assertEquals(-1L, SelfRun3PowerPolicy.resultRetryDelay(5));
        String coordinator = src("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("scheduleResultRetry"));
        assertTrue(coordinator.contains("repairResult(state)"));
        assertTrue(coordinator.contains("current.number(\"repairAttempt\") != 0"));
        assertTrue(coordinator.contains("V3_RESULT_REPAIR_EXHAUSTED"));
    }

    @Test public void repairCreatesFreshRequestWithoutChangingLogicalTurnOrResultDocument() throws Exception {
        String engine = src("SelfRun3Engine.java");
        String protocol = src("SelfRun3Protocol.java");
        String coordinator = src("SelfRun3Coordinator.java");
        assertTrue(engine.contains("case REPAIR"));
        assertTrue(engine.contains("!before.requestId().equals(nextRequest)"));
        assertTrue(engine.contains("put(v, \"requestId\", nextRequest)"));
        assertFalse(between(engine, "case REPAIR", "case PAUSE").contains("put(v, \"turnId\""));
        assertTrue(protocol.contains("static String repair(SelfRun3Engine.State s, String nextRequestId)"));
        assertTrue(protocol.contains("RESULT_REPAIR=1"));
        assertTrue(coordinator.contains("SelfRun3Protocol.repair(current, repairRequest)"));
    }

    @Test public void malformedOrPartiallyWrittenResultIsPendingInsteadOfBlindlyCreatingAnotherDocument() throws Exception {
        String drive = src("SelfRun3DriveAdapter.java");
        assertTrue(drive.contains("SelfRun3DriveLookup.findSingleDocumentId"));
        assertTrue(drive.contains("DOCUMENT_CREATE_UNCONFIRMED"));
        assertTrue(drive.contains("return SelfRun3Engine.emptyResult(s).toString()"));
        assertFalse(drive.contains("SELF_RUN_TURN_DOCUMENT_RETRY"));
        assertFalse(drive.contains("DriveSignalParser"));
    }

    @Test public void repairDoesNotConsumeLateUserInputReservation() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        String repair = between(coordinator, "private void repairResult", "private void commitTurn");
        assertFalse(repair.contains("SelfRun3UserInput.consumeIfRevision"));
        assertFalse(repair.contains("SelfRun3UserInput.snapshot"));
        String commit = between(coordinator, "private void commitTurn", "private void scheduleNetworkRetry");
        assertTrue(commit.contains("SelfRun3UserInput.Snapshot latest"));
        assertTrue(commit.contains("lateInput"));
    }

    @Test public void repairPromptIsCompactAndContainsNoRetiredSignalContract() throws Exception {
        String protocol = src("SelfRun3Protocol.java");
        assertTrue(protocol.contains("SELF_RUN_SKILL_DOCUMENT_ID"));
        assertTrue(protocol.contains("RESULT_REPAIR=1"));
        assertFalse(protocol.contains("static final String CONTRACT"));
        assertFalse(protocol.contains("SELF_RUN_TURN_COMPLETED"));
        assertFalse(protocol.contains("[SELF_RUN_TURN_DOCUMENT_RETRY "));
        assertFalse(protocol.contains("RESULT_IDENTITY_TEMPLATE"));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
