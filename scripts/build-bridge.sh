#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0

set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
export PATH="$project_dir/.build/tools/go/bin:$project_dir/.build/tools/bin:$HOME/go/bin:$PATH"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
export ANDROID_HOME="$sdk"
export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT:-$sdk/ndk/${POCKET_E2_NDK_VERSION:-28.2.13676358}}"
command -v go >/dev/null || { echo '需要 Go 1.27.1 或以上版本。' >&2; exit 1; }
command -v gomobile >/dev/null || { echo '需要 gomobile（版本与 bridge/tsbridge/go.mod 中的 x/mobile 一致）。' >&2; exit 1; }
mkdir -p "$project_dir/.build/tsbridge"
stamp=$(find "$project_dir/bridge/tsbridge" -type f \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \) -exec shasum -a 256 {} \; | shasum -a 256 | cut -d' ' -f1)
if [[ ! -f "$project_dir/.build/tsbridge.aar" || "$(cat "$project_dir/.build/bridge-stamp" 2>/dev/null || true)" != "$stamp" ]]; then
  (cd "$project_dir/bridge/tsbridge" && gomobile bind -trimpath -ldflags '-s -w' -target android/arm64 -androidapi 26 -o "$project_dir/.build/tsbridge.aar" .)
  echo "$stamp" > "$project_dir/.build/bridge-stamp"
fi
unzip -o -q "$project_dir/.build/tsbridge.aar" -d "$project_dir/.build/tsbridge"
