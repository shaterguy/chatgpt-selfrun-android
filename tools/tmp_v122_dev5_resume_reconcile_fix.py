from pathlib import Path

policy_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/DriveResumePolicy.java')
policy = policy_path.read_text(encoding='utf-8')
old_sig = """    static Decision decide(Origin origin, int anchorCursor, int totalCount,
                           List<DriveSignalParser.Event> postAnchorEvents) {
"""
new_sig = """    static Decision decide(Origin origin, int anchorCursor, int totalCount,
                           List<DriveSignalParser.Event> postAnchorEvents) {
        return decide(origin, true, anchorCursor, totalCount, postAnchorEvents);
    }

    static Decision decide(Origin origin, boolean needsContinuation, int anchorCursor, int totalCount,
                           List<DriveSignalParser.Event> postAnchorEvents) {
"""
if policy.count(old_sig) != 1:
    raise SystemExit('DriveResumePolicy signature anchor mismatch')
policy = policy.replace(old_sig, new_sig, 1)
old_external = '                case EXTERNAL_MANUAL -> new Decision(Action.CONTINUE, "EXTERNAL_MANUAL_ACTION_COMPLETE", null);\n'
new_external = '                case EXTERNAL_MANUAL -> needsContinuation ? new Decision(Action.CONTINUE, "EXTERNAL_MANUAL_ACTION_COMPLETE", null) : new Decision(Action.RESTORE_PHASE, "EXTERNAL_MANUAL_NO_CONTINUATION_REQUIRED", null);\n'
if policy.count(old_external) != 1:
    raise SystemExit('external no-material policy anchor mismatch')
policy_path.write_text(policy.replace(old_external, new_external, 1), encoding='utf-8')

store_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
store = store_path.read_text(encoding='utf-8')
old_helper = '    static boolean restorePauseWithoutDrive(String origin,boolean needsContinuation,boolean hasTurnDocument){if(PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation)return true;return PAUSE_ORIGIN_UI_MANUAL.equals(origin)&&!hasTurnDocument;}\n'
new_helper = '    static boolean restorePauseWithoutDrive(String origin,boolean needsContinuation,boolean hasTurnDocument){if(hasTurnDocument)return false;if(PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation)return true;return PAUSE_ORIGIN_UI_MANUAL.equals(origin);}\n'
if store.count(old_helper) != 1:
    raise SystemExit('restorePauseWithoutDrive anchor mismatch')
store = store.replace(old_helper, new_helper, 1)
old_decision = ' DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());DriveResumePolicy.Decision decision=DriveResumePolicy.decide(origin,pauseAnchorCursor(),totalCount,postAnchor);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,totalCount)).putBoolean("active",true).putBoolean("userStopped",false).putLong("phaseStartedAt",System.currentTimeMillis());putLatest(e,latest);\n'
new_decision = ' DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());DriveResumePolicy.Decision decision=DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,postAnchor);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,totalCount)).putBoolean("active",true).putBoolean("userStopped",false).putLong("phaseStartedAt",System.currentTimeMillis());putLatest(e,latest);\n'
if store.count(old_decision) != 1:
    raise SystemExit('baseline decision anchor mismatch')
store = store.replace(old_decision, new_decision, 1)
old_restore = '  case RESTORE_PHASE->{String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요");}else{e.putBoolean("paused",false).putString("phase",restore).putString("status","UI 일시정지 해제 · 기존 phase 복귀");clearPauseAnchor(e);}}\n'
new_restore = '  case RESTORE_PHASE->{String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요");}else{String restoredStatus=origin==DriveResumePolicy.Origin.UI_MANUAL?"UI 일시정지 해제 · 기존 phase 복귀":"외부 수동조치 완료 · 기존 phase 복귀";e.putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",restore).putString("status",restoredStatus);clearPauseAnchor(e);}}\n'
if store.count(old_restore) != 1:
    raise SystemExit('RESTORE_PHASE anchor mismatch')
store = store.replace(old_restore, new_restore, 1)
old_keep = '  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}\n'
new_keep = '  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putBoolean("resumeNeedsContinuation",true).putString("phase",PHASE_PAUSED).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}\n'
if store.count(old_keep) != 1:
    raise SystemExit('KEEP_PAUSED anchor mismatch')
store_path.write_text(store.replace(old_keep, new_keep, 1), encoding='utf-8')

