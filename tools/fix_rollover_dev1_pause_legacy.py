#!/usr/bin/env python3
from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Preserve PAUSE before a pending rollover and resume the persisted claim only on RESUME.
path = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
p = Path(path)
s = p.read_text(encoding='utf-8')
old = '''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        if (rollover.hasPendingClaim()) {
            startForegroundCompat();
            stopAutomationCallbacks();
            cleanupWebView();
            SelfRunRolloverCoordinator.Result resumed = rollover.resumePending(store);
            if (resumed.started()) adoptSuccessorRuntime();
            else if (rollover.hasPendingClaim()) {
                handler.postDelayed(this::resumePendingRollover, 5_000L);
                return START_STICKY;
            }
        }
        String currentRunId = store.runId();
'''
new = '''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            pauseFromUi();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        boolean resumePendingRollover = ACTION_RESUME.equals(action) && rollover.hasPendingClaim();
        if (resumePendingRollover && store.paused()) {
            stopAutomationCallbacks();
            store.beginManualResumeOverride();
            store.clearLastError();
        }
        if (rollover.hasPendingClaim()) {
            if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }
            startForegroundCompat();
            stopAutomationCallbacks();
            cleanupWebView();
            SelfRunRolloverCoordinator.Result resumed = rollover.resumePending(store);
            if (resumed.started()) adoptSuccessorRuntime();
            else if (rollover.hasPendingClaim()) {
                handler.postDelayed(this::resumePendingRollover, 5_000L);
                return START_STICKY;
            }
        }
        String currentRunId = store.runId();
'''
if s.count(old) != 1:
    raise SystemExit('onStart pending rollover block missing')
