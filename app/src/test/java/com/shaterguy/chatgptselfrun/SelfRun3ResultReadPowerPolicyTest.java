package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression contract for screen-off post-completion Drive result reads. */
public final class SelfRun3ResultReadPowerPolicyTest {
    @Test public void resultReadOwnsBoundedWakeLockForEntireDriveRead() throws Exception {
        String source = source("SelfRun3DriveAdapter.java");
        String read = source.substring(source.indexOf("String readResult("),
                source.indexOf("private SelfRun3Engine.State ensureDocument"));
        String acquire = source.substring(source.indexOf("private void acquireResultReadWakeLock()"),
                source.indexOf("private void releaseResultReadWakeLock()"));
        String release = source.substring(source.indexOf("private void releaseResultReadWakeLock()"),
                source.indexOf("private SelfRun3Engine.State ensureDocument"));

        assertTrue(read.contains("acquireResultReadWakeLock();"));
        assertTrue(read.indexOf("acquireResultReadWakeLock();") < read.indexOf("verifyAccount(token, s)"));
        assertTrue(read.contains("finally"));
        assertTrue(read.contains("releaseResultReadWakeLock();"));
        assertTrue(acquire.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(acquire.contains("SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS"));
        assertTrue(release.contains("resultReadWakeLock.release()"));
    }

    @Test public void resultReadPowerDoesNotChangeGenerationWaitPolicy() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        String coordinator = source("SelfRun3Coordinator.java");
        String wait = coordinator.substring(coordinator.indexOf("case WAIT ->"),
                coordinator.indexOf("case READ_RESULT ->"));

        assertTrue(drive.contains(":selfrun3-result-read"));
        assertTrue(wait.contains("releaseWakeLock();"));
        assertFalse(wait.contains("acquireWakeLock();"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
