package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRunContinuationSubmissionTest {
    @Test public void submissionDomIsAnActuatorOnly() throws Exception {
        String dom=source("SelfRunContinuationDom.java");
        assertTrue(dom.contains("baselineUserCount=userMessageCount()"));
        assertTrue(dom.contains("c.send.click()"));
        assertTrue(dom.contains("requestComposerSubmit()"));
        assertTrue(dom.contains("continuationClickedVerification()"));
        assertFalse(dom.contains("MutationObserver"));
        assertFalse(dom.contains("observeTurnCompletion"));
        assertFalse(dom.contains("armCompletionObserver"));
        assertFalse(dom.contains("assistant_final"));
    }
    @Test public void nativeBindsProtocolBeforeEitherSubmitPath() throws Exception {
        String service=source("SelfRunService.java");
        assertTrue(service.contains("ChatGptTurnProtocolScript.bindTurnAndThen"));
        assertTrue(service.contains("clickPreparedBootstrap"));
        assertTrue(service.contains("clickPreparedDriveTurn"));
        assertTrue(service.contains("store.beginTurnCompletionWait"));
        assertTrue(service.contains("detachDisplayOutput(\"submission_confirmed\")"));
        assertTrue(service.contains("armProtocolCompletion(token)"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return Files.readString(path,StandardCharsets.UTF_8);
    }
}
