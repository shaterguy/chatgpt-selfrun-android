from pathlib import Path
p = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
s = p.read_text(encoding='utf-8')
old = 'DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,tx.policyEvents())'
new = 'DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),tx.consumedCursor(),tx.policyEvents())'
count = s.count(old)
if count != 2:
    raise SystemExit(f'expected 2 old source assertions, found {count}')
s = s.replace(old, new)
p.write_text(s, encoding='utf-8')
print('resume source assertions aligned')
