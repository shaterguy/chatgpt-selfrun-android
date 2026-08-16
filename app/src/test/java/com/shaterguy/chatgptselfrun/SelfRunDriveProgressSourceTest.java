package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunDriveProgressSourceTest {
    @Test public void chatProgressUsesDriveCompletionWithoutAssistantDom() throws Exception {
        String service=src("SelfRunService.java"),dom=src("SelfRunDom.java");
        assertTrue(service.contains("PHASE_DRIVE_COMMIT_GUARD"));
        assertTrue(service.contains("PHASE_SEND_CONTINUE"));
        assertFalse(service.contains("PHASE_READ_NEXT_CONTROL"));
        assertFalse(dom.contains("SELF_RUN_NEXT"));
        assertFalse(dom.contains("data-message-author-role=\"assistant\""));
    }

    @Test public void workProgressUsesOnlyDriveCompletionProfile() throws Exception {
        String service=src("SelfRunService.java"),store=src("SelfRunStore.java"),protocol=src("SelfRunProtocol.java");
        assertTrue(service.contains("PHASE_APPLY_PREFS"));
        assertTrue(store.contains("DriveSignalParser.workProfile(pendingDriveSignalRaw())"));
        assertFalse(protocol.contains("parseLatest("));
        assertFalse(protocol.contains("SELF_RUN_NEXT"));
    }

    @Test public void pauseResumeReconcilesDriveThenRoutesByMode() throws Exception {
        String service=src("SelfRunService.java"),store=src("SelfRunStore.java");
        assertTrue(service.contains("PHASE_RESUME_BASELINE"));
        assertTrue(service.contains("pollDriveNow(epoch)"));
        String baseline=between(store,"void baselineManualResume","void captureConversationUrl");
        assertTrue(baseline.contains("MODE_WORK.equals(mode())"));
        assertTrue(baseline.contains("PHASE_APPLY_PREFS"));
        assertTrue(baseline.contains("PHASE_SEND_CONTINUE"));
        assertFalse(service.contains("readLatestSelfRunControl"));
    }

    @Test public void appDoesNotPersistOrDisplayRole() throws Exception {
        assertFalse(src("SelfRunStore.java").contains("String role()"));
        assertFalse(src("SelfRunHistoryStore.java").contains("store.role()"));
        assertFalse(src("SelfRunDetailActivity.java").contains("optString(\"role\")"));
    }

    private static String src(String file) throws Exception {
        Path main=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+file);
        if(Files.exists(main)) return new String(Files.readAllBytes(main), StandardCharsets.UTF_8);
        Path test=Paths.get("app/src/test/java/com/shaterguy/chatgptselfrun/"+file);
        if(!Files.exists(test)) test=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+file);
        return new String(Files.readAllBytes(test), StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b));}
}
