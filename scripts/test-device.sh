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
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 && "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" != 27 ]]; then
  echo 'Native import UI probe currently requires API 27 to restore camera-location decisions.' >&2
  exit 1
fi
[[ -f "$project_dir/.build/android/classes.jar" ]] || "$project_dir/scripts/build-android.sh"
rm -rf "$build/classes" "$build/dex"
mkdir -p "$build/classes" "$build/dex"
cat > "$build/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="top.kuaner.pockete2.qa">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
  <application android:label="Pocket E2 QA">
    <activity android:name=".ProviderWarmup" android:theme="@android:style/Theme.NoDisplay" android:exported="true" android:excludeFromRecents="true"/>
    <provider android:name=".ProcessControl" android:authorities="top.kuaner.pockete2.qa.restart-control" android:exported="true"/>
    <provider android:name=".Documents" android:authorities="top.kuaner.pockete2.qa.documents" android:exported="true" android:grantUriPermissions="true" android:permission="android.permission.MANAGE_DOCUMENTS">
      <intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER"/></intent-filter>
    </provider>
  </application>
  <instrumentation android:name="top.kuaner.pockete2.qa.Probe" android:targetPackage="top.kuaner.pockete2" android:functionalTest="true"/>
</manifest>
MANIFEST
"$tools/aapt2" link -I "$android_jar" --manifest "$build/AndroidManifest.xml" -o "$build/base.apk"
test_sources=()
while IFS= read -r -d '' file; do test_sources+=("$file"); done < <(rg --files -0 "$project_dir/tests/device" -g '*.java')
javac -encoding UTF-8 --release 8 -classpath "$android_jar:$project_dir/.build/android/classes.jar" -d "$build/classes" "${test_sources[@]}"
jar cf "$build/tests.jar" -C "$build/classes" .
"$tools/d8" --min-api 26 --lib "$android_jar" --classpath "$project_dir/.build/android/classes.jar" --output "$build/dex" "$build/tests.jar"
(cd "$build/dex" && zip -q "$build/base.apk" ./*.dex)
"$tools/zipalign" -f -p 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/pocket-e2.p12" --ks-key-alias pocket-e2 --ks-pass "file:$sign_dir/password" --out "$build/probe.apk" "$build/aligned.apk"
if [[ "${POCKET_E2_TEST_CAPTURE:-0}" == 1 || "${POCKET_E2_TEST_AMBIENT_ROOM_ONLY:-0}" == 1 ]]; then
  if adb shell dumpsys activity services top.kuaner.recorder | rg -q 'RecordingService|WorkService'; then echo 'E2Studio is recording or working; capture probe refused.' >&2; exit 1; fi
  adb shell pm grant top.kuaner.pockete2 android.permission.RECORD_AUDIO
fi
if [[ "${POCKET_E2_TEST_ROOM_ONLY:-0}" == 1 ]] && adb shell dumpsys activity services top.kuaner.recorder | rg -q 'RecordingService|WorkService'; then
  echo 'E2Studio is recording or working; real-room playback probe refused.' >&2
  exit 1
fi
if [[ "${POCKET_E2_TEST_CAMERA:-0}" == 1 ]]; then adb shell pm grant top.kuaner.pockete2 android.permission.CAMERA; fi
adb install -r "$build/probe.apk"
camera_location_flags=""
cleanup_probe() {
  local restore_failed=0
  if [[ "$camera_location_flags" =~ ^[0-9]+,[0-9]+$ ]]; then
    adb shell CLASSPATH=/data/local/tmp/pocket-e2-qa.apk app_process /system/bin top.kuaner.pockete2.qa.PermissionFlags "$camera_location_flags" || restore_failed=1
  fi
  adb shell rm -f /data/local/tmp/pocket-e2-qa.apk >/dev/null
  adb uninstall top.kuaner.pockete2.qa >/dev/null 2>&1 || true
  return "$restore_failed"
}
trap cleanup_probe EXIT
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 && "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 27 ]]; then
  adb push "$build/probe.apk" /data/local/tmp/pocket-e2-qa.apk >/dev/null
  camera_location_flags="$(adb shell CLASSPATH=/data/local/tmp/pocket-e2-qa.apk app_process /system/bin top.kuaner.pockete2.qa.PermissionFlags | tr -d '\r')"
  [[ "$camera_location_flags" =~ ^[0-9]+,[0-9]+$ ]] || { echo 'Could not preserve native camera location decisions' >&2; exit 1; }
fi
adb shell input keyevent 224
adb shell wm dismiss-keyguard
if [[ "${POCKET_E2_TEST_PROCESS_DEATH:-0}" == 1 ]]; then
  python3 "$project_dir/scripts/test-process-death.py" "$build" | tee "$build/process-death-result.txt"
  exit
fi
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 ]]; then adb shell am start -n top.kuaner.pockete2.qa/.ProviderWarmup >/dev/null; fi
probe_args=(
  -e capture "${POCKET_E2_TEST_CAPTURE:-0}"
  -e camera "${POCKET_E2_TEST_CAMERA:-0}"
  -e importUi "${POCKET_E2_TEST_IMPORT_UI:-0}"
  -e loginOnly "${POCKET_E2_TEST_LOGIN_ONLY:-0}"
  -e roomOnly "${POCKET_E2_TEST_ROOM_ONLY:-0}"
  -e voiceOnly "${POCKET_E2_TEST_VOICE_ONLY:-0}"
  -e locationOnly "${POCKET_E2_TEST_LOCATION_ONLY:-0}"
  -e sightOnly "${POCKET_E2_TEST_SIGHT_ONLY:-0}"
  -e arrivalOnly "${POCKET_E2_TEST_ARRIVAL_ONLY:-0}"
  -e voiceRoomOnly "${POCKET_E2_TEST_VOICE_ROOM_ONLY:-0}"
  -e ambientRoomOnly "${POCKET_E2_TEST_AMBIENT_ROOM_ONLY:-0}"
  -e ambientBrain "${POCKET_E2_TEST_AMBIENT_BRAIN:-0}"
  -e networkRoomOnly "${POCKET_E2_TEST_NETWORK_ROOM_ONLY:-0}"
  -e notifyRoomOnly "${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}"
  -e liveOnly "${POCKET_E2_TEST_LIVE_ONLY:-0}"
  -e outageOnly "${POCKET_E2_TEST_OUTAGE_ONLY:-0}"
  -e pairedTry "${POCKET_E2_TEST_PAIRED_TRY:-0}"
)
adb shell am instrument -w -r "${probe_args[@]}" top.kuaner.pockete2.qa/top.kuaner.pockete2.qa.Probe | tee "$build/result.txt"
rg -q 'result=PASS' "$build/result.txt"
rm -rf "$build/screenshots"
adb pull /sdcard/Android/data/top.kuaner.pockete2/cache/qa "$build/screenshots" >/dev/null
