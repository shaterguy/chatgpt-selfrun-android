package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Screen-off 3.1 waiting policy: metadata polling stays cheap; Drive I/O owns a bounded wake lock. */
public final class SelfRun3ResultReadPowerPolicyTest {
    @Test public void normalWaitUsesBoundedDriveMetadataInterval() throws Exception {
        String power = source("SelfRun3PowerPolicy.java");
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(power.contains("NORMAL_WAIT_POLL_MS = 60_000L"));
        assertTrue(power.contains("WAKE_LOCK_MAX_MS = 90_000L"));
        assertTrue(coordinator.contains("drive.resultVersion"));
        assertTrue(coordinator.contains("resultVersions"));
        assertTrue(coordinator.contains("nextResultPoll"));
    }

    @Test public void waitingDetachesSurfaceAndReleasesActiveWakeLock() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("case WAIT, READ_RESULT, CHECK_RECEIPT"));
        assertTrue(coordinator.contains("web.detach();"));
        assertTrue(coordinator.contains("releaseWakeLock();"));
        assertFalse(coordinator.contains("scheduleMissedProbe"));
        assertFalse(coordinator.contains("web.receipt"));
    }

    @Test public void metadataAndBodyReadsOwnDedicatedBoundedWakeLock() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        int constructor = drive.indexOf("SelfRun3DriveAdapter(Context");
        int setup = drive.indexOf("SelfRun3Engine.State setup(");
        int version = drive.indexOf("String resultVersion(");
        int read = drive.indexOf("String readResult(");
        int acquire = drive.indexOf("private void acquireResultReadWakeLock()");
        int release = drive.indexOf("private void releaseResultReadWakeLock()");
        int invalid = drive.indexOf("static boolean isInvalidCommittedResult");
        assertTrue(constructor >= 0 && setup > constructor);
        assertTrue(version >= 0 && read > version && acquire > read && release > acquire && invalid > release);
        String constructorBlock = drive.substring(constructor, setup);
        String versionBlock = drive.substring(version, read);
        String readBlock = drive.substring(read, acquire);
        String acquireBlock = drive.substring(acquire, release);
        String releaseBlock = drive.substring(release, invalid);
        assertTrue(constructorBlock.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(constructorBlock.contains(":selfrun3-result-read"));
        assertTrue(versionBlock.contains("acquireResultReadWakeLock();"));
        assertTrue(versionBlock.contains("finally"));
        assertTrue(versionBlock.contains("releaseResultReadWakeLock();"));
        assertTrue(readBlock.contains("acquireResultReadWakeLock();"));
        assertTrue(readBlock.indexOf("acquireResultReadWakeLock();") < readBlock.indexOf("verifyAccount(token, s)"));
        assertTrue(readBlock.contains("finally"));
        assertTrue(readBlock.contains("releaseResultReadWakeLock();"));
        assertTrue(acquireBlock.contains("resultReadWakeLock.acquire(SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS)"));
        assertTrue(releaseBlock.contains("resultReadWakeLock.release()"));
    }

    @Test public void fullResultBodyIsReadOnlyAfterMetadataVersionChanges() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String drive = source("SelfRun3DriveAdapter.java");
        assertTrue(coordinator.contains("if (version.equals(resultVersions.get(state.turnId())))"));
        assertTrue(coordinator.contains("drive.readResult(token, state)"));
        assertTrue(drive.contains("String resultVersion("));
        assertTrue(drive.contains("String readResult("));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
