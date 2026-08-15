from pathlib import Path

p = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
s = p.read_text(encoding='utf-8')
old = 'assertTrue(store.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,postAnchor)"));'
new = 'assertTrue(store.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,tx.policyEvents())"));'
if old not in s:
    raise SystemExit('stale resume policy assertion not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('resume source assertion aligned')