s = s.replace(old, new, 1)
old = '''        if (ACTION_PAUSE.equals(action)) { pauseFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
        if (ACTION_RESUME.equals(action)) { resumeFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
'''
new = '''        if (ACTION_RESUME.equals(action)) {
            if (resumePendingRollover) { if (canRun()) handler.post(this::resumeStateMachine); return store.active() ? START_STICKY : START_NOT_STICKY; }
            resumeFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY;
        }
'''
if s.count(old) != 1:
    raise SystemExit('old pause/resume action block missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Record whether the predecessor turn doc is actually an immutable original-requirement record.
path = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRolloverCoordinator.java'
p = Path(path)
s = p.read_text(encoding='utf-8')
old = '''            next.put("predecessorTurnDocumentId", store.turnDocumentId());
            next.put("projectUrl", store.projectUrl());
'''
new = '''            next.put("predecessorTurnDocumentId", store.turnDocumentId());
            next.put("predecessorOriginalRequirementStored", SelfRunSignalTransport.isSignalDocumentRun(app, predecessorRunId));
            next.put("projectUrl", store.projectUrl());
'''
if s.count(old) != 1:
    raise SystemExit('claim predecessor document block missing')
s = s.replace(old, new, 1)
old = '''            lineage.put("predecessorTurnDocumentId", claim.optString("predecessorTurnDocumentId"));
            lineage.put("cause", claim.optString("cause"));
'''
new = '''            lineage.put("predecessorTurnDocumentId", claim.optString("predecessorTurnDocumentId"));
            lineage.put("predecessorOriginalRequirementStored", claim.optBoolean("predecessorOriginalRequirementStored", false));
            lineage.put("cause", claim.optString("cause"));
'''
if s.count(old) != 1:
    raise SystemExit('lineage predecessor block missing')
s = s.replace(old, new, 1)
old = '''        String metadata = "SELF_RUN_PREDECESSOR_RUN_ID=" + lineage.optString("predecessorRunId") + "\\n"
                + "SELF_RUN_PREDECESSOR_JOB_FOLDER_ID=" + lineage.optString("predecessorJobFolderId") + "\\n"
                + "SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID=" + lineage.optString("predecessorTurnDocumentId") + "\\n"
                + "SELF_RUN_ROLLOVER_REASON=" + lineage.optString("cause") + "\\n";
'''
new = '''        boolean predecessorOriginalStored = lineage.optBoolean("predecessorOriginalRequirementStored", false);
        String metadata = "SELF_RUN_PREDECESSOR_RUN_ID=" + lineage.optString("predecessorRunId") + "\\n"
                + "SELF_RUN_PREDECESSOR_JOB_FOLDER_ID=" + lineage.optString("predecessorJobFolderId") + "\\n"
                + "SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID=" + lineage.optString("predecessorTurnDocumentId") + "\\n"
                + "SELF_RUN_PREDECESSOR_ORIGINAL_REQUIREMENT_STORED=" + (predecessorOriginalStored ? "1" : "0") + "\\n"
                + "SELF_RUN_ROLLOVER_REASON=" + lineage.optString("cause") + "\\n";
'''
if s.count(old) != 1:
    raise SystemExit('bootstrap metadata block missing')
s = s.replace(old, new, 1)
old = '''        String handoffInstruction = "\\n\\n이 Run은 이전 SelfRun 작업의 자동 승계 Run이다. 실질 작업을 시작하기 전에 SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID 문서의 본문을 원래 사용자 요구사항 권위 원본으로 읽고, SELF_RUN_PREDECESSOR_JOB_FOLDER_ID 폴더의 관련 실행 문서와 사용 가능한 모든 누적 HANDOFF를 확인한다. 특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고 실제 외부 상태와 대조하여 완료된 작업, 실제 반영 상태, 미완료 작업과 다음 진행 지점을 판정한 뒤 중복 작업 없이 이어서 수행한다.";
'''
new = '''        String originalRequirementInstruction = predecessorOriginalStored
                ? "SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID 문서의 본문을 원래 사용자 요구사항 권위 원본으로 읽는다."
                : "predecessor는 원문 요구사항 저장 기능 도입 전 Run이므로 현재 successor의 [요구사항]과 현재 DRIVE_TURN_DOCUMENT_ID 본문을 원래 사용자 요구사항 권위 원본으로 사용하고 predecessor turn document 본문을 원문 요구사항으로 해석하지 않는다.";
        String handoffInstruction = "\\n\\n이 Run은 이전 SelfRun 작업의 자동 승계 Run이다. 실질 작업을 시작하기 전에 " + originalRequirementInstruction + " SELF_RUN_PREDECESSOR_JOB_FOLDER_ID 폴더의 관련 실행 문서와 사용 가능한 모든 누적 HANDOFF를 확인한다. 특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고 실제 외부 상태와 대조하여 완료된 작업, 실제 반영 상태, 미완료 작업과 다음 진행 지점을 판정한 뒤 중복 작업 없이 이어서 수행한다.";
'''
if s.count(old) != 1:
    raise SystemExit('bootstrap handoff instruction block missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Expand permanent regression checks for pause ordering and legacy predecessor handling.
path = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverWiringTest.java'
p = Path(path)
s = p.read_text(encoding='utf-8')
anchor = '''    @Test public void successorBootstrapCarriesPredecessorReferences() throws Exception {
'''
insert = '''    @Test public void pausePrecedesPendingRolloverAndPausedStickyRunDoesNotResumeIt() throws Exception {
        String service=src("SelfRunService.java");
        String start=between(service,"@Override public int onStartCommand","private void startForegroundCompat");
        assertTrue(start.indexOf("ACTION_PAUSE.equals(action)") < start.indexOf("rollover.hasPendingClaim()"));
        assertTrue(start.contains("if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }"));
        assertTrue(start.contains("resumePendingRollover = ACTION_RESUME.equals(action) && rollover.hasPendingClaim()"));
    }

'''
if s.count(anchor) != 1:
    raise SystemExit('wiring test insertion anchor missing')
s = s.replace(anchor, insert + anchor, 1)
old = '''        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID="));
        assertTrue(coordinator.contains("특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고"));
'''
new = '''        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_ORIGINAL_REQUIREMENT_STORED="));
        assertTrue(coordinator.contains("predecessorOriginalStored"));
        assertTrue(coordinator.contains("predecessor turn document 본문을 원문 요구사항으로 해석하지 않는다"));
        assertTrue(coordinator.contains("특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고"));
'''
if s.count(old) != 1:
    raise SystemExit('bootstrap reference assertions missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

path = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPersistencePolicyTest.java'
p = Path(path)
s = p.read_text(encoding='utf-8')
old = '''        assertTrue(claim.contains("predecessorTurnDocumentId"));
        assertFalse(claim.contains("next.put(\\"requirement\\""));
'''
new = '''        assertTrue(claim.contains("predecessorTurnDocumentId"));
        assertTrue(claim.contains("predecessorOriginalRequirementStored"));
        assertFalse(claim.contains("next.put(\\"requirement\\""));
'''
if s.count(old) != 1:
    raise SystemExit('persistence claim assertions missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

print('pause and legacy rollover corrections applied')
