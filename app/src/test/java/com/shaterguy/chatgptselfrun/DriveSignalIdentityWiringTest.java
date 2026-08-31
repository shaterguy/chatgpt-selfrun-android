package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the file-ID based signal transport wiring against numeric cursor regression. */
public final class DriveSignalIdentityWiringTest {
    @Test public void signalDocumentTransportUsesDriveIdsAsOngoingConsumptionAuthority() throws Exception {
        String marker = src("SelfRunSignalTransport.java");
        String transport = src("DriveSignalDocumentTransport.java");
        String parser = src("DriveCommitParser.java");
        String identity = src("DriveSignalDocumentIdentity.java");
        String service = src("SelfRunService.java");

        assertTrue(marker.contains("DriveSignalDocumentIdentity.activate(context, value)"));
        assertTrue(transport.contains("DriveSignalDocumentIdentity.observeCandidate"));
        assertTrue(transport.contains("DriveSignalDocumentIdentity.preparePollOrdering(runId)"));
        assertTrue(transport.contains("DriveSignalDocumentIdentity.seal(runId)"));
        assertTrue(transport.contains("scanWithoutDocumentIdentity"));
        assertTrue(parser.contains("DriveSignalDocumentIdentity.resolver(jobId, consumed)"));
        assertTrue(parser.contains("!resolver.recognized(event.documentId)"));
        assertTrue(parser.contains("signal document identity mapping incomplete"));
        assertTrue(identity.contains("ignoredLegacyCursor"));
        assertTrue(identity.contains("legacy numeric cursor is not"));
        assertTrue(identity.contains("created < boundaryCreated"));
        assertTrue(service.contains("baselineManualResume(scan.totalCount,scan.latest,latestCompletion)"));
        assertFalse(parser.contains("identityMappingComplete"));
        assertFalse(identity.contains("legacyConsumed >"));
        assertFalse(identity.contains("event.cursor >"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
