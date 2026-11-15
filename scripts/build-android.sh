#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
tools="$ANDROID_HOME/build-tools/35.0.0"
sign_dir="${POCKET_SIGN_DIR:-$HOME/.local/share/pocket-android/signing}"
sign_file="pocket.p12"
sign_alias="pocket"
artifact="Pocket"
identity_args=(-PpocketLegacyIdentity=false)
if [[ "${POCKET_LEGACY_IDENTITY:-0}" == 1 ]]; then
    sign_dir="${POCKET_E2_SIGN_DIR:-$HOME/.local/share/pocket-e2/signing}"
    sign_file="pocket-e2.p12"
    sign_alias="pocket-e2"
    artifact="PocketLegacy"
    identity_args=(-PpocketLegacyIdentity=true)
    [[ -f "$sign_dir/$sign_file" && -f "$sign_dir/password" ]] || { echo 'Same-package updates require the original Pocket E2 signing key.' >&2; exit 1; }
fi
umask 077
mkdir -p "$project_dir/dist" "$sign_dir"
if [[ ! -f "$sign_dir/$sign_file" ]]; then
    [[ -f "$sign_dir/password" ]] || openssl rand -hex 24 > "$sign_dir/password"
    keytool -genkeypair -storetype PKCS12 -keystore "$sign_dir/$sign_file" -storepass:file "$sign_dir/password" -alias "$sign_alias" -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=OpenDuo Pocket Android' >/dev/null
fi
"$project_dir/gradlew" -p "$project_dir" :app:assembleRelease :app:exportProbeClasspath "$@" "${identity_args[@]}"
unsigned_apk="$project_dir/android/build/outputs/apk/release/app-release-unsigned.apk"
if unzip -Z1 "$unsigned_apk" | rg -q '^assets/e2-array-models\.zip$'; then
    # Keep local firmware builds distinct from the generic publication artifact.
    artifact="$artifact-E2"
elif [[ "$artifact" == Pocket ]]; then
    python3 "$project_dir/scripts/check-public-apk.py" "$unsigned_apk"
fi
"$tools/zipalign" -f -P 16 4 "$unsigned_apk" "$project_dir/dist/$artifact-aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/$sign_file" --ks-key-alias "$sign_alias" --ks-pass "file:$sign_dir/password" --out "$project_dir/dist/$artifact.apk" "$project_dir/dist/$artifact-aligned.apk"
"$tools/apksigner" verify "$project_dir/dist/$artifact.apk"
rm "$project_dir/dist/$artifact-aligned.apk"
chmod 644 "$project_dir/dist/$artifact.apk"
echo "$project_dir/dist/$artifact.apk"
