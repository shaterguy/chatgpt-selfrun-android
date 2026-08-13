package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class DriveVariantPolicyTest {
    @Test public void identityVersionActionsAndProviderPolicyAreIsolated() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        String service = source("SelfRunService.java");
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.drive'"));
        assertTrue(gradle.contains("versionCode 1000002"));
        assertTrue(gradle.contains("versionName '1.0.0-dev2'"));
        assertTrue(manifest.contains("android:label=\"@string/app_name\""));
        assertFalse(manifest.contains("android:sharedUserId"));
        assertFalse(manifest.matches("(?s).*android:authorities=\"(?!\\$\\{applicationId}).*"));
        assertTrue(service.contains("BuildConfig.APPLICATION_ID + \".RUN\""));
        assertTrue(service.contains("BuildConfig.APPLICATION_ID + \".PAUSE\""));
        assertTrue(service.contains("BuildConfig.APPLICATION_ID + \".RESUME\""));
        assertFalse(service.contains("com.shaterguy.chatgptselfrun.RUN"));
    }

    @Test public void driveRuntimeHasNoDiscoveryOrAssistantCompletionPath() throws Exception {
        String service = source("SelfRunService.java");
        assertFalse(service.contains("WAIT_DRIVE_DISCOVERY"));
        assertFalse(service.contains("WAIT_ASSISTANT"));
        assertFalse(service.contains("SESSION_BOUND"));
        assertFalse(service.contains("sessionBindTimedOut"));
        assertFalse(service.contains("observeAssistant"));
        assertFalse(service.contains("assistant DOM"));
        assertTrue(service.contains("getPollMetadata(accessToken, snapshot.turnDocumentId)"));
        assertTrue(service.contains("CONTINUATION_GUARD_MS = 120_000L"));
    }

    @Test public void creationUsesReservedFolderIdAndNeverDiscoversOrRecreatesUnknownDocument() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("drive.generateFolderId(accessToken)"));
        assertTrue(service.contains("store.reserveJobFolderId(reservedId)"));
        assertTrue(service.contains("drive.createJobFolder(accessToken, folderId, driveOperationRunId, base)"));
        assertTrue(service.contains("DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN"));
        assertFalse(service.contains("recoverAmbiguousCreate"));
        String api = source("DriveApiClient.java");
        assertTrue(api.contains("files/generateIds"));
        assertTrue(api.contains(".put(\"id\", folderId)"));
        assertTrue(api.contains("OutcomeUnknownException"));
        assertFalse(api.contains("pageSize="));
        assertFalse(api.contains("&q="));
    }

    @Test public void criticalStateWritesAreSynchronousAndChecked() throws Exception {
        String store = source("SelfRunStore.java");
        assertTrue(store.contains("private static void commitOrThrow"));
        assertTrue(store.contains("if (!editor.commit())"));
        assertTrue(store.contains("commitOrThrow(prefs.edit().putString(\"submissionState\", SUBMISSION_STARTED)"));
        assertEquals(1, occurrences(store, ".commit()"));
    }

    @Test public void guardRestoresOriginalDeadlineAndNeverReclicksUncertainSubmission() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("boolean restoring = commit.id().equals(store.pendingCommitId())"));
        assertTrue(service.contains("if (!restoring)"));
        assertTrue(service.contains("long detectedAt = store.commitDetectedAt(), dueAt = store.guardDueAt()"));
        assertTrue(service.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
        String dom = source("SelfRunDom.java");
        String recovery = dom.substring(dom.indexOf("static String checkDriveTurnSubmitted"),
                dom.indexOf("static String observeAssistant"));
        assertTrue(recovery.contains("data-message-author-role=\\\"user\\\""));
        assertFalse(recovery.contains("send.click()"));
        assertFalse(recovery.contains("data-message-author-role=\\\"assistant\\\""));
    }

    @Test public void jobIdUsesFullUuidEntropy() throws Exception {
        String activity = source("SelfRunNewActivity.java");
        assertTrue(activity.contains("UUID.randomUUID().toString().replace(\"-\", \"\").toUpperCase"));
        assertFalse(activity.contains("substring(0, 6)"));
    }

    @Test public void driveClientPinsScopeHostsParentsAndRecoveryIdentity() throws Exception {
        String auth = source("DriveAuthorization.java");
        String api = source("DriveApiClient.java");
        assertTrue(auth.contains("https://www.googleapis.com/auth/drive.file"));
        assertFalse(auth.contains("https://www.googleapis.com/auth/drive\""));
        assertTrue(auth.contains("PICKER_OAUTH_TRIGGER"));
        assertTrue(auth.contains("PICKER_ALLOW_FOLDER_SELECTION"));
        assertTrue(api.contains("setInstanceFollowRedirects(false)"));
        assertTrue(api.contains("ALLOWED_HOSTS"));
        assertTrue(api.contains("new JSONArray().put(parentId)"));
        assertTrue(api.contains("protocol_version"));
        assertTrue(api.contains("created_by"));
        assertTrue(api.contains("POLL_FIELDS"));
        assertTrue(api.contains("id,mimeType,parents,trashed,version,modifiedTime,shared"));
    }

    @Test public void driveWaitGuardAndStaleCallbacksCannotScheduleWebAutomation() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("private static boolean isWebAutomationPhase"));
        assertTrue(service.contains("!isWebAutomationPhase(store.phase())"));
        assertTrue(service.contains("handler.removeCallbacks(driveRetryRunnable)"));
        assertTrue(service.contains("epoch != automationEpoch"));
        assertTrue(service.contains("private volatile int automationEpoch"));
        assertTrue(service.contains("private volatile boolean driveInFlight"));
        assertTrue(service.contains("private boolean applyDriveResult"));
        assertTrue(service.contains("synchronized (SelfRunStore.RUN_STATE_LOCK)"));
        assertTrue(service.contains("driveOperationRunId.equals(store.runId())"));
        String phases = service.substring(service.indexOf("private static boolean isWebAutomationPhase"));
        assertFalse(phases.substring(0, phases.indexOf("private void handleDriveFailure"))
                .contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(phases.substring(0, phases.indexOf("private void handleDriveFailure"))
                .contains("PHASE_DRIVE_COMMIT_GUARD"));
    }

    @Test public void pauseResumeAndTerminalSideEffectsAreCrashDurable() throws Exception {
        String store = source("SelfRunStore.java");
        String service = source("SelfRunService.java");
        assertTrue(store.contains("void resumeTerminalWithContinuation()"));
        assertTrue(store.contains(".putString(\"phase\", PHASE_SEND_CONTINUE)"));
        assertTrue(store.contains("terminalSideEffectPending"));
        assertTrue(store.contains("terminalSideEffectRunId"));
        assertTrue(store.contains("terminalSideEffectCommitId"));
        assertTrue(store.contains("terminalSideEffectOwnedBy"));
        assertTrue(service.contains("replayTerminalSideEffect()"));
        assertTrue(service.contains("expectedRunId.equals(store.runId())"));
        assertTrue(service.contains("expectedPhase.equals(store.phase())"));
        assertTrue(service.contains("webView.onResume()"));
    }

    @Test public void bootstrapIsStagedBeforeClickAndUnknownResultNeverReclicks() throws Exception {
        String service = source("SelfRunService.java");
        String dom = source("SelfRunDom.java");
        assertTrue(service.contains("store.markBootstrapSubmissionStarted()"));
        assertTrue(service.contains("clickPreparedDriveInitial"));
        assertTrue(service.contains("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN"));
        String check = dom.substring(dom.indexOf("static String checkDriveInitialSubmitted"),
                dom.indexOf("static String prepareDriveTurn"));
        assertFalse(check.contains("send.click()"));
        assertFalse(check.contains("assistant"));
        assertTrue(check.contains("if(conv&&prior)return result('CONFIRMED'"));
        assertFalse(check.contains("data-message-author-role=\"user\""));
    }

    @Test public void legacyBootstrapStillRoutesWithoutDriveContract() {
        String legacy = SelfRunProtocol.bootstrap("SR-1", SelfRunStore.MODE_CHAT, "work");
        assertTrue(legacy.startsWith("[SELF_RUN_BOOTSTRAP 0.1.0 SR-1 MODE=CHAT]"));
        assertFalse(legacy.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        String drive = SelfRunProtocol.bootstrapDrive("SR-1", SelfRunStore.MODE_CHAT, "work",
                "runsFolder_12345678", "jobFolder_12345678", "document_12345678",
                "https://docs.google.com/document/d/document_12345678/edit", 1);
        assertTrue(drive.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(drive.contains("DRIVE_TURN_DOCUMENT_ID=document_12345678"));
    }

    private static String source(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String rootPath, String modulePath) throws Exception {
        Path path = Paths.get(rootPath);
        if (!Files.exists(path)) path = Paths.get(modulePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0, at = 0;
        while ((at = value.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }
}
