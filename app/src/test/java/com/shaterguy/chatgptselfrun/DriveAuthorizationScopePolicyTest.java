package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class DriveAuthorizationScopePolicyTest {
    @Test public void signalTransportAddsReadOnlyCrossAppScopesWithoutFullDriveWriteScope() throws Exception {
        String authorization = src("DriveAuthorization.java");
        assertTrue(authorization.contains("https://www.googleapis.com/auth/drive.file"));
        assertTrue(authorization.contains("https://www.googleapis.com/auth/drive.metadata.readonly"));
        assertTrue(authorization.contains("https://www.googleapis.com/auth/documents.readonly"));
        assertTrue(authorization.contains("setRequestedScopes(requiredScopes())"));
        assertFalse(authorization.contains("new Scope(\"https://www.googleapis.com/auth/drive\")"));
        assertFalse(authorization.contains("DRIVE_SCOPE = \"https://www.googleapis.com/auth/drive\""));
    }

    @Test public void signalDiscoveryIsRestrictedAgainByExactRunFolderAndRunId() throws Exception {
        String api = src("DriveApiClient.java");
        String transport = src("DriveSignalDocumentTransport.java");
        assertTrue(api.contains("turnDocument.parentId"));
        assertTrue(api.contains("listSignalDocuments(accessToken, turnDocument.name, turnDocument.parentId)"));
        assertTrue(api.contains("' in parents and trashed = false"));
        assertTrue(transport.contains("expectedParentId.equals(parentId)"));
        assertTrue(transport.contains("isCanonicalTitle(name, runId)"));
        assertTrue(transport.contains("metadata.shared"));
    }

    @Test public void nextInputBodyIsReadOnlyWhenTitleExplicitlyMarksPresence() throws Exception {
        String api = src("DriveApiClient.java");
        assertTrue(api.contains("DriveSignalDocumentTransport.needsBodyRead(metadata.name)"));
        assertTrue(api.contains("readNativeDocumentSnapshot(accessToken, metadata.id).text"));
        assertTrue(api.contains("DriveSignalDocumentTransport.materialize(metadata.name, body, batch.runId)"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
