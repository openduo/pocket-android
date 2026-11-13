#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0

set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
if [[ ! -d "$sdk/ndk" && -d /opt/homebrew/share/android-commandlinetools ]]; then sdk=/opt/homebrew/share/android-commandlinetools; fi
ndk="${ANDROID_NDK_ROOT:-$sdk/ndk/${POCKET_E2_NDK_VERSION:-28.2.13676358}}"
work="$project_dir/.build/pocket-opus"; archive="$project_dir/third_party/opus-1.6.1.tar.gz"
mkdir -p "$work"
python3 - "$archive" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != '6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1':
    raise SystemExit('Pocket E2 Opus dependency checksum mismatch')
PY
if [[ ! -f "$work/src/CMakeLists.txt" ]]; then mkdir -p "$work/src"; tar xzf "$archive" -C "$work/src" --strip-components 1; fi
cmake -S "$work/src" -B "$work/build" -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_DRED=OFF -DOPUS_OSCE=OFF -DOPUS_DEEP_PLC=OFF -DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF > "$work/configure.log" 2>&1
cmake --build "$work/build" -j 8 > "$work/build.log" 2>&1
compilers=("$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang); mkdir -p "$project_dir/.build/android/lib/arm64-v8a"
"${compilers[0]}" -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--no-undefined -I "$work/src/include" "$project_dir/android/src/main/cpp/pocket_e2_opus.c" "$work/build/libopus.a" -lm -o "$project_dir/.build/android/lib/arm64-v8a/libpocket_e2_opus.so"
