from pathlib import Path

path = Path('tools/verify_drive_variant.sh')
text = path.read_text(encoding='utf-8')

replacements = [
    (
        "grep -Fq 'SelfRun3ResultWatchdog.fingerprint(existing.text)' \"$DRIVE\"\n",
        "grep -Fq 'SelfRun3ResultDocumentReader.read' \"$DRIVE\"\n"
        "grep -Fq 'SelfRun3ResultDocumentPolicy.select' \"$DRIVE\"\n"
        "grep -Fq 'SelfRun3ResultWatchdog.fingerprint(selection.rawBody)' \"$DRIVE\"\n"
    ),
    (
        "grep -Fq 'isAppAuthorized' \"$LOOKUP\"\n",
        "! grep -Fq 'isAppAuthorized' \"$LOOKUP\"\n"
        "! grep -Fq 'optBoolean(\"shared\")' \"$LOOKUP\"\n"
    ),
    (
        "grep -Fq 'clearRecoveredDriveWarning(store);' \"$COORD\"\n",
        "grep -Fq 'clearRecoveredDriveWarning(store);' \"$COORD\"\n"
        "grep -Fq 'SelfRun3DriveTokenPolicy.needsRefresh' \"$COORD\"\n"
        "grep -Fq 'retryDriveStepAfterUnauthorized' \"$COORD\"\n"
        "grep -Fq 'ReadTrigger.AUTHORITY' \"$COORD\"\n"
        "grep -Fq 'stage=IMMEDIATE_RETRY' \"$COORD\"\n"
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one verifier match, found {count}: {old!r}')
    text = text.replace(old, new, 1)

if 'RESULT_EXISTS_BEFORE_DISPATCH' in text:
    raise SystemExit('legacy RESULT_EXISTS_BEFORE_DISPATCH verifier unexpectedly present')

path.write_text(text, encoding='utf-8')
print('dev5 verifier updated')
