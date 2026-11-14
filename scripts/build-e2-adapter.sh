#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
# Local opt-in only. Do not redistribute the proprietary payload in a public release.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
firmware="${1:?Pass the local firmware directory explicitly}"
output="${2:?Pass an ignored output directory}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
ndk="${ANDROID_NDK_ROOT:-$sdk/ndk/28.2.13676358}"
[[ -f "$firmware/assets/e2-array-models.zip" && -f "$firmware/lib/arm64-v8a/libtr_recorder.so" ]] || { echo 'E2 firmware 04319 array libraries/models are required.' >&2; exit 1; }
mkdir -p "$output/assets" "$output/jni/arm64-v8a"
cp "$firmware/assets/e2-array-models.zip" "$output/assets/"
cp "$firmware"/lib/arm64-v8a/*.so "$output/jni/arm64-v8a/"
compilers=("$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang)
python3 "$project_dir/scripts/prepare-e2-meter.py" "$firmware/lib/arm64-v8a/libtinyalsa.so" "$output/jni/arm64-v8a/libe2alsa.so"
"${compilers[0]}" -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -Wl,-soname,libtinyalsa.so -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--no-undefined "$project_dir/device-e2/src/main/cpp/e2_capture_meter.c" -ldl -lm -llog -o "$output/jni/arm64-v8a/libtinyalsa.so"
"${compilers[0]}" -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--no-undefined "$project_dir/device-e2/src/main/cpp/e2_array.c" -ldl -o "$output/jni/arm64-v8a/libe2_array.so"
