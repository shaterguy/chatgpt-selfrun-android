from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one stale assertion replacement, found {count}: {old!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


attachment = 'app/src/test/java/com/shaterguy/chatgptselfrun/AttachmentUploadPolicyTest.java'
replace_once(
    attachment,
    '        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000042"));',
    '        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000043"));'
)
replace_once(
    attachment,
    '        assertTrue(gradle.contains("selfRunDriveVersionName = \'1.3.0-dev2\'"));',
    '        assertTrue(gradle.contains("selfRunDriveVersionName = \'1.3.0-dev3\'"));'
)

composer = 'app/src/test/java/com/shaterguy/chatgptselfrun/LatestComposerSubmissionPolicyTest.java'
replace_once(
    composer,
    '        assertTrue(script.contains("!__srTurnContained(e)"));',
    '        assertTrue(script.contains("filter(__srMainComposer)"));\n        assertTrue(script.contains("__srTurnContained"));\n        assertTrue(script.contains("__srEditContext"));'
)
replace_once(
    composer,
    '        assertTrue(script.contains("const safeCalibratedComposer=calibratedComposer&&!__srTurnContained(calibratedComposer)?calibratedComposer:null"));',
    '        assertTrue(script.contains("const safeCalibratedComposer=__srMainComposer(calibratedComposer)?calibratedComposer:null"));\n        assertTrue(script.contains("__srEditContext"));'
)

turn_info = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunDriveTurnInfoSourceTest.java'
replace_once(
    turn_info,
    '        assertTrue(service.contains("SelfRunDom.prepareDriveTurn(store.conversationUrl(),prompt,store.commandMarkerId())"));',
    '        assertTrue(service.contains("SelfRunDom.prepareDriveTurn(store.conversationUrl(),prompt,store.commandMarkerId(),conversationFreshnessToken)"));'
)

# Keep the dependency pin and existing fail-closed assertions intact.
attachment_text = Path(attachment).read_text(encoding='utf-8')
composer_text = Path(composer).read_text(encoding='utf-8')
turn_info_text = Path(turn_info).read_text(encoding='utf-8')
if "implementation 'com.google.android.gms:play-services-auth:21.6.0'" not in attachment_text:
    raise SystemExit('attachment dependency pin assertion missing')
if 'assertFalse(script.contains("__srLatestComposer()||calibratedComposer"));' not in composer_text:
    raise SystemExit('unsafe direct calibrated fallback rejection missing')
if 'assertFalse(service.contains("PHASE_READ_NEXT_CONTROL"));' not in turn_info_text:
    raise SystemExit('Drive-only completion-control assertion missing')
