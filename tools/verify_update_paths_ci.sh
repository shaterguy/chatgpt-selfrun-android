#!/usr/bin/env bash
set -euo pipefail

PKG='com.shaterguy.chatgptselfrun.drive'
RC1='apks/rc1/chatgpt-selfrun-drive-v1.2.1.apk'
PERM='android.permission.POST_NOTIFICATIONS'

adb_ready() {
  adb wait-for-device >/dev/null
  local i
  for i in $(seq 1 20); do
    if adb shell true >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

stable_package_dump() {
  adb_ready
  local i dump
  for i in $(seq 1 15); do
    if dump="$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"; then
      if grep -Fq "Package [$PKG]" <<<"$dump"; then
        printf '%s\n' "$dump"
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

value_after_equals() {
  local pattern="$1" text="$2"
  awk -v p="$pattern" '$0 ~ p { sub(/^[^=]*=/, ""); print; exit }' <<<"$text"
}

snapshot_package() {
  local dump line
  dump="$(stable_package_dump)"
  SNAP_VERSION_NAME="$(value_after_equals '^[[:space:]]*versionName=' "$dump")"
  line="$(awk '/^[[:space:]]*versionCode=/{print; exit}' <<<"$dump")"
  SNAP_VERSION_CODE="${line#*=}"
  SNAP_VERSION_CODE="${SNAP_VERSION_CODE%% *}"
  SNAP_UID="$(value_after_equals '^[[:space:]]*userId=' "$dump")"
  SNAP_DATA_DIR="$(value_after_equals '^[[:space:]]*dataDir=' "$dump")"
  SNAP_FIRST_INSTALL="$(value_after_equals '^[[:space:]]*firstInstallTime=' "$dump")"
  printf 'SNAPSHOT_RAW versionName=<%s> versionCode=<%s> uid=<%s> dataDir=<%s> firstInstallTime=<%s>\n' \
    "$SNAP_VERSION_NAME" "$SNAP_VERSION_CODE" "$SNAP_UID" "$SNAP_DATA_DIR" "$SNAP_FIRST_INSTALL" | tee -a update-evidence.txt
  [[ -n "$SNAP_VERSION_NAME" && -n "$SNAP_VERSION_CODE" && -n "$SNAP_UID" && -n "$SNAP_DATA_DIR" && -n "$SNAP_FIRST_INSTALL" ]]
}

notification_granted() {
  local dump
  dump="$(stable_package_dump)"
  grep -F "${PERM}: granted=true" <<<"$dump" >/dev/null
}

launch_app() {
  local label="$1" component
  adb_ready
  component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | tail -1)"
  printf '%s resolved_component=<%s>\n' "$label" "$component" | tee -a update-evidence.txt
  [[ "$component" == "$PKG/"* ]]
  adb shell am start -W -n "$component" > "launch-${label}.txt"
  cat "launch-${label}.txt" | tee -a update-evidence.txt
  grep -Eq 'Status: (ok|OK)' "launch-${label}.txt"
}

install_apk() {
  local apk="$1" label="$2" i output
  adb_ready
  for i in $(seq 1 5); do
    if output="$(adb install -r "$apk" 2>&1)" && grep -Fq 'Success' <<<"$output"; then
      printf '%s install_attempt=%s output=%s\n' "$label" "$i" "$output" | tee -a update-evidence.txt
      adb_ready
      return 0
    fi
    printf '%s install_attempt=%s output=%s\n' "$label" "$i" "$output" | tee -a update-evidence.txt
    sleep 2
    adb_ready || true
  done
  return 1
}

scenario() {
  local base="$1" expected_base="$2" expected_code="$3" label="$4"
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb_ready

  install_apk "$base" "$label-base"
  snapshot_package
  [[ "$SNAP_VERSION_NAME" == "$expected_base" ]]
  [[ "$SNAP_VERSION_CODE" == "$expected_code" ]]
  local before_uid="$SNAP_UID" before_dir="$SNAP_DATA_DIR" before_first="$SNAP_FIRST_INSTALL"

  adb shell pm grant "$PKG" "$PERM"
  notification_granted

  install_apk "$RC1" "$label-target"
  snapshot_package
  [[ "$SNAP_VERSION_NAME" == '1.2.1' ]]
  [[ "$SNAP_VERSION_CODE" == '1000021' ]]
  [[ "$SNAP_UID" == "$before_uid" ]]
  [[ "$SNAP_DATA_DIR" == "$before_dir" ]]
  [[ "$SNAP_FIRST_INSTALL" == "$before_first" ]]
  notification_granted
  launch_app "$label"

  printf '%s UPDATE_PASS base=%s target=1.2.1 uid=%s dataDir=%s firstInstallTime=%s permission=PASS launch=PASS\n' \
    "$label" "$expected_base" "$before_uid" "$before_dir" "$before_first" | tee -a update-evidence.txt
}

scenario 'apks/chatgpt-selfrun-drive-v1.1.0.apk' '1.1.0' '1000009' 'v1.1.0-to-v1.2.1'
scenario 'apks/dev6/chatgpt-selfrun-drive-v1.2.1-dev6.apk' '1.2.1-dev6' '1000020' 'dev6-to-v1.2.1'

grep -Fq 'v1.1.0-to-v1.2.1 UPDATE_PASS' update-evidence.txt
grep -Fq 'dev6-to-v1.2.1 UPDATE_PASS' update-evidence.txt
cat update-evidence.txt
