package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class AttachmentUploadPolicyTest {
    @Test public void pickerUsesSafReadOnlyPersistableGrants() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(activity.contains("Intent.EXTRA_ALLOW_MULTIPLE"));
        assertTrue(activity.contains("takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)"));
        String pickerResult = between(activity, "onActivityResult", "readAttachmentDraft");
        assertFalse(pickerResult.contains("takePersistableUriPermission"));
        String startGrant = between(activity, "persistSelectedAttachmentGrants", "persistedReadGrantUris");
        assertTrue(startGrant.contains("takePersistableUriPermission"));
        assertFalse(activity.contains("FLAG_GRANT_WRITE_URI_PERMISSION"));
        assertTrue(activity.contains("\"content\".equals(uri.getScheme())"));
    }

    @Test public void stateMachineUploadsAttachmentsBeforeTurnDocumentAndBootstrap() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        String compact = compact(service);
        assertTrue(store.contains("PHASE_DRIVE_ATTACHMENT_UPLOAD"));
        assertTrue(store.contains("ATTACHMENT_ID_RESERVED"));
        assertTrue(store.contains("ATTACHMENT_UPLOADING"));
        assertTrue(store.contains("ATTACHMENT_COMMITTED"));
        assertTrue(service.contains("drive.generateFileId(accessToken)"));
        assertTrue(service.contains("store.reserveAttachmentFileId"));
        assertTrue(service.contains("drive.getMetadata(accessToken, fileId)"));
        assertTrue(service.contains("drive.uploadAttachmentResumable"));
        assertTrue(service.contains("store.allAttachmentsCommitted()"));
        int attachmentPhase = compact.indexOf("caseSelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD->uploadNextAttachment(epoch);");
        int documentPhase = compact.indexOf("caseSelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE->createOrRecoverDocument(epoch);");
        assertTrue(attachmentPhase >= 0 && documentPhase > attachmentPhase);
    }

    @Test public void multiAttachmentBatchContinuesUntilTheLastAttachmentCommits() {
        assertTrue(SelfRunService.shouldContinueSamePhaseDriveStep(
                SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD, true));
        assertFalse(SelfRunService.shouldContinueSamePhaseDriveStep(
                SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD, false));
        assertFalse(SelfRunService.shouldContinueSamePhaseDriveStep(
                SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE, true));
    }

    @Test public void attachmentPickerResumePreservesCurrentProjectDraft() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        String resume = between(activity, "@Override protected void onResume", "@Override protected void onSaveInstanceState");
        String reload = between(activity, "private void reloadProjects()", "private String selectedProjectUrl()");
        assertTrue(resume.contains("reloadProjects(selectedProjectUrl())"));
        assertTrue(reload.contains("reloadProjects(store.defaultProjectUrl())"));
        assertTrue(reload.contains("private void reloadProjects(String preferredUrl)"));
    }

    @Test public void resumableUploadDoesNotPersistOrLogSessionUrl() throws Exception {
        String drive = src("DriveApiClient.java");
        String store = src("SelfRunStore.java");
        assertTrue(drive.contains("uploadType=resumable"));
        assertTrue(drive.contains("setInstanceFollowRedirects(false)"));
        assertTrue(drive.contains("selfrun_kind\", \"attachment"));
        assertTrue(drive.contains("attachment_index"));
        assertFalse(store.contains("uploadSession"));
        assertFalse(store.contains("resumableSession"));
    }

    @Test public void attachmentBootstrapUsesFolderOnlyNotFileNamesOrUris() throws Exception {
        String protocol = src("SelfRunProtocol.java");
        assertTrue(protocol.contains("SELF_RUN_REFERENCE_FOLDER_ID="));
        assertFalse(protocol.contains("attachment.name"));
        assertFalse(protocol.contains("attachment.uri"));
    }

    @Test public void displayNameIsSanitizedBeforeDriveMetadataUse() {
        String name = SelfRunNewActivity.sanitizeDisplayName("../bad\nname\\x.pdf", 2);
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains("\n"));
        assertTrue(name.length() <= 180);
    }

    @Test public void committedUploadPersistsBeforeRecoverableGrantCleanup() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        String commitMethod = between(store, "void markAttachmentCommitted",
                "void releaseCommittedAttachmentPermissions");
        int committedWrite = commitMethod.indexOf("ATTACHMENT_COMMITTED");
        int cleanupCall = commitMethod.indexOf("releaseCommittedAttachmentPermissions();");
        assertTrue(committedWrite >= 0 && cleanupCall > committedWrite);
        assertTrue(service.contains("store.releaseCommittedAttachmentPermissions();"));
        assertFalse(service.contains("if (reportedSize >= 0) return reportedSize"));
    }

    @Test public void nativeGoogleMimeIsNormalizedAndLimitsAreFinite() throws Exception {
        assertEquals(DriveApiClient.MIME_OCTET_STREAM,
                DriveApiClient.normalizeAttachmentMimeType("application/vnd.google-apps.document"));
        assertEquals("application/pdf", DriveApiClient.normalizeAttachmentMimeType("application/pdf"));
        assertFalse(DriveApiClient.validAttachmentMimeType("application/vnd.google-apps.spreadsheet"));
        assertEquals(10, SelfRunStore.MAX_ATTACHMENTS_PER_RUN);
        assertEquals(100L * 1024L * 1024L, SelfRunStore.MAX_ATTACHMENT_BYTES);
        assertEquals(3, SelfRunStore.MAX_ATTACHMENT_UPLOAD_ATTEMPTS);

        String service = src("SelfRunService.java");
        assertTrue(service.contains("total > SelfRunStore.MAX_ATTACHMENT_BYTES"));
        assertTrue(service.contains("DRIVE_ATTACHMENT_RETRY_LIMIT"));
        assertTrue(service.contains("DRIVE_ATTACHMENT_LIMIT_EXCEEDED"));
    }

    @Test public void draftLifecycleNeverReleasesPersistedGrantOwnedByAnotherRun() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        String pickerResult = between(activity, "onActivityResult", "readAttachmentDraft");
        String removeMethod = between(activity, "private void removeAttachment", "private void releaseReadGrant");
        String destroyMethod = between(activity, "@Override protected void onDestroy", "private void reloadProjects");
        String startGrant = between(activity, "persistSelectedAttachmentGrants", "persistedReadGrantUris");
        String startMethod = between(activity, "private void startSelfRun", "private void startRunner");

        assertFalse(pickerResult.contains("releaseReadGrant"));
        assertFalse(removeMethod.contains("releaseReadGrant"));
        assertFalse(destroyMethod.contains("releaseReadGrant"));
        assertTrue(startGrant.contains("if (!persistedBefore.contains(item.uri)) acquired.add(uri)"));
        assertTrue(startGrant.contains("releaseReadGrant(uri)"));
        assertTrue(startMethod.contains("persistedReadGrantUris()"));
        assertTrue(startMethod.contains("prepareAttachmentGrantHandoff(attachmentsNeedingPersistableGrant(persistedBefore))"));
        assertTrue(startMethod.contains("persistSelectedAttachmentGrants(persistedBefore)"));
    }

    @Test public void uriGrantCleanupUsesDurableJournal() throws Exception {
        String store = src("SelfRunStore.java");
        String activity = src("SelfRunNewActivity.java");
        assertTrue(store.contains("KEY_ATTACHMENT_GRANT_CLEANUP"));
        assertTrue(store.contains("prepareAttachmentGrantHandoff"));
        assertTrue(store.contains("drainAttachmentGrantCleanupJournal"));
        assertTrue(store.contains("getPersistedUriPermissions"));
        assertTrue(activity.contains("store.prepareAttachmentGrantHandoff(attachmentsNeedingPersistableGrant(persistedBefore))"));
        assertTrue(activity.contains("store.cancelAttachmentGrantHandoff()"));
    }

    @Test public void developmentVersionAdvancesWithScopedWebkitDependency() throws Exception {
        Path p = Paths.get("app/build.gradle");
        if (!Files.exists(p)) p = Paths.get("build.gradle");
        String gradle = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000048"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.3.0-dev8'"));
        assertTrue(gradle.contains("implementation 'com.google.android.gms:play-services-auth:21.6.0'"));
        assertTrue(gradle.contains("implementation 'androidx.webkit:webkit:1.16.0'"));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start);
        int b = source.indexOf(end, Math.max(0, a));
        if (a < 0 || b < 0 || b <= a) return "";
        return source.substring(a, b);
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
