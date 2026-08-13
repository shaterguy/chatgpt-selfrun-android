package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class DriveInitializationPolicyTest {
    @Test public void newExecutionDocIsSignalLogWithoutInitialBlock() throws Exception {
        String service = src("SelfRunService.java");
        assertFalse(service.contains("DriveInitialDocument"));
        String method = between(service, "private void initializeDocument", "private void verifyInitialDocument");
        assertTrue(method.contains("readDocumentText"));
        assertFalse(method.contains("initializeDocument(accessToken"));
        assertTrue(service.contains("DriveSignalParser.scan"));
    }
    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
