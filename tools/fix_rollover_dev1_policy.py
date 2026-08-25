#!/usr/bin/env python3
from pathlib import Path

service = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java')
s = service.read_text(encoding='utf-8')
old = 'if (resumed.started()) { adoptSuccessorRuntime(); startForegroundCompat(); resumeStateMachine(); }'
if s.count(old) != 1:
    raise SystemExit(f'resume foreground match count={s.count(old)}')
s = s.replace(old, 'if (resumed.started()) { adoptSuccessorRuntime(); resumeStateMachine(); }', 1)
old = '''        if (result.started()) {
            adoptSuccessorRuntime();
            startForegroundCompat();
            handler.post(this::resumeStateMachine);
            return;
        }
'''
if s.count(old) != 1:
    raise SystemExit(f'rollover foreground match count={s.count(old)}')
s = s.replace(old, '''        if (result.started()) {
            adoptSuccessorRuntime();
            handler.post(this::resumeStateMachine);
            return;
        }
''', 1)
service.write_text(s, encoding='utf-8')

verify = Path('tools/verify_drive_variant.sh')
v = verify.read_text(encoding='utf-8')
old = 'ACTIVITY=$SRC/SelfRunNewActivity.java\n'
if v.count(old) != 1:
    raise SystemExit('activity policy anchor missing')
v = v.replace(old, old + 'RUN_ID=$SRC/SelfRunRunId.java\n', 1)
old = '''grep -Fq 'RUN_SUFFIX_LENGTH = 6' "$ACTIVITY"
grep -Fq 'TimeZone.getTimeZone("Asia/Seoul")' "$ACTIVITY"
! grep -Fq 'UUID.randomUUID' "$ACTIVITY"
'''
new = '''grep -Fq 'SUFFIX_LENGTH = 6' "$RUN_ID"
grep -Fq 'TimeZone.getTimeZone("Asia/Seoul")' "$RUN_ID"
! grep -Fq 'UUID.randomUUID' "$RUN_ID"
'''
if v.count(old) != 1:
    raise SystemExit('run id policy block missing')
v = v.replace(old, new, 1)
old = '[[ "$FG_POST_COUNT" == \'4\' ]]'
if v.count(old) != 1:
    raise SystemExit('foreground count policy missing')
v = v.replace(old, '[[ "$FG_POST_COUNT" == \'5\' ]]', 1)
verify.write_text(v, encoding='utf-8')
print('rollover policy correction applied')
