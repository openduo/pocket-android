#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
build="$project_dir/.build/host"
json="$project_dir/.build/test-deps/json-20250517.jar"
mkdir -p "$build/classes" "$(dirname "$json")"
if [[ ! -f "$json" ]]; then curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar -o "$json"; fi
[[ "$(shasum -a 256 "$json" | cut -d' ' -f1)" == 3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796 ]] || { echo 'JSON dependency checksum mismatch' >&2; exit 1; }
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(rg --files -0 "$project_dir/core/src/main/java" "$project_dir/ui-policy/src/main/java" "$project_dir/tests/host" -g '*.java')
libraries="$project_dir/android/libs/*"
javac -encoding UTF-8 --release 8 -classpath "$json:$libraries" -d "$build/classes" "${sources[@]}"
java -ea -Dpocket.android.root="$project_dir" -classpath "$json:$build/classes:$libraries" Contracts
