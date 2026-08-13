package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunPauseResumeTest {
    @Test public void manualResumeBaselinesThenForcesContinue() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String resume = between(service, "private void resumeFromUi", "private void enterPreservedPause");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(resume.contains("beginManualResumeOverride"));
        assertFalse(resume.contains("resumeNeedsContinuation"));
        assertFalse(resume.contains("pausedFromPhase"));
        assertTrue(store.contains("PHASE_RESUME_BASELINE"));
        assertTrue(baseline.contains("PHASE_SEND_CONTINUE"));
        assertFalse(baseline.contains("event.type"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return Files.readString(p); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
