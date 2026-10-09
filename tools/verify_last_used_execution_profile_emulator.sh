#!/usr/bin/env bash
set -euo pipefail

TARGET="com.shaterguy.chatgptselfrun.drive.test"
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TARGET)#\1#p" | head -1)"
if [[ -z "$INSTRUMENTATION" ]]; then
    echo "No instrumentation runner registered for $TARGET" >&2
    exit 1
fi

adb shell am instrument -w -r \
    -e class com.shaterguy.chatgptselfrun.LastUsedExecutionProfileAndroidTest \
    "$INSTRUMENTATION" | tee selfrun-last-used-profile-runtime-evidence.txt
grep -Fq 'OK (41 tests)' selfrun-last-used-profile-runtime-evidence.txt
grep -Fq 'class=com.shaterguy.chatgptselfrun.LastUsedExecutionProfileAndroidTest' \
    selfrun-last-used-profile-runtime-evidence.txt
