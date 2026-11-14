#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
formatter="$project_dir/.build/tools/format/google-java-format.jar"
mkdir -p "$(dirname "$formatter")"
if [[ ! -f "$formatter" ]]; then curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar -o "$formatter"; fi
[[ "$(shasum -a 256 "$formatter" | cut -d' ' -f1)" == 812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1 ]] || { echo 'Formatter checksum mismatch' >&2; exit 1; }
rg --files "$project_dir/android/src/main/java" "$project_dir/core/src" "$project_dir/ui-policy/src" "$project_dir/device/src" "$project_dir/device-e2/src" "$project_dir/tests" -g '*.java' > "$project_dir/.build/java-sources"
java -jar "$formatter" --aosp --replace @"$project_dir/.build/java-sources"
