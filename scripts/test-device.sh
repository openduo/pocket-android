#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
tools="$sdk/build-tools/${POCKET_E2_BUILD_TOOLS:-35.0.0}"
android_jar="$sdk/platforms/${POCKET_E2_ANDROID_PLATFORM:-android-35}/android.jar"
build="$project_dir/.build/device-tests"
sign_dir="${POCKET_SIGN_DIR:-$HOME/.local/share/pocket-android/signing}"
target_package="dev.openduo.pocket"
probe_package="dev.openduo.pocket.qa"
sign_file="pocket.p12"
sign_alias="pocket"
if [[ "${POCKET_LEGACY_IDENTITY:-0}" == 1 ]]; then
  target_package="top.kuaner.pockete2"
  probe_package="top.kuaner.pockete2.qa"
  sign_dir="${POCKET_E2_SIGN_DIR:-$HOME/.local/share/pocket-e2/signing}"
  sign_file="pocket-e2.p12"
  sign_alias="pocket-e2"
  if [[ "${POCKET_TEST_SERVICE_READINESS_ONLY:-0}${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}${POCKET_E2_TEST_VOICE_ROOM_ONLY:-0}${POCKET_E2_TEST_AMBIENT_ROOM_ONLY:-0}${POCKET_E2_TEST_SIGHT_ROOM_ONLY:-0}${POCKET_E2_TEST_BUILD_ONLY:-0}${POCKET_TEST_INPUT_ONLY:-0}${POCKET_TEST_COORDINATION_ONLY:-0}${POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY:-0}" != *1* ]]; then
    echo 'Select a targeted service check for the paired legacy app; the full historical suite is disabled.' >&2
    exit 1
  fi
  for flag in "${!POCKET_E2_TEST_@}" "${!POCKET_TEST_@}" POCKET_CAPTURE_SHOWCASE; do
    [[ "${!flag:-0}" == 1 ]] || continue
    case "$flag" in
      POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY|POCKET_TEST_COORDINATION_ONLY|POCKET_TEST_INPUT_ONLY|POCKET_TEST_SERVICE_READINESS_ONLY|POCKET_E2_TEST_NOTIFY_ROOM_ONLY|POCKET_E2_TEST_VOICE_ROOM_ONLY|POCKET_E2_TEST_AMBIENT_ROOM_ONLY|POCKET_E2_TEST_SIGHT_ROOM_ONLY|POCKET_E2_TEST_BUILD_ONLY|POCKET_E2_TEST_AMBIENT_BRAIN|POCKET_E2_TEST_CAPTURE|POCKET_E2_TEST_CAMERA) ;;
      *) echo "Unsupported legacy service option: $flag" >&2; exit 1 ;;
    esac
  done
fi
if [[ "${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}" == 1 ]]; then
  case "${POCKET_TEST_NOTIFY_TARGET:-}" in
    ambient:?*|job:?*) ;;
    *) echo 'Select a dedicated ambient/keepalive test session with POCKET_TEST_NOTIFY_TARGET.' >&2; exit 1 ;;
  esac
fi
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 && "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" != 27 ]]; then
  echo 'Native import UI probe currently requires API 27 to restore camera-location decisions.' >&2
  exit 1
fi
[[ -f "$project_dir/.build/android/classes.jar" ]] || "$project_dir/scripts/build-android.sh"
rm -rf "$build/classes" "$build/dex"
mkdir -p "$build/classes" "$build/dex"
cat > "$build/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.openduo.pocket.qa">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
  <application android:label="DuoDuo Pocket QA">
    <activity android:name=".ProviderWarmup" android:theme="@android:style/Theme.NoDisplay" android:exported="true" android:excludeFromRecents="true"/>
    <provider android:name=".ProcessControl" android:authorities="dev.openduo.pocket.qa.restart-control" android:exported="true"/>
    <provider android:name=".Documents" android:authorities="dev.openduo.pocket.qa.documents" android:exported="true" android:grantUriPermissions="true" android:permission="android.permission.MANAGE_DOCUMENTS">
      <intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER"/></intent-filter>
    </provider>
  </application>
  <instrumentation android:name="dev.openduo.pocket.qa.Probe" android:targetPackage="dev.openduo.pocket" android:functionalTest="true"/>
