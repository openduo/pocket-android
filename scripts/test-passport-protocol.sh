#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
# Runs the same upstream vectors on Android/ART. No APK install, Bluetooth, app data or room I/O.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
serial="${ANDROID_SERIAL:?Set ANDROID_SERIAL explicitly for this protocol-only check}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
build="$project_dir/.build/passport-device"
remote=/data/local/tmp/pocket-passport-protocol
mkdir -p "$build/classes" "$build/dex"
(cd "$project_dir" && ./gradlew :core:jar --console=plain)
json="$project_dir/.build/test-deps/json-20250517.jar"
mkdir -p "$(dirname "$json")"
if [[ ! -f "$json" ]]; then curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar -o "$json"; fi
[[ "$(shasum -a 256 "$json" | cut -d' ' -f1)" == 3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796 ]] || { echo 'JSON dependency checksum mismatch' >&2; exit 1; }
javac --release 8 -encoding UTF-8 -classpath "$json:$project_dir/core/build/libs/core.jar" \
    -d "$build/classes" "$project_dir/tests/host/PassportContracts.java"
jar cf "$build/runner.jar" -C "$build/classes" .
"$sdk/build-tools/35.0.0/d8" --lib "$sdk/platforms/android-35/android.jar" --min-api 26 --output "$build/dex" \
    "$build/runner.jar" "$project_dir/core/build/libs/core.jar" "$project_dir"/android/libs/*.jar
jar cf "$build/protocol.jar" -C "$build/dex" .
cleanup() { adb -s "$serial" shell rm -rf "$remote" >/dev/null; }
trap cleanup EXIT
adb -s "$serial" shell mkdir -p "$remote/tests/fixtures/passport"
adb -s "$serial" push "$build/protocol.jar" "$remote/protocol.jar" >/dev/null
adb -s "$serial" push "$project_dir/tests/fixtures/passport/upstream-vectors.json" "$remote/tests/fixtures/passport/upstream-vectors.json" >/dev/null
adb -s "$serial" push "$project_dir/tests/fixtures/passport/GraphemeBreakTest.txt" "$remote/tests/fixtures/passport/GraphemeBreakTest.txt" >/dev/null
adb -s "$serial" shell "CLASSPATH=$remote/protocol.jar app_process -Xms2m -Xmx32m /system/bin PassportContracts $remote" | tee "$build/result.log"
