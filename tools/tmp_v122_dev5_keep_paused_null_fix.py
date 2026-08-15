from pathlib import Path

store_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
store = store_path.read_text(encoding='utf-8')
old_keep = '  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putBoolean("resumeNeedsContinuation",true).putString("phase",PHASE_PAUSED).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}\n'
new_keep = '  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putString("phase",PHASE_PAUSED);if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{e.putBoolean("resumeNeedsContinuation",true).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}}\n'
if store.count(old_keep) != 1:
    raise SystemExit('SelfRunStore KEEP_PAUSED anchor mismatch')
store_path.write_text(store.replace(old_keep, new_keep, 1), encoding='utf-8')

policy_test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveResumePolicyTest.java')
policy_tests = policy_test_path.read_text(encoding='utf-8')
old_ai = '''        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, userAction.action);\n        assertEquals("USER_ACTION_RESUME_PREPARATION_REQUIRED", userAction.reason);\n        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, aiPause.action);\n        assertEquals("AI_PAUSE_REMAINS_LATCHED", aiPause.reason);\n'''
new_ai = '''        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, userAction.action);\n        assertEquals("USER_ACTION_RESUME_PREPARATION_REQUIRED", userAction.reason);\n        assertNull(userAction.event);\n        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, aiPause.action);\n        assertEquals("AI_PAUSE_REMAINS_LATCHED", aiPause.reason);\n        assertNull(aiPause.event);\n'''
if policy_tests.count(old_ai) != 1:
    raise SystemExit('DriveResumePolicyTest AI latch anchor mismatch')
policy_tests = policy_tests.replace(old_ai, new_ai, 1)
old_blocking = '''        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED,\n                DriveResumePolicy.decide(DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 4,\n                        Collections.singletonList(event(DriveSignalParser.Type.PAUSED, 4, false))).action);\n'''
new_blocking = '''        DriveResumePolicy.Decision blocking = DriveResumePolicy.decide(\n                DriveResumePolicy.Origin.EXTERNAL_MANUAL, false, 3, 4,\n                Collections.singletonList(event(DriveSignalParser.Type.PAUSED, 4, false)));\n        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, blocking.action);\n        assertNotNull(blocking.event);\n'''
if policy_tests.count(old_blocking) != 1:
    raise SystemExit('DriveResumePolicyTest blocking event anchor mismatch')
policy_test_path.write_text(policy_tests.replace(old_blocking, new_blocking, 1), encoding='utf-8')

source_test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
source_tests = source_test_path.read_text(encoding='utf-8')
old_stale_assert = '        assertTrue(store.contains("putBoolean(\\"resumeNeedsContinuation\\",true).putString(\\"phase\\",PHASE_PAUSED)"));\n'
new_blocking_assert = '        assertTrue(store.contains("else{e.putBoolean(\\"resumeNeedsContinuation\\",true).putString(\\"status\\",\\"재개 보류 · 더 최신 blocking signal 확인\\")"));\n'
if source_tests.count(old_stale_assert) != 1:
    raise SystemExit('SelfRunPauseResumeTest stale blocking assertion mismatch')
source_tests = source_tests.replace(old_stale_assert, new_blocking_assert, 1)
insert_anchor = '''    @Test public void pauseAnchorIsDurableAndIncludesOriginCursorAndDriveIdentity() throws Exception {\n'''
new_test = '''    @Test public void noMaterialAiLatchKeepsExistingAnchorWithoutTerminalReanchor() throws Exception {\n        String store = src("SelfRunStore.java");\n        String resume = between(store, "void baselineManualResume", "void captureConversationUrl");\n        int nullStart = resume.indexOf("if(blocking==null){");\n        int elseStart = nullStart < 0 ? -1 : resume.indexOf("}else{", nullStart);\n        int reanchorStart = elseStart < 0 ? -1 : resume.indexOf("pauseOriginForDriveSignal(blocking.type)", elseStart);\n        assertTrue(nullStart >= 0);\n        assertTrue(elseStart > nullStart);\n        assertTrue(reanchorStart > elseStart);\n        String nullBranch = resume.substring(nullStart, elseStart);\n        assertTrue(nullBranch.contains("기존 pause latch 유지"));\n        assertFalse(nullBranch.contains("recordPauseAnchor"));\n        assertFalse(nullBranch.contains("terminal(e,blocking)"));\n        assertFalse(nullBranch.contains("resumeNeedsContinuation"));\n        String blockingBranch = resume.substring(elseStart, resume.indexOf("case DONE", elseStart));\n        assertTrue(blockingBranch.contains("resumeNeedsContinuation"));\n        assertTrue(blockingBranch.contains("recordPauseAnchor"));\n        assertTrue(blockingBranch.contains("terminal(e,blocking)"));\n    }\n\n'''
if source_tests.count(insert_anchor) != 1:
    raise SystemExit('SelfRunPauseResumeTest insert anchor mismatch')
source_test_path.write_text(source_tests.replace(insert_anchor, new_test + insert_anchor, 1), encoding='utf-8')
