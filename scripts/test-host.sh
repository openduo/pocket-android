#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
android_jar="$sdk/platforms/${POCKET_E2_ANDROID_PLATFORM:-android-35}/android.jar"
build="$project_dir/.build/host"
json="$project_dir/.build/test-deps/json-20250517.jar"
mkdir -p "$build/classes" "$(dirname "$json")"
if [[ ! -f "$json" ]]; then curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar -o "$json"; fi
[[ "$(shasum -a 256 "$json" | cut -d' ' -f1)" == 3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796 ]] || { echo 'JSON dependency checksum mismatch' >&2; exit 1; }
sources=()
for name in Endpoint Json Message Attachment Outbox TurnState SessionViewer AmbientInput AmbientOutput VoiceRetry PlaceLedger DraftText ImportBatch ImportInbox ImportRoute Settings SecretStore; do sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/core/$name.java"); done
for name in PocketProtocol PressAssembler ReplyTracker; do sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/passport/$name.java"); done
sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/pairing/QrCode.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/sight/SightProtocol.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/audio/PacketStream.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/net/ChannelTransport.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/net/ChannelClient.java" "$project_dir/tests/host/Contracts.java")
sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/net/ChannelConnection.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/trymode/TryChannel.java" "$project_dir/android/src/main/java/top/kuaner/pockete2/trymode/TryScript.java" "$project_dir/tests/host/TryChannelContracts.java")
sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/sight/PreviewFrame.java")
sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/sight/SightRecovery.java")
sources+=("$project_dir/android/src/main/java/top/kuaner/pockete2/text/TextCatalog.java")
sources+=("$project_dir/tests/host/SessionViewerContracts.java")
sources+=("$project_dir/tests/host/ImportInboxContracts.java")
sources+=("$project_dir/tests/host/UploadContracts.java")
sources+=("$project_dir/tests/host/VoiceUploadContracts.java")
sources+=("$project_dir/tests/host/AmbientOutputContracts.java")
zxing="$project_dir/android/libs/zxing-core-3.5.3.jar"
javac -encoding UTF-8 --release 8 -classpath "$json:$android_jar:$zxing" -d "$build/classes" "${sources[@]}"
java -ea -Dpocket.e2.root="$project_dir" -classpath "$json:$build/classes:$android_jar:$zxing" Contracts
