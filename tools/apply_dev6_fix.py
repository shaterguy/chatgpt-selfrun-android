from pathlib import Path


def patch(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected text not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


patch(
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
    '''private static boolean drivePhase(String phase){return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)||SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)||SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)||SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)||SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(phase)||SelfRunStore.PHASE_RESUME_BASELINE.equals(phase);}\n''',
    '''private static boolean drivePhase(String phase){return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)||SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)||SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)||SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)||SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(phase)||SelfRunStore.PHASE_RESUME_BASELINE.equals(phase);}\n\nstatic boolean shouldContinueSamePhaseDriveStep(String phase, boolean hasUncommittedAttachment) {\n    return SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase) && hasUncommittedAttachment;\n}\n''',
)

patch(
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
    '''                    runDriveStep(epoch);\n                    if (prior.equals(store.phase()) || SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase()) || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) break;\n''',
    '''                    runDriveStep(epoch);\n                    if (prior.equals(store.phase())) {\n                        if (shouldContinueSamePhaseDriveStep(prior, store.nextUncommittedAttachment() != null)) continue;\n                        break;\n                    }\n                    if (SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())\n                            || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) break;\n''',
)

patch(
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java",
    '''    @Override protected void onResume() { super.onResume(); if (firstResume) { firstResume=false; return; } if(project!=null) reloadProjects(); }\n''',
    '''    @Override protected void onResume() { super.onResume(); if (firstResume) { firstResume=false; return; } if(project!=null) reloadProjects(selectedProjectUrl()); }\n''',
)

patch(
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java",
    '''    private void reloadProjects() {\n        String previous=store.defaultProjectUrl(); projectEntries=catalog.entries(); ArrayList<String> labels=new ArrayList<>(); labels.add("일반채팅"); int selected=0;\n        for(int i=0;i<projectEntries.size();i++){ProjectUrlPolicy.ProjectRef entry=projectEntries.get(i);labels.add(catalog.displayName(entry));if(entry.canonicalUrl.equals(previous))selected=i+1;}\n        project.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); project.setSelection(selected);\n    }\n''',
    '''    private void reloadProjects() { reloadProjects(store.defaultProjectUrl()); }\n\n    private void reloadProjects(String preferredUrl) {\n        String previous=preferredUrl==null?"":preferredUrl; projectEntries=catalog.entries(); ArrayList<String> labels=new ArrayList<>(); labels.add("일반채팅"); int selected=0;\n        for(int i=0;i<projectEntries.size();i++){ProjectUrlPolicy.ProjectRef entry=projectEntries.get(i);labels.add(catalog.displayName(entry));if(entry.canonicalUrl.equals(previous))selected=i+1;}\n        project.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); project.setSelection(selected);\n    }\n''',
)

test_path = Path("app/src/test/java/com/shaterguy/chatgptselfrun/AttachmentUploadPolicyTest.java")
test = test_path.read_text(encoding="utf-8")
marker = '''    @Test public void resumableUploadDoesNotPersistOrLogSessionUrl() throws Exception {\n'''
extra = '''    @Test public void multiAttachmentBatchContinuesUntilTheLastAttachmentCommits() {\n        assertTrue(SelfRunService.shouldContinueSamePhaseDriveStep(\n                SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD, true));\n        assertFalse(SelfRunService.shouldContinueSamePhaseDriveStep(\n                SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD, false));\n        assertFalse(SelfRunService.shouldContinueSamePhaseDriveStep(\n                SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE, true));\n    }\n\n    @Test public void attachmentPickerResumePreservesCurrentProjectDraft() throws Exception {\n        String activity = src("SelfRunNewActivity.java");\n        String resume = between(activity, "@Override protected void onResume", "@Override protected void onSaveInstanceState");\n        String reload = between(activity, "private void reloadProjects()", "private String selectedProjectUrl()");\n        assertTrue(resume.contains("reloadProjects(selectedProjectUrl())"));\n        assertTrue(reload.contains("reloadProjects(store.defaultProjectUrl())"));\n        assertTrue(reload.contains("private void reloadProjects(String preferredUrl)"));\n    }\n\n'''
if extra.strip() in test:
    raise SystemExit("dev6 regression tests already present")
if marker not in test:
    raise SystemExit("attachment regression insertion marker missing")
test_path.write_text(test.replace(marker, extra + marker, 1), encoding="utf-8")

patch(
    "app/build.gradle",
    '''def selfRunDriveVersionCode = 1000045\ndef selfRunDriveVersionName = '1.3.0-dev5'\n''',
    '''def selfRunDriveVersionCode = 1000046\ndef selfRunDriveVersionName = '1.3.0-dev6'\n''',
)

patch(
    "app/src/test/java/com/shaterguy/chatgptselfrun/AttachmentUploadPolicyTest.java",
    '''        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000045"));\n        assertTrue(gradle.contains("selfRunDriveVersionName = '1.3.0-dev5'"));\n''',
    '''        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000046"));\n        assertTrue(gradle.contains("selfRunDriveVersionName = '1.3.0-dev6'"));\n''',
)
