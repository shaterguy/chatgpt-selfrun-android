package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunContinuationInvalidationTest {
    @Test public void supersedingDriveSignalsClearPreparedAndPendingContinuationState() throws Exception {
        String store = src("SelfRunStore.java");
        String helper = between(store, "private SharedPreferences.Editor invalidateSupersededContinuation", "private static void putLatest");
        assertTrue(helper.contains("SelfRunProtocol.clearPendingContinuation(runId())"));
        assertTrue(helper.contains("clearCommandWait(e)"));
        assertTrue(helper.contains("clearPendingCompletion(e)"));
        assertTrue(helper.contains("resetCompletionGuard(e)"));

        String pause = between(store, "private void pauseEvent", "private void terminal");
        assertTrue(pause.contains("invalidateSupersededContinuation(e)"));
        String protocolPause = between(store, "private void protocolPause", "private static boolean validRestoredPhase");
        assertTrue(protocolPause.contains("invalidateSupersededContinuation(e)"));

        String apply = between(store, "void applyDriveSignals", "void repairGuard");
        assertTrue(apply.contains("case USER_ACTION_REQUIRED->{rank=3;pauseEvent"));
        assertTrue(apply.contains("case PAUSED->{rank=3;pauseEvent"));
        assertTrue(apply.contains("case DONE->{rank=3;invalidateSupersededContinuation(e)"));
        assertTrue(apply.contains("guardArmed=false;guardFingerprint=\"\""));
    }

    @Test public void resumeInvalidatesOnlyWhenAuthorityChangesAndPreservesNoMaterialRestore() throws Exception {
        String store = src("SelfRunStore.java");
        String resume = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(segment(resume, "case APPLY_COMPLETION", "case CONTINUE").contains("invalidateSupersededContinuation(e)"));
        assertTrue(segment(resume, "case CONTINUE", "case RESTORE_PHASE").contains("invalidateSupersededContinuation(e)"));
        assertFalse(segment(resume, "case RESTORE_PHASE", "case KEEP_PAUSED").contains("invalidateSupersededContinuation(e)"));

        String keep = segment(resume, "case KEEP_PAUSED", "case DONE");
        int nullStart = keep.indexOf("if(blocking==null){");
        int elseStart = nullStart < 0 ? -1 : keep.indexOf("}else{", nullStart);
        assertTrue(nullStart >= 0);
        assertTrue(elseStart > nullStart);
        assertFalse(keep.substring(nullStart, elseStart).contains("invalidateSupersededContinuation"));
        assertTrue(keep.substring(elseStart).contains("invalidateSupersededContinuation(e)"));

        assertTrue(segment(resume, "case DONE", "case PROTOCOL_ERROR").contains("invalidateSupersededContinuation(e)"));
        assertTrue(resume.substring(resume.indexOf("case PROTOCOL_ERROR")).contains("invalidateSupersededContinuation(e)"));
    }

    @Test public void sameBatchAckAndCompletionUseTransactionLocalPendingAuthority() throws Exception {
        String store = src("SelfRunStore.java");
        String apply = between(store, "void applyDriveSignals", "void repairGuard");
        assertTrue(apply.contains("DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw())"));
        assertTrue(apply.contains("if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);batchPending.supersede()"));
        assertTrue(apply.contains("String raw=batchPending.acceptCompletion(x.raw)"));
        assertFalse(apply.contains("MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw())"));
    }

    private static String src(String f) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + f);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + f);
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String between(String s, String a, String b) { return s.substring(s.indexOf(a), s.indexOf(b)); }
    private static String segment(String s, String a, String b) { return between(s, a, b); }
}
