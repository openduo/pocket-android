#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
if ! command -v adb >/dev/null; then
    echo 'Install Android platform-tools and connect the E2 device first.' >&2
    exit 1
fi

device_adb() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        adb -s "$ANDROID_SERIAL" "$@"
    else
        adb "$@"
    fi
}

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    ready_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
    [[ "$ready_count" == 1 ]] || {
        echo 'Connect exactly one authorized Android device or set ANDROID_SERIAL.' >&2
        exit 1
    }
fi

model="$(device_adb shell getprop ro.product.model | tr -d '\r')"
fingerprint="$(device_adb shell getprop ro.build.fingerprint | tr -d '\r')"
[[ "$model" == 'E2 AI Recorder' && "$fingerprint" == */04319:user/release-keys ]] || {
    echo 'The connected device is not E2 firmware 04319.' >&2
    exit 1
}

package_paths="$(device_adb shell pm path top.kuaner.recorder | sed 's/^package://; s/\r$//')"
[[ -n "$package_paths" ]] || { echo 'E2 system recorder APK was not found.' >&2; exit 1; }

firmware_dir="$project_dir/.build/e2-firmware-from-device"
apk_dir="$project_dir/.build/e2-recorder-apks"
validation_dir="$project_dir/.build/e2-firmware-validation"
rm -rf "$firmware_dir" "$apk_dir" "$validation_dir"
mkdir -p "$firmware_dir" "$apk_dir" "$validation_dir"

index=0
while IFS= read -r remote_apk; do
    [[ -n "$remote_apk" ]] || continue
    local_apk="$apk_dir/recorder-$index.apk"
    device_adb pull "$remote_apk" "$local_apk" >/dev/null 2>&1
    if unzip -Z1 "$local_apk" | rg -q '^assets/e2-array-models\.zip$'; then
        unzip -oq "$local_apk" 'assets/e2-array-models.zip' 'lib/arm64-v8a/*' -d "$firmware_dir"
    fi
    index=$((index + 1))
done <<< "$package_paths"

[[ -s "$firmware_dir/assets/e2-array-models.zip" \
    && -s "$firmware_dir/lib/arm64-v8a/libtr_recorder.so" \
    && -s "$firmware_dir/lib/arm64-v8a/libtinyalsa.so" ]] || {
    echo 'The recorder APK did not provide the required E2 model and libraries.' >&2
    exit 1
}

python3 "$project_dir/scripts/prepare-e2-meter.py" \
    "$firmware_dir/lib/arm64-v8a/libtinyalsa.so" "$validation_dir/libe2alsa.so"
python3 - "$firmware_dir/assets/e2-array-models.zip" <<'PY'
from pathlib import PurePosixPath
from zipfile import ZipFile
import sys

with ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    required = ('config/model1/', 'config/model2/', 'config/nnse/')
    if any(PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts
           for name in names):
        raise SystemExit('E2 model archive contains an unsafe path.')
    missing = [path for path in required if not any(name.startswith(path) for name in names)]
    if missing:
        raise SystemExit('E2 model archive is incomplete: ' + ', '.join(missing))
PY

echo "E2 build inputs recovered into ignored directory: $firmware_dir"