test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveResumePolicyTest.java')
tests = test_path.read_text(encoding='utf-8')
old_test = """    @Test public void localPrerequisitePauseRestoresWithoutDriveButDrivePausedStillReconciles() {
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, false));
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, true));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, true, true));
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, false));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, true));
    }

"""
new_test = """    @Test public void localPrerequisiteDirectRestoreOnlyBeforeTurnDocumentExists() {
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, false));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, true));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, true, true));
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, false));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, true));
    }

    @Test public void existingDocumentExternalNoMaterialUsesNeedsContinuationFallback() {
        DriveResumePolicy.Decision local = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 3, Collections.emptyList());
        DriveResumePolicy.Decision drivePaused = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.EXTERNAL_MANUAL, true, 3, 3, Collections.emptyList());
        assertEquals(DriveResumePolicy.Action.RESTORE_PHASE, local.action);
        assertEquals("EXTERNAL_MANUAL_NO_CONTINUATION_REQUIRED", local.reason);
        assertEquals(DriveResumePolicy.Action.CONTINUE, drivePaused.action);
        assertEquals("EXTERNAL_MANUAL_ACTION_COMPLETE", drivePaused.reason);
    }

    @Test public void existingDocumentExternalMaterialSignalOverridesFallback() {
        assertEquals(DriveResumePolicy.Action.APPLY_COMPLETION,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.TURN_COMPLETED, 4, false))).action);
        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.PAUSED, 4, false))).action);
        assertEquals(DriveResumePolicy.Action.DONE,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.DONE, 4, false))).action);
    }

"""
if tests.count(old_test) != 1:
    raise SystemExit('DriveResumePolicyTest local prerequisite anchor mismatch')
test_path.write_text(tests.replace(old_test, new_test, 1), encoding='utf-8')

source_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
source = source_path.read_text(encoding='utf-8')
old_source_bits = '''        assertTrue(store.contains("PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation"));
        assertTrue(store.contains("PAUSE_ORIGIN_UI_MANUAL.equals(origin)&&!hasTurnDocument"));
'''
new_source_bits = '''        assertTrue(store.contains("if(hasTurnDocument)return false"));
        assertTrue(store.contains("PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation"));
        assertTrue(store.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,postAnchor)"));
        assertTrue(store.contains("putBoolean(\\"resumeNeedsContinuation\\",true).putString(\\"phase\\",PHASE_PAUSED)"));
'''
if source.count(old_source_bits) != 1:
    raise SystemExit('SelfRunPauseResumeTest anchor mismatch')
source_path.write_text(source.replace(old_source_bits, new_source_bits, 1), encoding='utf-8')

runtime_path = Path('docs/SELF_RUN_DRIVE_RUNTIME.md')
runtime = runtime_path.read_text(encoding='utf-8')
old_runtime = '반면 ChatGPT 로그인·OAuth·Drive 재연결 같은 앱 내부 prerequisite pause는 `resumeNeedsContinuation=false`를 영속하고 Resume 시 `pauseAnchorPhase`로 직접 복귀하여 bootstrap 전이나 turn document 생성 전에도 빈 document를 polling하지 않습니다.'
new_runtime = '반면 ChatGPT 로그인·OAuth·Drive 재연결 같은 앱 내부 prerequisite pause는 `resumeNeedsContinuation=false`를 영속합니다. turn document가 아직 없으면 Resume 시 `pauseAnchorPhase`로 직접 복귀하여 빈 document를 polling하지 않습니다. turn document가 이미 있으면 반드시 pause anchor 이후 Drive signal을 먼저 reconcile하고, 새 material signal이 없을 때만 `pauseAnchorPhase`를 복구합니다.'
if runtime.count(old_runtime) != 1:
    raise SystemExit('runtime resume sentence mismatch')
runtime_path.write_text(runtime.replace(old_runtime, new_runtime, 1), encoding='utf-8')

protocol_path = Path('docs/SELF_RUN_DRIVE_V1_PROTOCOL.md')
protocol = protocol_path.read_text(encoding='utf-8')
old_protocol = '- 외부 수동 행동 origin은 새 completion이 없을 때만 plain continuation을 허용합니다.'
new_protocol = '- 외부 수동 행동 origin은 Drive `PAUSED`처럼 `resumeNeedsContinuation=true`이면 새 material signal이 없을 때 plain continuation을 허용합니다. 앱 내부 prerequisite의 `resumeNeedsContinuation=false`는 turn document가 있으면 post-anchor를 먼저 reconcile한 뒤 새 material signal이 없을 때 `pausedFromPhase`를 복구합니다.'
if protocol.count(old_protocol) != 1:
    raise SystemExit('protocol external manual sentence mismatch')
protocol_path.write_text(protocol.replace(old_protocol, new_protocol, 1), encoding='utf-8')
