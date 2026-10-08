#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME required}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
BT="$ANDROID_HOME/build-tools/36.0.0"
SDK="$ANDROID_HOME/platforms/android-36/android.jar"
OUT="$ROOT/build"
mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 8 -target 8 -cp "$SDK" -d "$OUT/classes" "$ROOT/src/UpgradeFixture.java"
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class' -print)
"$BT/d8" --min-api 26 --lib "$SDK" --output "$OUT/dex" "${CLASSES[@]}"
for variant in formal test; do
  TARGET=com.shaterguy.chatgptselfrun.drive
  if [[ "$variant" == test ]]; then TARGET+=.test; fi
  mkdir -p "$OUT/$variant"
  cat > "$OUT/$variant/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.shaterguy.selfrun.upgradefixture.$variant" android:versionCode="3" android:versionName="1.0.2">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="SelfRun isolated upgrade fixture" android:allowBackup="false"/>
<instrumentation android:name="com.shaterguy.selfrun.upgradefixture.UpgradeFixture" android:targetPackage="$TARGET" android:functionalTest="true"/>
</manifest>
XML
  "$BT/aapt" package -f -M "$OUT/$variant/AndroidManifest.xml" -I "$SDK" -F "$OUT/fixture-$variant-unsigned.apk"
  (cd "$OUT/dex" && zip -q -u "$OUT/fixture-$variant-unsigned.apk" classes.dex)
  "$BT/zipalign" -p -f 4 "$OUT/fixture-$variant-unsigned.apk" "$OUT/fixture-$variant-aligned-unsigned.apk"
done
printf '%s\n' 'FIXTURE_BUILD_ONLY_PASS; unsigned outputs are not install deliverables'
