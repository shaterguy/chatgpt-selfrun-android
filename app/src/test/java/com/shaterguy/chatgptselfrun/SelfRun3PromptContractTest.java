package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRun3PromptContractTest {
    @Test public void promptUses31CanonicalSkillAndFreshExecutionEnvelope() throws Exception {
        String source = src("SelfRun3Protocol.java");
        assertTrue(source.contains("CONTRACT_VERSION=\"3.1.0\""));
        assertTrue(source.contains("SELF_RUN_SKILL_DOCUMENT_ID=SKILL_DOCUMENT_ID"));
        assertTrue(source.contains("1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo"));
        assertFalse(source.contains("1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs"));
        assertTrue(source.contains("[SELF_RUN_V3 "));
        assertTrue(source.contains("REQUEST_ID"));
        assertTrue(source.contains("RESULT_IDENTITY_TEMPLATE"));
        assertTrue(source.contains("PROFILE_REGISTRY_CHAT"));
        assertTrue(source.contains("PROFILE_REGISTRY_WORK"));
        assertTrue(source.contains("[최초 요구사항 원문]"));
        assertTrue(source.contains("full checkpoint"));
        assertTrue(source.contains("PARALLEL_MERGE"));
        assertTrue(source.contains("USER_ACTION_RESOLVED"));
    }

    @Test public void serviceAndLaunchMarkerStayV3Only() throws Exception {
        String service = src("SelfRunService.java");
        String marker = src("SelfRunSignalTransport.java");
        assertTrue(service.contains("V3_STALE_RUN_RETIRED"));
        assertFalse(service.contains("SelfRunRolloverCoordinator"));
        assertTrue(marker.contains("SelfRun3RunMarker.mark"));
        assertTrue(marker.contains("SelfRun3RunMarker.current"));
    }

    private static String src(String name) throws Exception {
        Path p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun", name);
        if (!Files.exists(p)) p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun", name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
