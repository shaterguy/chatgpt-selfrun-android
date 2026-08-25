#!/usr/bin/env python3
from pathlib import Path

# First apply the lifecycle/variant policy correction that was verified in the prior CI attempt.
exec(Path('tools/fix_rollover_dev1_policy.py').read_text(encoding='utf-8'), {'__name__': '__main__'})

def replace(path, old, new, expected=1):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    n = s.count(old)
    if n != expected:
        raise SystemExit(f'{path}: expected {expected} matches for {old!r}, got {n}')
    p.write_text(s.replace(old, new), encoding='utf-8')

version_tests = [
    'app/src/test/java/com/shaterguy/chatgptselfrun/AttachmentUploadPolicyTest.java',
    'app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapFiniteStateWiringTest.java',
    'app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapStageAndDirectPickerPolicyTest.java',
    'app/src/test/java/com/shaterguy/chatgptselfrun/TestAppVariantPolicyTest.java',
    'app/src/test/java/com/shaterguy/chatgptselfrun/WebUiCalibrationBackupPolicyTest.java',
]
for path in version_tests:
    replace(path, 'selfRunDriveVersionCode = 1000096', 'selfRunDriveVersionCode = 1000097')
    replace(path, "selfRunDriveVersionName = '1.6.1'", "selfRunDriveVersionName = '1.7.0-dev1'")

path = 'app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java'
p = Path(path)
s = p.read_text(encoding='utf-8')
old = 'String g=read("app/build.gradle","build.gradle"),a=src("SelfRunNewActivity.java");'
new = 'String g=read("app/build.gradle","build.gradle"),a=src("SelfRunNewActivity.java"),runId=src("SelfRunRunId.java");'
if s.count(old) != 1:
    raise SystemExit('DriveVariantPolicyTest source declaration anchor missing')
s = s.replace(old, new, 1)
for old, new in [
    ('assertTrue(a.contains("RUN_SUFFIX_LENGTH = 6"));', 'assertTrue(runId.contains("SUFFIX_LENGTH = 6"));'),
    ('assertTrue(a.contains("Asia/Seoul"));', 'assertTrue(runId.contains("Asia/Seoul"));'),
    ('assertFalse(a.contains("UUID.randomUUID"));', 'assertFalse(runId.contains("UUID.randomUUID"));'),
]:
    if s.count(old) != 1:
        raise SystemExit(f'DriveVariantPolicyTest anchor missing: {old}')
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('rollover dev1 identity expectations corrected')
