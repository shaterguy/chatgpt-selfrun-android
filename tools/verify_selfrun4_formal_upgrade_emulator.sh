#!/usr/bin/env bash
set -euo pipefail
# This harness only runs on disposable CI emulators, never a user's phone/tablet.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || { echo 'Disposable emulator required' >&2; exit 1; }
FORMAL=com.shaterguy.chatgptselfrun.drive
INSTRUMENTATION="$FORMAL.test/com.shaterguy.chatgptselfrun.SelfRunAndroidTestRunner"
CLASS=com.shaterguy.chatgptselfrun.SelfRun3UpgradePersistenceAndroidTest
CANDIDATE=current/formal.apk
HARNESS=current/formal-androidTest.apk
BT="$ANDROID_HOME/build-tools/36.0.0"
CERT=b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe
version_code() { adb shell dumpsys package "$FORMAL" | tr -d '\r' | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -1; }
uid() { adb shell pm list packages -U "$FORMAL" | tr -d '\r' | sed -n "s/^package:$FORMAL uid:\([0-9]*\)$/\1/p"; }
verify_identity() {
  local apk="$1" code="$2" name="$3" badging cert
  badging="$($BT/aapt dump badging "$apk")"
  grep -Fq "package: name='$FORMAL'" <<<"$badging"
  grep -Fq "versionCode='$code'" <<<"$badging"
  grep -Fq "versionName='$name'" <<<"$badging"
  cert="$($BT/apksigner verify --verbose --print-certs "$apk")"
  grep -Fqi "$CERT" <<<"$cert"
}
verify_identity "$CANDIDATE" 4002006 4.0.6
: > selfrun-v406-formal-upgrade-evidence.txt
while IFS='|' read -r name code apk; do
  verify_identity "$apk" "$code" "$name"
  (( 4002006 > code ))
  adb uninstall "$FORMAL.test" >/dev/null 2>&1 || true
  adb uninstall "$FORMAL" >/dev/null 2>&1 || true
  adb install --no-incremental "$apk" >/dev/null
  [[ "$(version_code)" == "$code" ]]
  before_uid="$(uid)"; test -n "$before_uid"
  adb install --no-incremental "$HARNESS" >/dev/null
  adb shell am instrument -w -r -e class "$CLASS#seedUpgradeState" "$INSTRUMENTATION" | tee current/upgrade-seed.txt
  grep -Fq 'OK (1 test)' current/upgrade-seed.txt
  adb shell am force-stop "$FORMAL"
  # Normal replacement only: no downgrade flag and no data clearing between seed and verify.
  adb install -r --no-incremental "$CANDIDATE" >/dev/null
  [[ "$(version_code)" == '4002006' ]]
  [[ "$(uid)" == "$before_uid" ]]
  adb shell am instrument -w -r -e class "$CLASS#verifyUpgradeState" "$INSTRUMENTATION" | tee current/upgrade-verify.txt
  grep -Fq 'OK (1 test)' current/upgrade-verify.txt
  printf 'UPGRADE_PASS baseline=%s code=%s target=4.0.6 targetCode=4002006 uid=%s data=preferences,runtime-setting,webview-cookie\n' "$name" "$code" "$before_uid" | tee -a selfrun-v406-formal-upgrade-evidence.txt
done <<'BASELINES'
4.0.3|4001003|stable/chatgpt-selfrun-drive-v4.0.3.apk
4.0.4-dev2|4002004|legacy-dev2/SelfRun-Drive-v4.0.4-dev2.apk
4.0.5|4001005|stable/chatgpt-selfrun-drive-v4.0.5.apk
BASELINES

