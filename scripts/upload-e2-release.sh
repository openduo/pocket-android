#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 vVERSION /path/to/Pocket-E2.apk" >&2
    exit 2
fi
tag="$1"
apk="$2"
[[ "$apk" == /* ]] || apk="$PWD/$apk"
[[ "$tag" =~ ^v[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release tag.' >&2; exit 2; }
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
command -v gh >/dev/null || { echo 'Install GitHub CLI (gh) and authenticate first.' >&2; exit 1; }

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
build_tools="${POCKET_E2_BUILD_TOOLS:-35.0.0}"
apksigner="$sdk/build-tools/$build_tools/apksigner"
aapt="$sdk/build-tools/$build_tools/aapt"
[[ -x "$apksigner" && -x "$aapt" ]] || { echo 'Android build-tools 35.0.0 are required.' >&2; exit 1; }

cd "$project_dir"
python3 scripts/check-e2-apk.py "$apk"
"$apksigner" verify "$apk"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
gh release download "$tag" --pattern Pocket.apk --pattern SHA256SUMS \
    --dir "$tmp_dir" --repo openduo/pocket-android
generic_apk="$tmp_dir/Pocket.apk"
[[ -f "$generic_apk" && -f "$tmp_dir/SHA256SUMS" ]] || {
    echo "Release $tag must contain Pocket.apk and SHA256SUMS." >&2
    exit 1
}
"$apksigner" verify "$generic_apk"

generic_manifest_line="$("$aapt" dump badging "$generic_apk" | sed -n '1p')"
e2_manifest_line="$("$aapt" dump badging "$apk" | sed -n '1p')"
[[ "$generic_manifest_line" == "$e2_manifest_line" && "$e2_manifest_line" == "package: name='dev.openduo.pocket'"* ]] || {
    echo 'E2 APK package identity/version does not match the generic release.' >&2
    exit 1
}
generic_signer="$("$apksigner" verify --print-certs "$generic_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
e2_signer="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
[[ -n "$generic_signer" && "$generic_signer" == "$e2_signer" ]] || {
    echo 'E2 APK is not signed with the generic release certificate.' >&2
    exit 1
}

cp "$apk" "$tmp_dir/Pocket-E2.apk"
(cd "$tmp_dir" && {
    grep -v '  Pocket-E2\.apk$' SHA256SUMS > SHA256SUMS.updated || true
    mv SHA256SUMS.updated SHA256SUMS
    shasum -a 256 Pocket-E2.apk >> SHA256SUMS
})
gh release upload "$tag" "$tmp_dir/Pocket-E2.apk" "$tmp_dir/SHA256SUMS" \
    --repo openduo/pocket-android --clobber
gh release view "$tag" --repo openduo/pocket-android --json url -q .url
