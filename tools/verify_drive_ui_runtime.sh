#!/usr/bin/env bash
# Same emulator, same source; collect real Android UI evidence before co-install verification.
set -euo pipefail
set +e
gradle --build-cache --no-daemon --console=plain :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$TEST_INSTRUMENTATION_CLASS
INSTRUMENTATION_RESULT=$?
adb pull /sdcard/Android/data/com.shaterguy.chatgptselfrun.drive/files/ui-evidence ui-evidence
PULL_RESULT=$?
set -e
if [[ "$PULL_RESULT" == 0 ]]; then
  echo "SOURCE_SHA=$GITHUB_SHA" >> ui-evidence/manifest.txt
  echo "SCREENSHOT_VARIANT=debug; CANONICAL_APK_VARIANT=qaApp; SAME_SOURCE=true" >> ui-evidence/manifest.txt
  find ui-evidence -name '*.png' -type f -print0 | sort -z | xargs -0 sha256sum > ui-evidence/SHA256SUMS.txt
fi
test "$INSTRUMENTATION_RESULT" -eq 0
test "$PULL_RESULT" -eq 0
test -s ui-evidence/manifest.txt
adb shell dumpsys webviewupdate > drive-test-webview-evidence.txt
cat drive-test-webview-evidence.txt
FORMAL_EXPECTED_VERSION="2.3.1" TEST_PREV_EXPECTED_VERSION="$TEST_PREV_VERSION_NAME" TEST_EXPECTED_VERSION="$TEST_VERSION_NAME" timeout --foreground 6m bash tools/verify_drive_test_coinstall_emulator.sh formal-baseline/chatgpt-selfrun-drive-v2.3.1.apk predecessor/previous-test.apk current-candidate/current-test.apk > drive-test-coinstall-evidence.txt 2>&1
cat drive-test-coinstall-evidence.txt
