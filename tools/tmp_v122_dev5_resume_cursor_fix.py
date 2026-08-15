from pathlib import Path
store = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
test = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ResumeDriveTransactionTest.java')
pause = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')

s = store.read_text(encoding='utf-8')
old = 'DriveSignalParser.Event lastProcessed(){return lastProcessed;}int consumedCursor(){return Math.max(0,consumedCursor);}'
new = 'DriveSignalParser.Event lastProcessed(){return lastProcessed;}int consumedCursor(){return Math.max(0,consumedCursor);}int committedCursor(int alreadyConsumed){return Math.max(Math.max(0,alreadyConsumed),consumedCursor());}'
if old not in s:
    raise SystemExit('resume cursor method marker missing')
s = s.replace(old, new, 1)
old = 'tx.observe(postAnchor,pauseAnchorCursor(),totalCount);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",tx.consumedCursor()).putBoolean("active",true)'
new = 'tx.observe(postAnchor,pauseAnchorCursor(),totalCount);int resumeCursor=tx.committedCursor(driveSignalCursor());SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",resumeCursor).putBoolean("active",true)'
if old not in s:
    raise SystemExit('baseline cursor marker missing')
s = s.replace(old, new, 1)
s = s.replace('DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),tx.consumedCursor(),tx.policyEvents())', 'DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),resumeCursor,tx.policyEvents())', 1)
s = s.replace('PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),tx.consumedCursor(),blocking)', 'PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),resumeCursor,blocking)', 1)
store.write_text(s, encoding='utf-8')

t = test.read_text(encoding='utf-8')
marker = '    @Test public void structuralFailureDoesNotAdvancePastLastActuallyProcessedCursor() {'
method = '''    @Test public void noPostAnchorNeverRegressesAlreadyConsumedDurableCursor() {\n        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");\n        tx.observe(Collections.emptyList(), 7, 9);\n        assertEquals(7, tx.consumedCursor());\n        assertEquals(9, tx.committedCursor(9));\n    }\n\n'''
if marker not in t:
    raise SystemExit('resume test marker missing')
t = t.replace(marker, method + marker, 1)
test.write_text(t, encoding='utf-8')

p = pause.read_text(encoding='utf-8')
p = p.replace('assertTrue(baseline.contains("putInt(\\\"driveSignalCursor\\\",tx.consumedCursor())"));', 'assertTrue(baseline.contains("int resumeCursor=tx.committedCursor(driveSignalCursor())"));\n        assertTrue(baseline.contains("putInt(\\\"driveSignalCursor\\\",resumeCursor)"));')
p = p.replace('DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),tx.consumedCursor(),tx.policyEvents())', 'DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),resumeCursor,tx.policyEvents())')
pause.write_text(p, encoding='utf-8')
print('resume durable cursor preservation patch applied')
