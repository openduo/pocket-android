#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
tools="$sdk/build-tools/${POCKET_E2_BUILD_TOOLS:-35.0.0}"
android_jar="$sdk/platforms/${POCKET_E2_ANDROID_PLATFORM:-android-35}/android.jar"
build="$project_dir/.build/device-tests"
sign_dir="${POCKET_E2_SIGN_DIR:-$HOME/.local/share/pocket-e2/signing}"
[[ -f "$project_dir/.build/android/classes.jar" ]] || "$project_dir/scripts/build-android.sh"
mkdir -p "$build/classes" "$build/dex"
cat > "$build/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="top.kuaner.pockete2.qa"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Pocket E2 QA"/><instrumentation android:name="top.kuaner.pockete2.qa.Probe" android:targetPackage="top.kuaner.pockete2" android:functionalTest="true"/></manifest>
MANIFEST
"$tools/aapt2" link -I "$android_jar" --manifest "$build/AndroidManifest.xml" -o "$build/base.apk"
javac -encoding UTF-8 --release 8 -classpath "$android_jar:$project_dir/.build/android/classes.jar" -d "$build/classes" "$project_dir/tests/device/Probe.java" "$project_dir/tests/device/SecretProbe.java"
jar cf "$build/tests.jar" -C "$build/classes" .
"$tools/d8" --min-api 26 --lib "$android_jar" --classpath "$project_dir/.build/android/classes.jar" --output "$build/dex" "$build/tests.jar"
(cd "$build/dex" && zip -q "$build/base.apk" ./*.dex)
"$tools/zipalign" -f -p 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/pocket-e2.p12" --ks-key-alias pocket-e2 --ks-pass "file:$sign_dir/password" --out "$build/probe.apk" "$build/aligned.apk"
if [[ "${POCKET_E2_TEST_CAPTURE:-0}" == 1 ]]; then
  if adb shell dumpsys activity services top.kuaner.recorder | rg -q 'RecordingService|WorkService'; then echo 'E2Studio is recording or working; capture probe refused.' >&2; exit 1; fi
  adb shell pm grant top.kuaner.pockete2 android.permission.RECORD_AUDIO
fi
if [[ "${POCKET_E2_TEST_CAMERA:-0}" == 1 ]]; then adb shell pm grant top.kuaner.pockete2 android.permission.CAMERA; fi
adb install -r "$build/probe.apk"
adb shell input keyevent 224
adb shell wm dismiss-keyguard
adb shell am instrument -w -r -e capture "${POCKET_E2_TEST_CAPTURE:-0}" -e camera "${POCKET_E2_TEST_CAMERA:-0}" top.kuaner.pockete2.qa/top.kuaner.pockete2.qa.Probe | tee "$build/result.txt"
rg -q 'result=PASS' "$build/result.txt"
rm -rf "$build/screenshots"
adb pull /sdcard/Android/data/top.kuaner.pockete2/cache/qa "$build/screenshots" >/dev/null
