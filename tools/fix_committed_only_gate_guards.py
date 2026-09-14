from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


power_path = Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRun3ResultReadPowerPolicyTest.java")
power = power_path.read_text(encoding="utf-8")
power = replace_once(
    power,
    '        int invalid = drive.indexOf("static boolean isInvalidCommittedResult");',
    '        int end = drive.indexOf("    private SelfRun3Engine.State ensureDocument", release);',
    "power end marker",
)
power = replace_once(
    power,
    '        assertTrue(observe >= 0 && read > observe && acquire > read && release > acquire && invalid > release);',
    '        assertTrue(observe >= 0 && read > observe && acquire > read && release > acquire && end > release);',
    "power ordering assertion",
)
power = replace_once(
    power,
    '        String releaseBlock = drive.substring(release, invalid);',
    '        String releaseBlock = drive.substring(release, end);',
    "power release block",
)
power_path.write_text(power, encoding="utf-8")

verify_path = Path("tools/verify_drive_variant.sh")
verify = verify_path.read_text(encoding="utf-8")
verify = replace_once(
    verify,
    "grep -Fq 'committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks' \"$UNBOUNDED_TEST\"",
    "grep -Fq 'committedResultBeyondFormer512KiBGateIgnoresFormerIdentityGate' \"$UNBOUNDED_TEST\"",
    "variant unbounded test name",
)
verify = replace_once(
    verify,
    "grep -Fq 'SelfRun3StrictJson.parseObject' \"$ENGINE\"\n",
    "grep -Fq 'SelfRun3StrictJson.parseObject' \"$ENGINE\"\ngrep -Fq 'Boolean.TRUE.equals(r.opt(\"committed\"))' \"$ENGINE\"\n",
    "variant committed gate assertion",
)
verify = replace_once(
    verify,
    "grep -Fq 'recordResultBodyObservation(current, finalObservation);' \"$DRIVE\"\n",
    "grep -Fq 'recordResultBodyObservation(current, finalObservation);' \"$DRIVE\"\n! grep -Fq 'isInvalidCommittedResult' \"$DRIVE\"\n! grep -Fq 'INVALID_COMMITTED_RESULT' \"$COORD\"\n",
    "variant forbidden semantic repair assertions",
)
verify_path.write_text(verify, encoding="utf-8")
