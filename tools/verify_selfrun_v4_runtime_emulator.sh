#!/usr/bin/env bash
set -euo pipefail

diagnose_android_emulator() {
  local rc=$?
  echo "::error::Android emulator validation failed (exit=${rc})"
  adb devices -l || true
  timeout 12s adb shell free -m || true
  timeout 12s adb logcat -d -t 500 -s AndroidRuntime:E ActivityManager:E PackageManager:E installd:E system_server:E lmkd:W || true
  exit "$rc"
}
trap diagnose_android_emulator ERR

if [[ "${TEST_ONLY:-false}" == 'true' ]]; then
  bash tools/verify_selfrun_test_update_emulator.sh
else
  bash tools/verify_selfrun3_release_emulator.sh
  cp selfrun-v3-release-runtime-evidence.txt selfrun-upgrade-4.0.8-evidence.txt
  BASE_VERSION_NAME=4.0.0 BASE_VERSION_CODE=4001000 bash tools/verify_selfrun3_release_emulator.sh
  cp selfrun-v3-release-runtime-evidence.txt selfrun-upgrade-4.0.0-evidence.txt
fi
bash tools/verify_last_used_execution_profile_emulator.sh
