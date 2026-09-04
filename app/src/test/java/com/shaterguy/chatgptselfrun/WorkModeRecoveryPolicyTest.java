package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class WorkModeRecoveryPolicyTest {
    @Test public void manualPauseCanRestoreProtocolWaitAndUiPhases() {
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_APPLY_PREFS));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_APPLY_REASONING));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_SEND_CONTINUE));
        assertFalse(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC));
    }
    @Test public void waitHasNoWorkSpecificOrDomFallbackPath() throws Exception {
        String service=source("SelfRunService.java"),dom=source("SelfRunContinuationDom.java");
        String wait=service.substring(service.indexOf("private void runWebStep()"),service.indexOf("private String ensureTurnProtocolToken"));
        assertTrue(wait.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(wait.contains("detachDisplayOutput"));
        assertFalse(wait.contains("authorizeAndRunDrive"));
        assertFalse(wait.contains("evaluateJavascript"));
        assertFalse(dom.contains("observeTurnCompletion"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
