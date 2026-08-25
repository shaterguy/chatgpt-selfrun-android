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
        assertTrue(authorization.contains("setRequestedScopes(runtimeScopes())"));
        assertTrue(authorization.contains("setRequestedScopes(pickerScopes())"));
        assertTrue(authorization.contains("Collections.singletonList(new Scope(DRIVE_FILE_SCOPE))"));
        assertFalse(authorization.contains("new Scope(\"https://www.googleapis.com/auth/drive\")"));
        assertFalse(authorization.contains("DRIVE_SCOPE = \"https://www.googleapis.com/auth/drive\""));
    }

    @Test public void pickerOAuthTriggerNeverCombinesCrossAppReadScopes() throws Exception {
        String authorization = src("DriveAuthorization.java");
        String pickerScopes = between(authorization, "private static List<Scope> pickerScopes()", "static AuthorizationRequest silentRequest()");
        String pickerRequest = between(authorization, "static AuthorizationRequest folderPickerRequest()", "static void requestSilently");
        assertTrue(pickerScopes.contains("DRIVE_FILE_SCOPE"));
        assertFalse(pickerScopes.contains("DRIVE_METADATA_READONLY_SCOPE"));
        assertFalse(pickerScopes.contains("DOCUMENTS_READONLY_SCOPE"));
        assertTrue(pickerRequest.contains("setRequestedScopes(pickerScopes())"));
        assertTrue(pickerRequest.contains("PICKER_OAUTH_TRIGGER"));
        assertFalse(pickerRequest.contains("runtimeScopes()"));
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

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        if (a < 0 || b <= a) return "";
        return source.substring(a, b);
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
