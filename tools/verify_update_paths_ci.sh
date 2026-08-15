#!/usr/bin/env bash
set -euo pipefail

PKG='com.shaterguy.chatgptselfrun.drive'
RC1='apks/rc1/chatgpt-selfrun-drive-v1.2.1.apk'
PERM='android.permission.POST_NOTIFICATIONS'

field() {
  local key="$1"
  adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n "s/^[[:space:]]*${key}=//p" | head -1
}

uid_value() {
  adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^[[:space:]]*userId=//p' | head -1
}

version_name() {
  adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1
}

version_code() {
  adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1
}

notification_granted() {
  adb shell dumpsys package "$PKG" | tr -d '\r' | grep -F "${PERM}: granted=true" >/dev/null
}

launch_app() {
  local label="$1"
  local component
  component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)"
  [[ "$component" == "$PKG/"* ]]
  adb shell am start -W -n "$component" > "launch-${label}.txt"
  grep -Eq 'Status: (ok|OK)' "launch-${label}.txt"
}

scenario() {
  local base="$1"
  local expected_base="$2"
  local label="$3"

  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -r "$base" | tee -a update-evidence.txt
  [[ "$(version_name)" == "$expected_base" ]]

  local before_uid before_dir before_first
  before_uid="$(uid_value)"
  before_dir="$(field dataDir)"
  before_first="$(field firstInstallTime)"
  [[ -n "$before_uid" && -n "$before_dir" && -n "$before_first" ]]

  adb shell pm grant "$PKG" "$PERM"
  notification_granted

  adb install -r "$RC1" | tee -a update-evidence.txt
  [[ "$(version_name)" == '1.2.1' ]]
  [[ "$(version_code)" == '1000021' ]]
  [[ "$(uid_value)" == "$before_uid" ]]
  [[ "$(field dataDir)" == "$before_dir" ]]
  [[ "$(field firstInstallTime)" == "$before_first" ]]
  notification_granted
  launch_app "$label"

  printf '%s UPDATE_PASS base=%s target=1.2.1 uid=%s dataDir=%s firstInstallTime=%s permission=PASS launch=PASS\n' \
    "$label" "$expected_base" "$before_uid" "$before_dir" "$before_first" | tee -a update-evidence.txt
}

scenario 'apks/chatgpt-selfrun-drive-v1.1.0.apk' '1.1.0' 'v1.1.0-to-v1.2.1'
scenario 'apks/dev6/chatgpt-selfrun-drive-v1.2.1-dev6.apk' '1.2.1-dev6' 'dev6-to-v1.2.1'

grep -Fq 'v1.1.0-to-v1.2.1 UPDATE_PASS' update-evidence.txt
grep -Fq 'dev6-to-v1.2.1 UPDATE_PASS' update-evidence.txt
cat update-evidence.txt
