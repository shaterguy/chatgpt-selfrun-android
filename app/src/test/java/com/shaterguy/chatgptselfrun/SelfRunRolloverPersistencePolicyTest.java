package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Structural regression checks for the durable predecessor -> successor handoff. */
public final class SelfRunRolloverPersistencePolicyTest {
    @Test public void successorIdIsReservedAndPersistedBeforePredecessorTerminalFence() throws Exception {
        String source = src("SelfRunRolloverCoordinator.java");
        String begin = section(source, "synchronized Result beginOrResume", "synchronized Result resumePending");
        int generate = begin.indexOf("String successorRunId = SelfRunRunId.create()");
        int claimField = begin.indexOf("next.put(\"successorRunId\", successorRunId)");
        int claimCommit = begin.indexOf("prefs.edit().putString(CURRENT, next.toString()).commit()");
        int startClaimed = begin.indexOf("return startClaimedSuccessor(store, next)");
        assertTrue(generate >= 0 && claimField > generate && claimCommit > claimField && startClaimed > claimCommit);

        String start = section(source, "private Result startClaimedSuccessor", "private boolean markPredecessorTerminal");
        int terminal = start.indexOf("markPredecessorTerminal(store, successorRunId, cause)");
        int successorStart = start.indexOf("store.start(successorRunId");
        assertTrue(terminal >= 0 && successorStart > terminal);
    }

    @Test public void processRestartResumesThePersistedSuccessorInsteadOfAllocatingAnother() throws Exception {
        String source = src("SelfRunRolloverCoordinator.java");
        String resume = section(source, "synchronized Result resumePending", "private Result startClaimedSuccessor");
        assertTrue(resume.contains("JSONObject existing = claim()"));
        assertTrue(resume.contains("return startClaimedSuccessor(store, existing)"));
        assertFalse(resume.contains("SelfRunRunId.create()"));

        String start = section(source, "private Result startClaimedSuccessor", "private boolean markPredecessorTerminal");
        assertTrue(start.contains("if (successorRunId.equals(store.runId()))"));
        assertTrue(start.contains("RESULT_ALREADY_STARTED"));
    }

    @Test public void rolloverJournalStoresReferencesAndNotRawRequirement() throws Exception {
        String source = src("SelfRunRolloverCoordinator.java");
        String claim = section(source, "String successorRunId = SelfRunRunId.create()", "return startClaimedSuccessor(store, next)");
        assertTrue(claim.contains("predecessorJobFolderId"));
        assertTrue(claim.contains("predecessorTurnDocumentId"));
        assertTrue(claim.contains("predecessorOriginalRequirementStored"));
        assertFalse(claim.contains("next.put(\"requirement\""));
        assertFalse(claim.contains("next.put(\"rawRequirement\""));
    }

    @Test public void sameCauseIsGuardedBeforeASecondSuccessorIdIsCreated() throws Exception {
        String source = src("SelfRunRolloverCoordinator.java");
        String begin = section(source, "synchronized Result beginOrResume", "synchronized Result resumePending");
        int guard = begin.indexOf("SelfRunRolloverPolicy.containsCause(priorCauses, cause)");
        int generate = begin.indexOf("String successorRunId = SelfRunRunId.create()");
        assertTrue(guard >= 0 && generate > guard);
        assertTrue(begin.contains("RESULT_LOOP_GUARD"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int a = source.indexOf(start);
        int b = source.indexOf(end, Math.max(0, a + start.length()));
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return source.substring(a, b);
    }
}
