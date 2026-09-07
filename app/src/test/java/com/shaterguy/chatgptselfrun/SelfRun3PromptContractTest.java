package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class SelfRun3PromptContractTest {
    @Test public void promptUsesCanonicalSkillAndCompactEnvelopeOnly() throws Exception {
        String source = src("SelfRun3Protocol.java");
        assertTrue(source.contains("SELF_RUN_SKILL_DOCUMENT_ID"));
        assertTrue(source.contains("1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs"));
        assertTrue(source.contains("WORK_PROFILE_CHOICES"));
        assertTrue(source.contains("compactWorkChoices"));
        assertFalse(source.contains("static final String CONTRACT ="));
        assertFalse(source.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(source.contains("ProfileRegistry.exportWorkJson"));
        assertFalse(source.contains("[최초 요구사항 원문]"));
        assertFalse(source.contains("REQUEST_ID="));
        assertFalse(source.contains("SELF_RUN_TURN_COMPLETED"));
    }

    @Test public void serviceAndLaunchMarkerDoNotPreserveRetiredExecutor() throws Exception {
        String service = src("SelfRunService.java");
        String marker = src("SelfRunSignalTransport.java");
        assertTrue(service.contains("V3_STALE_RUN_RETIRED"));
        assertFalse(service.contains("V3_LEGACY_RUN_PRESERVED"));
        assertFalse(service.contains("SelfRunRolloverCoordinator"));
        assertTrue(marker.contains("SelfRun3RunMarker.mark"));
        assertTrue(marker.contains("SelfRun3RunMarker.current"));
        assertFalse(marker.contains("DriveSignalDocumentIdentity"));
        assertFalse(marker.contains("SelfRunProtocolRules"));
    }

    private static String src(String name) throws Exception {
        Path p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun", name);
        if (!Files.exists(p)) p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun", name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
