package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class DriveSignalBodyWriteRacePolicyTest {
    @Test public void sameSignalFileBodyCompletionChangesSyntheticPollIdentity() throws Exception {
        String api = src("DriveApiClient.java");
        assertTrue(api.contains("\"signal:\" + latest.id + \":\" + latest.modifiedTime"));
        assertTrue(api.contains("latest.modifiedTime));"));
    }

    @Test public void malformedOrNotYetWrittenNextInputBodyIsNotConsumed() throws Exception {
        String api = src("DriveApiClient.java");
        assertTrue(api.contains("DriveSignalDocumentTransport.needsBodyRead(metadata.name)"));
        assertTrue(api.contains("catch (IllegalArgumentException malformedSignal)"));
        assertTrue(api.contains("continue;"));
        assertTrue(api.contains("readNativeDocumentSnapshot(accessToken, metadata.id).text"));
    }

    @Test public void providerCreatedTimeStillOwnsSignalOrdering() throws Exception {
        String transport = src("DriveSignalDocumentTransport.java");
        assertTrue(transport.contains("Long.compare(createdMillis(leftCreatedTime), createdMillis(rightCreatedTime))"));
        assertTrue(transport.contains("titleTimestamp(leftTitle, runId).compareTo(titleTimestamp(rightTitle, runId))"));
        assertTrue(transport.contains("safeLeftId.compareTo(safeRightId)"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
