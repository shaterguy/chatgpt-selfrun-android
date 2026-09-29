package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class ProfileRegistryTransferWiringTest {
    @Test public void registryActivityIsReadOnlyCanonicalStatusAndRefresh() throws Exception {
        String activity = source("ProfileRegistryActivity.java");
        assertTrue(activity.contains("ProfileRegistrySync.refresh"));
        assertTrue(source("ProfileRegistrySync.java").contains(ProfileRegistrySync.CHAT_DOCUMENT_ID));
        assertTrue(source("ProfileRegistrySync.java").contains(ProfileRegistrySync.WORK_DOCUMENT_ID));
        assertFalse(activity.contains("ACTION_OPEN_DOCUMENT"));
        assertFalse(activity.contains("startCapture("));
        assertFalse(activity.contains("registerCaptured("));
        assertFalse(activity.contains("confirmDelete("));
    }

    @Test public void registryCodecVerifiesCanonicalFingerprintsAndLkg() throws Exception {
        String registry = source("ProfileRegistry.java");
        assertTrue(registry.contains("parseCanonicalRegistry"));
        assertTrue(registry.contains("canonical fingerprint mismatch"));
        assertTrue(registry.contains("KEY_CHAT_LKG"));
        assertTrue(registry.contains("KEY_WORK_LKG"));
        assertTrue(registry.contains("replaceCanonicalSnapshot"));
        assertTrue(registry.contains("canonical registry returned zero profiles"));
    }

    @Test public void newRunRefreshesRegistryBeforeValidationAndUsesSharedAuthority() throws Exception {
        String activity = source("SelfRunNewActivity.java");
        String engine = source("RequestProfileScript.java");
        assertTrue(activity.contains("ProfileRegistrySync.refresh"));
        assertTrue(activity.contains("ProfileRegistry.listChat()"));
        assertTrue(activity.contains("ProfileRegistry.listWork()"));
        assertTrue(engine.contains("installRegistry"));
        assertTrue(engine.contains("ProfileRegistry.runtimeJson()"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
