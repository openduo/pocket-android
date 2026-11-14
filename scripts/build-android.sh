#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
tools="$ANDROID_HOME/build-tools/35.0.0"
sign_dir="${POCKET_SIGN_DIR:-$HOME/.local/share/pocket-android/signing}"
umask 077
mkdir -p "$project_dir/dist" "$sign_dir"
if [[ ! -f "$sign_dir/pocket.p12" ]]; then
    [[ -f "$sign_dir/password" ]] || openssl rand -hex 24 > "$sign_dir/password"
    keytool -genkeypair -storetype PKCS12 -keystore "$sign_dir/pocket.p12" -storepass:file "$sign_dir/password" -alias pocket -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=OpenDuo Pocket Android' >/dev/null
fi
"$project_dir/gradlew" -p "$project_dir" :app:assembleRelease :app:exportProbeClasspath "$@"
"$tools/zipalign" -f -P 16 4 "$project_dir/android/build/outputs/apk/release/app-release-unsigned.apk" "$project_dir/dist/Pocket-aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/pocket.p12" --ks-key-alias pocket --ks-pass "file:$sign_dir/password" --out "$project_dir/dist/Pocket.apk" "$project_dir/dist/Pocket-aligned.apk"
"$tools/apksigner" verify "$project_dir/dist/Pocket.apk"
rm "$project_dir/dist/Pocket-aligned.apk"
chmod 644 "$project_dir/dist/Pocket.apk"
echo "$project_dir/dist/Pocket.apk"
