package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRunStoppedResumePolicyTest {
    @Test public void stoppedResumeIsExplicitWithoutRequiringLedgerStopProof() throws Exception {
        String engine = src("SelfRun3Engine.java");
        String coordinator = src("SelfRun3Coordinator.java");
        String resume = src("SelfRunStoppedResume.java");
        assertTrue(engine.contains("RESUME_STOPPED"));
        assertTrue(engine.contains("original.flag(\"taskStopped\") && e.kind != Kind.RESUME_STOPPED"));
        assertTrue(engine.contains("put(root,\"taskStopped\",false)"));
        assertTrue(engine.contains("resumeWaitsForPinnedResult"));
        assertTrue(engine.contains("stoppedResumeWait"));
        assertTrue(coordinator.contains("shouldReadStoppedResumePinnedResult"));
        assertTrue(coordinator.contains("execution.flag(\"stoppedResumeWait\")"));
        assertTrue(coordinator.contains("runDriveStep(execution, DriveStep.READ_RESULT, ReadTrigger.AUTHORITY, null)"));
        assertTrue(coordinator.contains("SystemClock.elapsedRealtime() + runtimeSettings.resultPollMs()"));
        assertFalse(engine.contains("if(!original.flag(\"taskStopped\")) return original"));
        assertFalse(engine.contains("STOPPED_DRIVE_READ_REQUIRED"));
        assertFalse(engine.contains("STOPPED_SNAPSHOT_CHANGED"));
        assertFalse(resume.contains("STOPPED_STATE_REQUIRED"));
    }

    @Test public void historyOnlyOffersUserStoppedResumeAndServiceOwnsRecovery() throws Exception {
        String history = src("SelfRunHistoryActivity.java");
        String detail = src("SelfRunDetailActivity.java");
        String service = src("SelfRunService.java");
        String main = src("MainActivity.java");
        String coordinator = src("SelfRun3Coordinator.java");
        assertTrue(history.contains("SelfRunStoppedResume.isEligible(item)"));
        assertTrue(detail.contains("SelfRunStoppedResume.isEligible(item)"));
        assertTrue(history.contains("중지된 작업 재개"));
        assertTrue(detail.contains("중지된 작업 재개"));
        assertTrue(service.contains("ACTION_RESUME_STOPPED"));
        assertTrue(service.contains("stoppedResume.hasPending() ? ACTION_RESUME_STOPPED : ACTION_RUN"));
        assertTrue(service.contains("stoppedResume.resumePending()"));
        assertTrue(main.contains("sendRunnerAction(SelfRunService.ACTION_STOP)"));
        assertFalse(main.contains("stopService(new Intent(this, SelfRunService.class))"));
        assertTrue(coordinator.contains("web.quiesce()"));
    }

    @Test public void recoveryReusesExistingLedgerAndPinnedDriveIdentity() throws Exception {
        String resume = src("SelfRunStoppedResume.java");
        assertTrue(resume.contains("ledger.load(target)"));
        assertTrue(resume.contains("config.optString(\"accountId\").equals(store.driveAccountId())"));
        assertTrue(resume.contains("config.optString(\"baseFolderId\").equals(store.driveRunsBaseFolderId())"));
        assertTrue(resume.contains("SelfRun3Engine.Kind.RESUME_STOPPED"));
        assertTrue(resume.contains("coordinator.onStoppedResumeAuthorized(token)"));
        assertTrue(resume.contains("coordinator.onStart(SelfRunService.ACTION_RUN)"));
        assertFalse(resume.contains("generateFolderId"));
        assertFalse(resume.contains("conversationUrl") && resume.contains("ACTION_VIEW"));
    }

    @Test public void competingActiveTasksAndDriveMismatchAreRejectedBeforeRecovery() throws Exception {
        String resume = src("SelfRunStoppedResume.java");
        assertFalse(resume.contains("TASK_ALREADY_DONE"));
        assertFalse(resume.contains("SelfRun3StoppedRecovery.plan"));
        assertFalse(resume.contains("STOPPED_RESULT_UNREADABLE"));
        assertTrue(resume.contains("DriveAuthorization.requestSilently"));
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
