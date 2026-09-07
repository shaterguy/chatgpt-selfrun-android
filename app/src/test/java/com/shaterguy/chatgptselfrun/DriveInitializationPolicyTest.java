package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class DriveInitializationPolicyTest {
    @Test public void newExecutionDocStoresExactOriginalRequirement() throws Exception {
        String drive = src("SelfRun3DriveAdapter.java");
        String setup = between(drive, "SelfRun3Engine.State setup", "SelfRun3Engine.State prepareTurn");
        assertTrue(setup.contains("String requirement = s.config().optString(\"requirement\")"));
        assertTrue(setup.contains("api.readTurnDocumentSnapshot"));
        assertTrue(setup.contains("api.initializeDocument"));
        assertTrue(setup.contains("initial.revisionId"));
        assertTrue(setup.contains("stripTerminalNewline(initial.text).equals(stripTerminalNewline(requirement))"));
        assertTrue(setup.contains("REQUIREMENT_READBACK_MISMATCH"));
    }

    @Test public void v3CompletionNeverUsesLegacySignalDocumentTransport() throws Exception {
        String service = src("SelfRunService.java");
        String coordinator = src("SelfRun3Coordinator.java");
        String drive = src("SelfRun3DriveAdapter.java");
        assertFalse(service.contains("SelfRunSignalTransport.isSignalDocumentRun"));
        assertFalse(coordinator.contains("DriveSignalParser"));
        assertFalse(drive.contains("readSignalDocumentSnapshot"));
        assertTrue(drive.contains("SelfRun3DriveLookup.findSingleDocumentId"));
        assertTrue(coordinator.contains("DriveStep.READ_RESULT"));
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
