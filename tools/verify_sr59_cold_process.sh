#!/usr/bin/env bash
set -euo pipefail

TARGET=com.shaterguy.chatgptselfrun.drive.test
CANONICAL=release-output/SelfRun-Drive-TEST-v4.0.10-dev4.apk
INSTRUMENTATION_APK=release-output/SelfRun-Drive-TEST-v4.0.10-dev4-cold-androidTest.apk
CLASS=com.shaterguy.chatgptselfrun.ColdProcessPersistenceAndroidTest
mkdir -p cold-process-evidence

adb install -r -t "$CANONICAL"
adb install -r -t "$INSTRUMENTATION_APK"
RUNNER="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TARGET)#\1#p" | head -1)"
test -n "$RUNNER"
printf 'target=%s\nrunner=%s\n' "$TARGET" "$RUNNER" | tee cold-process-evidence/identity.txt
sha256sum "$CANONICAL" | tee -a cold-process-evidence/identity.txt

adb shell am instrument -w -r -e class "$CLASS#seedAcceptedSelectionsBeforeOSProcessDeath" "$RUNNER" | tee cold-process-evidence/seed.txt
grep -Fq 'OK (1 test)' cold-process-evidence/seed.txt
grep -Fq 'test=seedAcceptedSelectionsBeforeOSProcessDeath' cold-process-evidence/seed.txt

adb shell am start -W -n "$TARGET/com.shaterguy.chatgptselfrun.MainActivity" | tee cold-process-evidence/restart-before-stop.txt
sleep 2
before_pid="$(adb shell pidof "$TARGET" | tr -d '\r\n' || true)"
test -n "$before_pid"
printf 'live_app_pid_before_force_stop=%s\n' "$before_pid" | tee -a cold-process-evidence/identity.txt

adb shell am force-stop "$TARGET"
sleep 1
after_pid="$(adb shell pidof "$TARGET" | tr -d '\r\n' || true)"
printf 'app_pid_after_force_stop=%s\n' "${after_pid:-NONE}" | tee -a cold-process-evidence/identity.txt
test -z "$after_pid"

adb shell am instrument -w -r -e class "$CLASS#verifyAfterActualOSForceStopNewProcess" "$RUNNER" | tee cold-process-evidence/readback.txt
grep -Fq 'OK (1 test)' cold-process-evidence/readback.txt
grep -Fq 'test=verifyAfterActualOSForceStopNewProcess' cold-process-evidence/readback.txt
echo 'SR59_COLD_PROCESS_PERSISTENCE_PASS: OS force-stop confirmed; subsequent instrumentation process restored CHAT, WORK, HYBRID and UI selectors' | tee cold-process-evidence/verdict.txt
