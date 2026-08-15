package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunIdempotencySourceTest {
    @Test public void continuationMarkerUsesStableCompletionPauseAndNextIdentity() throws Exception {
        String store = src("SelfRunStore.java");
        String marker = between(store, "String commandMarkerId", "void markCommandSubmitted");
        assertTrue(marker.contains("pendingDriveSignalCursor"));
        assertTrue(marker.contains("pauseAnchorId"));
        assertTrue(marker.contains("nextInputFingerprint"));
        assertFalse(marker.contains("commandAttempt()+\":continue"));
    }

    @Test public void clickedContinuationMarkerIsNeverClickedAgainOnRetryOrRestart() throws Exception {
        String dom = src("SelfRunDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = between(dom, "static String clickPreparedDriveTurn", "static String readLatestSelfRunControl");
        assertTrue(prepare.contains("state==='clicked'"));
        assertTrue(prepare.contains("SUBMISSION_PENDING"));
        assertTrue(click.contains("state==='clicked'"));
        assertTrue(click.contains("SUBMISSION_PENDING"));
    }

    @Test public void duplicateCompletionGuardResetsOnlyAfterCommandReceived() throws Exception {
        String store = src("SelfRunStore.java");
        String apply = between(store, "void applyDriveSignals", "void repairGuard");
        assertTrue(apply.contains("completionGuardFingerprint"));
        assertTrue(apply.contains("TURN_COMPLETED 중복 확인"));
        assertTrue(apply.contains("case COMMAND_RECEIVED"));
        assertTrue(apply.contains("completionGuardArmed"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
