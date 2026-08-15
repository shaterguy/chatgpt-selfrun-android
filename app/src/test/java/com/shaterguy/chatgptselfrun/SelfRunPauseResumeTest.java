package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunPauseResumeTest {
    @Test public void manualResumeReconcilesDriveBeforeContinuing() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String resume = between(service, "private void resumeFromUi", "private void enterPreservedPause");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(resume.contains("beginManualResumeOverride"));
        assertTrue(store.contains("PHASE_RESUME_BASELINE"));
        assertTrue(baseline.contains("DriveResumePolicy.decide"));
        assertTrue(baseline.contains("APPLY_COMPLETION"));
        assertTrue(baseline.contains("RESTORE_PHASE"));
        assertTrue(baseline.contains("KEEP_PAUSED"));
        assertTrue(baseline.contains("PROTOCOL_ERROR"));
        assertFalse(baseline.contains("CONTINUE 강제 제출 준비"));
    }

    @Test public void drivePauseSignalUsesExternalManualOriginAcrossInitialAndReanchorPaths() throws Exception {
        String store = src("SelfRunStore.java");
        String pause = between(store, "private void pauseEvent", "private void terminal");
        String resume = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(pause.contains("pauseOriginForDriveSignal(x.type)"));
        assertTrue(resume.contains("pauseOriginForDriveSignal(blocking.type)"));
        assertTrue(store.contains("if(type==DriveSignalParser.Type.PAUSED)return PAUSE_ORIGIN_EXTERNAL_MANUAL"));
        assertTrue(store.contains("if(st.contains(\"SelfRun Drive 일시정지\"))return PAUSE_ORIGIN_EXTERNAL_MANUAL"));
        assertTrue(store.contains("recordPauseAnchor(e,PAUSE_ORIGIN_AI_PAUSED,code"));
    }

    @Test public void localPrerequisiteResumeRestoresAnchorPhaseBeforeDriveBaseline() throws Exception {
        String store = src("SelfRunStore.java");
        String begin = between(store, "void beginManualResumeOverride", "void baselineManualResume");
        assertTrue(begin.contains("restorePauseWithoutDrive(pauseAnchorOrigin(),resumeNeedsContinuation(),!turnDocumentId().isEmpty())"));
        assertTrue(begin.contains("String restore=pauseAnchorPhase()"));
        assertTrue(begin.contains("putString(\"phase\",restore)"));
        assertTrue(begin.contains("clearPauseAnchor(e)"));
        assertTrue(begin.contains("PHASE_RESUME_BASELINE"));
        assertTrue(store.contains("if(hasTurnDocument)return false"));
        assertTrue(store.contains("PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation"));
        assertTrue(store.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),resumeCursor,tx.policyEvents())"));
        assertTrue(store.contains("else{invalidateSupersededContinuation(e);e.putBoolean(\"resumeNeedsContinuation\",true).putString(\"status\",\"재개 보류 · 더 최신 blocking signal 확인\")"));
    }

    @Test public void noMaterialAiLatchKeepsExistingAnchorWithoutTerminalReanchor() throws Exception {
        String store = src("SelfRunStore.java");
        String resume = between(store, "void baselineManualResume", "void captureConversationUrl");
        int nullStart = resume.indexOf("if(blocking==null){");
        int elseStart = nullStart < 0 ? -1 : resume.indexOf("}else{", nullStart);
        int reanchorStart = elseStart < 0 ? -1 : resume.indexOf("pauseOriginForDriveSignal(blocking.type)", elseStart);
        assertTrue(nullStart >= 0);
        assertTrue(elseStart > nullStart);
        assertTrue(reanchorStart > elseStart);
        String nullBranch = resume.substring(nullStart, elseStart);
        assertTrue(nullBranch.contains("기존 pause latch 유지"));
        assertFalse(nullBranch.contains("recordPauseAnchor"));
        assertFalse(nullBranch.contains("terminal(e,blocking)"));
        assertFalse(nullBranch.contains("resumeNeedsContinuation"));
        assertFalse(nullBranch.contains("invalidateSupersededContinuation"));
        String blockingBranch = resume.substring(elseStart, resume.indexOf("case DONE", elseStart));
        assertTrue(blockingBranch.contains("invalidateSupersededContinuation"));
        assertTrue(blockingBranch.contains("resumeNeedsContinuation"));
        assertTrue(blockingBranch.contains("recordPauseAnchor"));
        assertTrue(blockingBranch.contains("terminal(e,blocking)"));
    }

    @Test public void pauseAnchorIsDurableAndIncludesOriginCursorAndDriveIdentity() throws Exception {
        String store = src("SelfRunStore.java");
        assertTrue(store.contains("pauseAnchorRunId"));
        assertTrue(store.contains("pauseAnchorOrigin"));
        assertTrue(store.contains("pauseAnchorPhase"));
        assertTrue(store.contains("pauseAnchorCursor"));
        assertTrue(store.contains("pauseAnchorDriveVersion"));
        assertTrue(store.contains("pauseAnchorModifiedTime"));
        assertTrue(store.contains("pauseAnchorId"));
        assertTrue(store.contains("AI_USER_ACTION_REQUIRED"));
        assertTrue(store.contains("AI_PAUSED"));
        assertTrue(store.contains("UI_MANUAL"));
    }

    @Test public void resumeDriveReadbackReturnsToStateMachineNotBlindWebSend() throws Exception {
        String service = src("SelfRunService.java");
        String poll = between(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("handler.post(this::resumeAfterDriveReconciliation)"));
        assertFalse(poll.contains("baselineManualResume(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);}))handler.post(this::ensureWebView)"));
    }

    @Test public void resumeBaselineUsesOrderedCommandTransaction() throws Exception {
        String store = src("SelfRunStore.java");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(baseline.contains("ResumeDriveTransaction tx=new ResumeDriveTransaction"));
        assertTrue(baseline.contains("tx.observe(postAnchor,pauseAnchorCursor(),totalCount)"));
        assertTrue(baseline.contains("int resumeCursor=tx.committedCursor(driveSignalCursor())"));
        assertTrue(baseline.contains("putInt(\"driveSignalCursor\",resumeCursor)"));
        assertTrue(baseline.contains("if(tx.lastProcessed()!=null)putLatest(e,tx.lastProcessed())"));
        assertTrue(baseline.contains("if(tx.acked())"));
        assertTrue(baseline.contains("tx.normalContinueAck()"));
        assertTrue(baseline.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),resumeCursor,tx.policyEvents())"));
        assertTrue(baseline.contains("String raw=tx.acceptCompletion(completion.raw)"));
        assertFalse(baseline.contains("hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
