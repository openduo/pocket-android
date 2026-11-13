#!/usr/bin/env bash
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
python3 "$project_dir/scripts/check-architecture.py"
python3 "$project_dir/scripts/check-localization.py"
(cd "$project_dir" && shasum -a 256 -c third_party/dependencies.sha256)
(cd "$project_dir" && shasum -a 256 -c third_party/try-assets.sha256)
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
tools="$sdk/build-tools/${POCKET_E2_BUILD_TOOLS:-35.0.0}"
android_jar="$sdk/platforms/${POCKET_E2_ANDROID_PLATFORM:-android-35}/android.jar"
[[ -x "$tools/aapt2" && -f "$android_jar" ]] || { echo '需要 Android SDK Platform 35 和 Build Tools 35.0.0。' >&2; exit 1; }
build="$project_dir/.build/android"
out="$project_dir/dist/PocketE2.apk"
sign_dir="${POCKET_E2_SIGN_DIR:-$HOME/.local/share/pocket-e2/signing}"
umask 077
mkdir -p "$build" "$project_dir/dist" "$sign_dir"
if [[ ! -f "$sign_dir/pocket-e2.p12" ]]; then
  [[ -f "$sign_dir/password" ]] || openssl rand -hex 24 > "$sign_dir/password"
  keytool -genkeypair -storetype PKCS12 -keystore "$sign_dir/pocket-e2.p12" -storepass:file "$sign_dir/password" -alias pocket-e2 -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Pocket E2' >/dev/null
fi
rm -rf "$build/classes" "$build/dex" "$build/generated" "$build/resources.zip" "$build/assets" "$build/base.apk"
mkdir -p "$build/classes" "$build/dex" "$build/generated" "$build/assets/licenses" "$build/lib/arm64-v8a"
"$project_dir/scripts/build-bridge.sh"
"$project_dir/scripts/build-opus.sh"
(cd "$build/classes" && jar xf "$project_dir/.build/tsbridge/classes.jar")
for dependency in "$project_dir"/android/libs/*.jar; do
  (cd "$build/classes" && jar xf "$dependency")
done
if [[ -d "$project_dir/android/src/main/assets" ]]; then cp -R "$project_dir/android/src/main/assets/." "$build/assets/"; fi
cp "$project_dir/android/libs/commonmark-LICENSE.txt" "$build/assets/licenses/"
cp "$project_dir/android/libs/zxing-LICENSE.txt" "$build/assets/licenses/"
cp "$project_dir/.build/tsbridge/jni/arm64-v8a/libgojni.so" "$build/lib/arm64-v8a/"
cp "$project_dir/LICENSE" "$build/assets/licenses/Pocket-LICENSE.txt"
cp "$project_dir/third_party/POCKET-IOS-NOTICE.txt" "$build/assets/licenses/Upstream-NOTICE.txt"
cp "$project_dir/third_party/opus-LICENSE.txt" "$build/assets/licenses/Opus-LICENSE.txt"
if [[ -f "$project_dir/THIRD_PARTY_NOTICES.md" ]]; then cp "$project_dir/THIRD_PARTY_NOTICES.md" "$build/assets/licenses/THIRD_PARTY_NOTICES.md"; fi
firmware="${POCKET_E2_FIRMWARE_DIR:-$project_dir/.build/firmware}"
if [[ -f "$firmware/assets/e2-array-models.zip" ]]; then
  cp "$firmware/assets/e2-array-models.zip" "$build/assets/"
  cp "$firmware"/lib/arm64-v8a/*.so "$build/lib/arm64-v8a/"
  ndk="${ANDROID_NDK_ROOT:-$sdk/ndk/${POCKET_E2_NDK_VERSION:-28.2.13676358}}"
  compilers=("$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang)
  python3 "$project_dir/scripts/prepare-e2-meter.py" "$firmware/lib/arm64-v8a/libtinyalsa.so" "$build/lib/arm64-v8a/libe2alsa.so"
  "${compilers[0]}" -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -Wl,-soname,libtinyalsa.so -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--no-undefined "$project_dir/android/src/main/cpp/e2_capture_meter.c" -ldl -lm -llog -o "$build/lib/arm64-v8a/libtinyalsa.so"
  "${compilers[0]}" -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--no-undefined "$project_dir/android/src/main/cpp/e2_array.c" -ldl -o "$build/lib/arm64-v8a/libe2_array.so"
fi
src="$project_dir/android/src/main"
"$tools/aapt2" compile --dir "$src/res" -o "$build/resources.zip"
"$tools/aapt2" link -I "$android_jar" --manifest "$src/AndroidManifest.xml" --java "$build/generated" -A "$build/assets" -o "$build/base.apk" "$build/resources.zip"
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find "$src/java" "$build/generated" -name '*.java' -print0)
javac -encoding UTF-8 --release 8 -classpath "$android_jar:$build/classes" -d "$build/classes" "${sources[@]}"
jar cf "$build/classes.jar" -C "$build/classes" .
"$tools/d8" --min-api 26 --lib "$android_jar" --output "$build/dex" "$build/classes.jar"
(cd "$build/dex" && zip -q "$build/base.apk" ./*.dex)
(cd "$build" && zip -q -r "$build/base.apk" lib)
"$tools/zipalign" -f -p 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks "$sign_dir/pocket-e2.p12" --ks-key-alias pocket-e2 --ks-pass "file:$sign_dir/password" --out "$build/signed.apk" "$build/aligned.apk"
"$tools/apksigner" verify "$build/signed.apk"
install -m 0644 "$build/signed.apk" "$out"
echo "$out"
