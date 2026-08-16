package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunPauseResumeTest {
    @Test public void manualResumeReReadsDriveBeforeContinuation() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String resume = between(service, "private void resumeFromUi", "private void enterPreservedPause");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(resume.contains("beginManualResumeOverride"));
        assertTrue(store.contains("PHASE_RESUME_BASELINE"));
        assertTrue(baseline.contains("MODE_WORK.equals(mode())"));
        assertTrue(baseline.contains("PHASE_APPLY_PREFS"));
        assertTrue(baseline.contains("PHASE_SEND_CONTINUE"));
        assertTrue(baseline.contains("latestUnseenCompletion"));
        assertFalse(baseline.contains("event.type"));
        assertFalse(service.contains("PHASE_READ_NEXT_CONTROL"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
