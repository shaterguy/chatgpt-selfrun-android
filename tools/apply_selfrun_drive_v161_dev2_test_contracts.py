from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace(text, old, new, path):
    if old not in text:
        raise SystemExit(f"{path}: missing expected contract: {old[:80]}")
    return text.replace(old, new)


# Remaining prerelease identity assertions reported by the first remote test run.
for path in [
    "app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapFiniteStateWiringTest.java",
    "app/src/test/java/com/shaterguy/chatgptselfrun/TestAppVariantPolicyTest.java",
    "app/src/test/java/com/shaterguy/chatgptselfrun/WebUiCalibrationBackupPolicyTest.java",
]:
    text = read(path)
    text = text.replace("selfRunDriveVersionCode = 1000091", "selfRunDriveVersionCode = 1000092")
    text = text.replace("selfRunDriveVersionName = '1.6.1-dev1'", "selfRunDriveVersionName = '1.6.1-dev2'")
    write(path, text)

# Common classifier contract: STOP remains composer-scoped, while a strongly identified
# SEND can be recognized in the immediate composer scope for Work layout parity.
path = "app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapSendFallbackPolicyTest.java"
text = read(path)
text = replace(
    text,
    '        assertTrue(prepare.contains("if(!buttonLike(e)||!inComposer(e))return false"));',
    '        assertTrue(prepare.contains("const isStop=e=>!!e&&buttonLike(e)&&inComposer(e)&&stopSemantic(e)"));\n        assertTrue(prepare.contains("const isAdjacentSend=e=>"));\n        assertTrue(prepare.contains("inComposerScope(e)"));',
    path,
)
write(path, text)

path = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationSubmissionVerificationTest.java"
text = read(path)
text = replace(
    text,
    '        assertTrue(js.contains("isStop(e)||isVoice(e)||!inComposer(e)"));\n        assertTrue(js.indexOf("const isVoice=") < js.indexOf("const isSend="));',
    '        assertTrue(js.contains("const isStop=e=>!!e&&buttonLike(e)&&inComposer(e)&&stopSemantic(e)"));\n        assertTrue(js.contains("const isAdjacentSend=e=>"));\n        assertTrue(js.contains("sendSemantic(e)"));\n        assertTrue(js.indexOf("const isVoice=") < js.indexOf("const isSend="));',
    path,
)
write(path, text)

path = "app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapSendLivenessPolicyTest.java"
text = read(path)
text = replace(
    text,
    '        assertFalse(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));',
    '        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));',
    path,
)
write(path, text)

print("SelfRun Drive v1.6.1-dev2 test contracts aligned")
