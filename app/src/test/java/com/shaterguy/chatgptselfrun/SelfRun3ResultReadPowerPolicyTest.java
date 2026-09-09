package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Screen-off 3.1 waiting policy: bounded polling fresh-reads Drive under a dedicated wake lock. */
public final class SelfRun3ResultReadPowerPolicyTest {
    @Test public void normalWaitUsesBoundedDrivePollInterval() throws Exception {
        String power = source("SelfRun3PowerPolicy.java");
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(power.contains("NORMAL_WAIT_POLL_MS = 30_000L"));
        assertTrue(power.contains("WAKE_LOCK_MAX_MS = 90_000L"));
        assertTrue(coordinator.contains("drive.observeResult(token, state)"));
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

    @Test public void freshObservationAndMetadataReadsOwnDedicatedBoundedWakeLock() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        int constructor = drive.indexOf("SelfRun3DriveAdapter(Context");
        int setup = drive.indexOf("SelfRun3Engine.State setup(");
        int observe = drive.indexOf("ResultObservation observeResult(");
        int version = drive.indexOf("String resultVersion(");
        int read = drive.indexOf("String readResult(");
        int acquire = drive.indexOf("private void acquireResultReadWakeLock()");
        int release = drive.indexOf("private void releaseResultReadWakeLock()");
        int invalid = drive.indexOf("static boolean isInvalidCommittedResult");
        assertTrue(constructor >= 0 && setup > constructor);
        assertTrue(observe >= 0 && version > observe && read > version && acquire > read && release > acquire && invalid > release);
        String constructorBlock = drive.substring(constructor, setup);
        String observeBlock = drive.substring(observe, version);
        String versionBlock = drive.substring(version, read);
        String readBlock = drive.substring(read, acquire);
        String acquireBlock = drive.substring(acquire, release);
        String releaseBlock = drive.substring(release, invalid);
        assertTrue(constructorBlock.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(constructorBlock.contains(":selfrun3-result-read"));
        assertTrue(observeBlock.contains("acquireResultReadWakeLock();"));
        assertTrue(observeBlock.indexOf("acquireResultReadWakeLock();") < observeBlock.indexOf("verifyAccount(token, s)"));
        assertTrue(observeBlock.contains("finally"));
        assertTrue(observeBlock.contains("releaseResultReadWakeLock();"));
        assertTrue(versionBlock.contains("acquireResultReadWakeLock();"));
        assertTrue(versionBlock.contains("finally"));
        assertTrue(versionBlock.contains("releaseResultReadWakeLock();"));
        assertTrue(readBlock.contains("return observeResult(token, s).candidateBody;"));
        assertTrue(acquireBlock.contains("resultReadWakeLock.acquire(SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS)"));
        assertTrue(releaseBlock.contains("resultReadWakeLock.release()"));
    }

    @Test public void fullResultBodyIsFreshReadOnEveryDuePoll() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String drive = source("SelfRun3DriveAdapter.java");
        assertTrue(coordinator.contains("drive.observeResult(token, state)"));
        assertTrue(coordinator.contains("SelfRun3Engine.parseResult(observation.candidateBody, current)"));
        assertFalse(coordinator.contains("if (version.equals(resultVersions.get(state.turnId())))"));
        assertTrue(drive.contains("ResultObservation observeResult("));
        assertTrue(drive.contains("readTurnDocumentSnapshot(token, s.resource(\"resultDocumentId\"))"));
        assertTrue(drive.contains("new ResultObservation(metadata.modifiedTime + \":\" + metadata.version, raw, candidate)"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