</manifest>
MANIFEST
python3 - "$build/AndroidManifest.xml" "$probe_package" "$target_package" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text().replace('package="dev.openduo.pocket.qa"', 'package="' + sys.argv[2] + '"')
for name in ('ProviderWarmup', 'ProcessControl', 'Documents'):
    text = text.replace('android:name=".' + name + '"', 'android:name="dev.openduo.pocket.qa.' + name + '"')
text = text.replace('android:targetPackage="dev.openduo.pocket"', 'android:targetPackage="' + sys.argv[3] + '"')
text = text.replace('android:authorities="dev.openduo.pocket.qa.', 'android:authorities="' + sys.argv[2] + '.')
p.write_text(text)
PY
"$tools/aapt2" link -I "$android_jar" --manifest "$build/AndroidManifest.xml" -o "$build/base.apk"
test_sources=()
while IFS= read -r -d '' file; do test_sources+=("$file"); done < <(rg --files -0 "$project_dir/tests/device" -g '*.java')
javac -encoding UTF-8 --release 8 -classpath "$android_jar:$project_dir/.build/android/classes.jar" -d "$build/classes" "${test_sources[@]}"
jar cf "$build/tests.jar" -C "$build/classes" .
"$tools/d8" --min-api 26 --lib "$android_jar" --classpath "$project_dir/.build/android/classes.jar" --output "$build/dex" "$build/tests.jar"
(cd "$build/dex" && zip -q "$build/base.apk" ./*.dex)
"$tools/zipalign" -f -p 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/$sign_file" --ks-key-alias "$sign_alias" --ks-pass "file:$sign_dir/password" --out "$build/probe.apk" "$build/aligned.apk"
if [[ "${POCKET_E2_TEST_CAPTURE:-0}" == 1 || "${POCKET_E2_TEST_AMBIENT_ROOM_ONLY:-0}" == 1 || "${POCKET_E2_TEST_VOICE_DEATH:-0}" == 1 ]]; then
  if adb shell dumpsys activity services top.kuaner.recorder | rg -q 'RecordingService|WorkService'; then echo 'E2Studio is recording or working; capture probe refused.' >&2; exit 1; fi
  adb shell pm grant "$target_package" android.permission.RECORD_AUDIO
fi
if [[ "${POCKET_E2_TEST_ROOM_ONLY:-0}" == 1 ]] && adb shell dumpsys activity services top.kuaner.recorder | rg -q 'RecordingService|WorkService'; then
  echo 'E2Studio is recording or working; real-room playback probe refused.' >&2
  exit 1
fi
if [[ "${POCKET_E2_TEST_CAMERA:-0}" == 1 ]]; then adb shell pm grant "$target_package" android.permission.CAMERA; fi
adb install -r "$build/probe.apk"
if [[ "${POCKET_E2_TEST_BUILD_ONLY:-0}" == 1 ]]; then exit; fi
camera_location_flags=""
resume_after_probe=0
cleanup_probe() {
  local restore_failed=0
  if [[ "$camera_location_flags" =~ ^[0-9]+,[0-9]+$ ]]; then
    adb shell CLASSPATH=/data/local/tmp/pocket-e2-qa.apk app_process /system/bin dev.openduo.pocket.qa.PermissionFlags "$camera_location_flags" || restore_failed=1
  fi
  adb shell rm -f /data/local/tmp/pocket-e2-qa.apk >/dev/null
  adb uninstall "$probe_package" >/dev/null 2>&1 || true
  if [[ "$resume_after_probe" == 1 ]]; then
    # Instrumentation ends the target process; leave the paired app available to the user.
    adb shell am start -n "$target_package/dev.openduo.pocket.app.MainActivity" >/dev/null || restore_failed=1
  fi
  return "$restore_failed"
}
trap cleanup_probe EXIT
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 && "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 27 ]]; then
  adb push "$build/probe.apk" /data/local/tmp/pocket-e2-qa.apk >/dev/null
  camera_location_flags="$(adb shell CLASSPATH=/data/local/tmp/pocket-e2-qa.apk app_process /system/bin dev.openduo.pocket.qa.PermissionFlags | tr -d '\r')"
  [[ "$camera_location_flags" =~ ^[0-9]+,[0-9]+$ ]] || { echo 'Could not preserve native camera location decisions' >&2; exit 1; }
fi
adb shell input keyevent 224
adb shell wm dismiss-keyguard
if [[ "${POCKET_E2_TEST_PROCESS_DEATH:-0}" == 1 ]]; then
  python3 "$project_dir/scripts/test-process-death.py" "$build" | tee "$build/process-death-result.txt"
  exit
fi
if [[ "${POCKET_E2_TEST_OUTBOX_DEATH:-0}" == 1 ]]; then
  python3 "$project_dir/scripts/test-outbox-death.py" "$build" | tee "$build/outbox-death-result.txt"
  exit
fi
if [[ "${POCKET_E2_TEST_VOICE_DEATH:-0}" == 1 ]]; then
  python3 "$project_dir/scripts/test-voice-death.py" "$build" | tee "$build/voice-death-result.txt"
  exit
fi
if [[ "${POCKET_E2_TEST_IMPORT_UI:-0}" == 1 ]]; then adb shell am start -n dev.openduo.pocket.qa/.ProviderWarmup >/dev/null; fi
probe_args=(
  -e lockscreenPlaybackOnly "${POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY:-0}"
  -e showcaseOnly "${POCKET_CAPTURE_SHOWCASE:-0}"
  -e showcaseLanguage "${POCKET_SHOWCASE_LANGUAGE:-en}"
  -e showcaseTheme "${POCKET_SHOWCASE_THEME:-light}"
  -e inputOnly "${POCKET_TEST_INPUT_ONLY:-0}"
  -e coordinationOnly "${POCKET_TEST_COORDINATION_ONLY:-0}"
  -e hardwareSmokeOnly "${POCKET_TEST_HARDWARE_SMOKE:-0}"
  -e serviceReadinessOnly "${POCKET_TEST_SERVICE_READINESS_ONLY:-0}"
  -e windowOnly "${POCKET_E2_TEST_WINDOW_ONLY:-0}"
  -e screenReaderOnly "${POCKET_E2_TEST_SCREEN_READER_ONLY:-0}"
  -e locationServiceOnly "${POCKET_E2_TEST_LOCATION_SERVICE_ONLY:-0}"
  -e uiOnly "${POCKET_E2_TEST_UI_ONLY:-0}"
  -e uiLanguage "${POCKET_E2_TEST_UI_LANGUAGE:-en}"
  -e uiTheme "${POCKET_E2_TEST_UI_THEME:-dark}"
  -e capture "${POCKET_E2_TEST_CAPTURE:-0}"
  -e camera "${POCKET_E2_TEST_CAMERA:-0}"
  -e importUi "${POCKET_E2_TEST_IMPORT_UI:-0}"
  -e loginOnly "${POCKET_E2_TEST_LOGIN_ONLY:-0}"
  -e roomOnly "${POCKET_E2_TEST_ROOM_ONLY:-0}"
  -e voiceOnly "${POCKET_E2_TEST_VOICE_ONLY:-0}"
  -e voiceStorageOnly "${POCKET_E2_TEST_VOICE_STORAGE_ONLY:-0}"
  -e upgradeOnly "${POCKET_E2_TEST_UPGRADE_ONLY:-0}"
  -e backgroundVoiceOnly "${POCKET_E2_TEST_BACKGROUND_VOICE_ONLY:-0}"
  -e interruptionOnly "${POCKET_E2_TEST_INTERRUPTION_ONLY:-0}"
  -e readFailureOnly "${POCKET_E2_TEST_READ_FAILURE_ONLY:-0}"
  -e historyOnly "${POCKET_E2_TEST_HISTORY_ONLY:-0}"
  -e audioBurstOnly "${POCKET_E2_TEST_AUDIO_BURST_ONLY:-0}"
  -e locationOnly "${POCKET_E2_TEST_LOCATION_ONLY:-0}"
  -e sightOnly "${POCKET_E2_TEST_SIGHT_ONLY:-0}"
  -e sightRoomOnly "${POCKET_E2_TEST_SIGHT_ROOM_ONLY:-0}"
  -e arrivalOnly "${POCKET_E2_TEST_ARRIVAL_ONLY:-0}"
  -e voiceRoomOnly "${POCKET_E2_TEST_VOICE_ROOM_ONLY:-0}"
  -e ambientRoomOnly "${POCKET_E2_TEST_AMBIENT_ROOM_ONLY:-0}"
  -e ambientBrain "${POCKET_E2_TEST_AMBIENT_BRAIN:-0}"
  -e networkRoomOnly "${POCKET_E2_TEST_NETWORK_ROOM_ONLY:-0}"
  -e requestRoomOnly "${POCKET_E2_TEST_REQUEST_ROOM_ONLY:-0}"
  -e accessOnly "${POCKET_E2_TEST_ACCESS_ONLY:-0}"
  -e accessibilityOnly "${POCKET_E2_TEST_ACCESSIBILITY_ONLY:-0}"
  -e notifyRoomOnly "${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}"
  -e liveOnly "${POCKET_E2_TEST_LIVE_ONLY:-0}"
  -e outageOnly "${POCKET_E2_TEST_OUTAGE_ONLY:-0}"
  -e pairedTry "${POCKET_E2_TEST_PAIRED_TRY:-0}"
)
if [[ -n "${POCKET_TEST_NOTIFY_TARGET:-}" ]]; then
  probe_args+=(-e notifyTargetBase64 "$(printf '%s' "$POCKET_TEST_NOTIFY_TARGET" | base64 | tr -d '\r\n')")
fi
if [[ "${POCKET_TEST_SERVICE_READINESS_ONLY:-0}" == 1 || "${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}" == 1 || "${POCKET_TEST_INPUT_ONLY:-0}" == 1 || "${POCKET_TEST_COORDINATION_ONLY:-0}" == 1 || "${POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY:-0}" == 1 ]]; then
  resume_after_probe=1
fi
adb shell am instrument -w -r "${probe_args[@]}" "$probe_package/dev.openduo.pocket.qa.Probe" | tee "$build/result.txt"
rg -q 'result=PASS' "$build/result.txt"
rm -rf "$build/screenshots"
if [[ "${POCKET_CAPTURE_SHOWCASE:-0}" == 1 ]]; then
  rm -rf "$build/showcase"
  adb pull "/sdcard/Android/data/$target_package/cache/showcase" "$build/showcase" >/dev/null
elif [[ "${POCKET_TEST_SERVICE_READINESS_ONLY:-0}" == 1 ]]; then
  adb pull "/sdcard/Android/data/$target_package/cache/service-readiness" "$build/service-readiness" >/dev/null
elif [[ "${POCKET_TEST_HARDWARE_SMOKE:-0}" != 1 && "${POCKET_E2_TEST_NOTIFY_ROOM_ONLY:-0}" != 1 && "${POCKET_TEST_INPUT_ONLY:-0}" != 1 && "${POCKET_TEST_COORDINATION_ONLY:-0}" != 1 && "${POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY:-0}" != 1 ]]; then
  adb pull "/sdcard/Android/data/$target_package/cache/qa" "$build/screenshots" >/dev/null
fi
