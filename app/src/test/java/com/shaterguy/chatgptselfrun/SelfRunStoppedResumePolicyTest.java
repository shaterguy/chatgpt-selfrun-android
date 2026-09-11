package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRunStoppedResumePolicyTest {
    @Test public void stoppedResumeIsExplicitAndNormalResumeCannotBypassStopLatch() throws Exception {
        String engine = src("SelfRun3Engine.java");
        assertTrue(engine.contains("RESUME_STOPPED"));
        assertTrue(engine.contains("original.flag(\"taskStopped\") && e.kind != Kind.RESUME_STOPPED"));
        assertTrue(engine.contains("if(!original.flag(\"taskStopped\")) return original"));
        assertTrue(engine.contains("put(v,\"taskStopped\",false)"));
    }

    @Test public void historyOnlyOffersUserStoppedResumeAndServiceOwnsRecovery() throws Exception {
        String history = src("SelfRunHistoryActivity.java");
        String detail = src("SelfRunDetailActivity.java");
        String service = src("SelfRunService.java");
        assertTrue(history.contains("SelfRunStoppedResume.isEligible(item)"));
        assertTrue(detail.contains("SelfRunStoppedResume.isEligible(item)"));
        assertTrue(history.contains("중지된 작업 재개"));
        assertTrue(detail.contains("중지된 작업 재개"));
        assertTrue(service.contains("ACTION_RESUME_STOPPED"));
        assertTrue(service.contains("stoppedResume.hasPending() ? ACTION_RESUME_STOPPED : ACTION_RUN"));
        assertTrue(service.contains("stoppedResume.resumePending()"));
    }

    @Test public void recoveryReusesExistingLedgerAndPinnedDriveIdentity() throws Exception {
        String resume = src("SelfRunStoppedResume.java");
        assertTrue(resume.contains("ledger.load(target)"));
        assertTrue(resume.contains("config.optString(\"accountId\").equals(store.driveAccountId())"));
        assertTrue(resume.contains("config.optString(\"baseFolderId\").equals(store.driveRunsBaseFolderId())"));
        assertTrue(resume.contains("SelfRun3Engine.Kind.RESUME_STOPPED"));
        assertTrue(resume.contains("coordinator.onStart(SelfRunService.ACTION_RUN)"));
        assertFalse(resume.contains("generateFolderId"));
        assertFalse(resume.contains("conversationUrl") && resume.contains("ACTION_VIEW"));
    }

    @Test public void doneAndCompetingActiveTasksAreRejectedBeforeRecovery() throws Exception {
        String resume = src("SelfRunStoppedResume.java");
        assertTrue(resume.contains("TASK_ALREADY_DONE"));
        assertTrue(resume.contains("ANOTHER_RUN_ACTIVE"));
        assertTrue(resume.contains("DRIVE_BINDING_MISMATCH"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
