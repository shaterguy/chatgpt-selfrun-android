package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRunRolloverWiringTest {
    @Test public void conversationLocalRoutesUseRollover() throws Exception {
        String service=src("SelfRunService.java");
        String launch=between(service,"private void launchWebView","private boolean isTurnCompletionCallback");
        assertTrue(launch.contains("rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH)"));
        assertTrue(launch.contains("rolloverConversation(SelfRunRolloverPolicy.RENDERER_CRASH)"));
        assertTrue(launch.contains("detail.didCrash()"));
        String step=between(service,"private void runWebStep","private String ensureTurnProtocolToken");
        assertTrue(step.contains("rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH)"));
        assertFalse(step.contains("restoreCanonical()"));
    }
    @Test public void predecessorLateCallbacksAreFencedByRunAndEpoch() throws Exception {
        String service=src("SelfRunService.java");
        assertTrue(service.contains("epoch != automationEpoch"));
        assertTrue(service.contains("runId.equals(store.runId())"));
        assertTrue(service.contains("driveOperationRunId.equals(store.runId())"));
        assertTrue(service.contains("stopAutomationCallbacks();"));
        assertTrue(service.contains("cleanupWebView();"));
    }
    @Test public void pausePrecedesPendingRolloverAndPausedStickyRunDoesNotResumeIt() throws Exception {
        String service=src("SelfRunService.java");
        String start=between(service,"@Override public int onStartCommand","private void startForegroundCompat");
        assertTrue(start.indexOf("ACTION_PAUSE.equals(action)") < start.indexOf("rollover.hasPendingClaim()"));
        assertTrue(start.contains("if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }"));
        assertTrue(start.contains("resumePendingRollover = ACTION_RESUME.equals(action) && rollover.hasPendingClaim()"));
    }

    @Test public void continuationFailureEvidenceIsNotClearedBeforeClassification() throws Exception {
        String service=src("SelfRunService.java");
        assertFalse(service.contains("if(isContinuationDiagnosticPhase(phase))rollover.clearLocalFailures(runId)"));
        assertTrue(service.contains("shouldCountContinuationFailure(status,store.phaseStartedAt(),System.currentTimeMillis())"));
        assertTrue(service.contains("rollover.recordLocalFailure(runId,status)"));
        assertTrue(service.contains("CONTINUATION_NO_PROGRESS"));
        String coordinator=src("SelfRunRolloverCoordinator.java");
        assertTrue(coordinator.contains("ChatPickerStateStore.effectiveForRun"));
        assertTrue(coordinator.contains("chatPickerSelection"));
    }

    @Test public void successorBootstrapCarriesPredecessorReferences() throws Exception {
        String coordinator=src("SelfRunRolloverCoordinator.java");
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_RUN_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_JOB_FOLDER_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_ORIGINAL_REQUIREMENT_STORED="));
        assertTrue(coordinator.contains("predecessorOriginalStored"));
        assertTrue(coordinator.contains("predecessor turn document 본문을 원문 요구사항으로 해석하지 않는다"));
        assertTrue(coordinator.contains("특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고"));
    }
    private static String src(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);}
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x+a.length());assertTrue(x>=0);assertTrue(y>x);return s.substring(x,y);}
}
