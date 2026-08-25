package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class DriveInitializationPolicyTest {
    @Test public void newExecutionDocStoresExactOriginalRequirement() throws Exception {
        String service = src("SelfRunService.java");
        String init = between(service, "private void initializeDocument", "private void verifyInitialDocument");
        assertTrue(init.contains("SelfRunOriginalRequirement.validationError"));
        assertTrue(init.contains("readTurnDocumentSnapshot"));
        assertTrue(init.contains("current.revisionId"));
        assertTrue(init.contains("exactDocumentMatch"));
        assertTrue(init.contains("ORIGINAL_REQUIREMENT_READBACK_MISMATCH"));
    }

    @Test public void signalDocumentTransportNeverFallsBackToRequirementBody() throws Exception {
        String drive = src("DriveApiClient.java");
        assertTrue(drive.contains("final boolean staged"));
        assertTrue(drive.contains("if (batch.staged) return readSignalDocumentSnapshot"));
        assertTrue(drive.contains("getPollMetadata(String accessToken, String fileId, boolean signalDocumentTransport)"));
        String service = src("SelfRunService.java");
        assertTrue(service.contains("SelfRunSignalTransport.isSignalDocumentRun"));
    }

    @Test public void newRunUiPreservesRawRequirementAndMarksTransport() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("String request = requirement.getText().toString();"));
        assertFalse(activity.contains("String request = requirement.getText().toString().trim();"));
        assertTrue(activity.contains("SelfRunOriginalRequirement.validationError(request)"));
        assertTrue(activity.contains("SelfRunSignalTransport.mark(this, runId)"));
        assertTrue(activity.contains("SelfRunRunId.create()"));
    }

    private static String src(String f) throws Exception {
        Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);
        if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x+a.length());assertTrue(x>=0);assertTrue(y>x);return s.substring(x,y);}
}
