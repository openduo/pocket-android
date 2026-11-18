# DuoDuo Pocket for Android

English · [简体中文](README.zh-CN.md)

The native Android client for [OpenDuo Pocket](https://github.com/openduo/pocket-ios): text, voice, files and an ambient room on your own tailnet. The app shares the iOS name, pompom-and-voice-bars icon, `dev.openduo.pocket` application ID and `duoduopocket://pair` links. The reference is iOS 0.4.0 at `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`.

Built for phones, tablets and other Android devices with a display. Window layout follows shared UI policy, while hardware-specific behavior stays behind device adapters.

<p align="center">
  <img src="docs/screens/app.png" alt="DuoDuo Pocket on Android: English conversation, Markdown schedule, file reply and ambient controls using prepared demo content" width="100%">
</p>
<p align="center"><sub>English UI with built-in Try content on an Android emulator. Replies are prepared examples; capture and playback are off.</sub></p>
<p align="center">
  <img src="docs/screens/devices.png" alt="Pocket Android across a physical device, phone and tablet emulators, and a landscape display layout" width="100%">
</p>
<p align="center"><sub>The gallery combines one physical-device capture with phone, tablet and landscape emulator layouts.</sub></p>

[Download 0.4.1](https://github.com/openduo/pocket-android/releases/tag/v0.4.1) · [Implemented and remaining](docs/status.md) · [Device adapters](docs/devices.md) · [Architecture](docs/architecture.md) · [iOS migration gaps](docs/migration.md)

## What is implemented

- Embedded Tailscale login, authorization by QR/link, pairing, API v2 and `/live`. Recovery resamples netmon to fix a restored Wi-Fi connection leaving the node offline.
- Conversations, Markdown/tables/tools, quotes/copy/share, history, photos/files and Android import/share handoff; durable SQLite drafts, idempotent outbox and recovery receipts.
- Hold to talk, Opus voice upload/retry, ambient capture, streamed replies/read-aloud, audio focus and one shared microphone lease.
- Passport protocol 1.3 and Android BLE integration: press/audio assembly, RESULT/WORK/REPLY, quiet delivery and replay memory; [upstream differential checks and hardware limits](docs/passport.md).
- Session lists/details/notification controls, Camera2/H.264 Sight, maps/coordinates/geocoding and arrival tracking.
- English and Simplified Chinese, system/light/dark appearance, diagnostics, local notifications and a clearly labeled prepared-content Try mode.
- Shared touch/key/remote actions, editable foreground bindings and native D-pad/OK/Back navigation; [input guide](docs/input.md).
- Uses the latest upstream [pocket-rs](https://github.com/openduo/pocket-rs) protocol/JNI implementation, including media-v1 audio handling and reply playback through screen lock.
- Chat has Settings, the room name and Ambient in the top bar. It uses a centered composer, a shared recording panel for touch and keys, full-width bubbles below 320dp and a bounded reading column on wider windows. [Android window and keyboard behavior](docs/ui.md).

Implementation and real-service acceptance are tracked separately. See [status](docs/status.md) and the [migration record](docs/migration.md) for current verification limits and remaining work.

## Install and connect

0.4.1 uses version code 6 and the existing public signing key, so it updates 0.4.0 and earlier public previews in place without clearing data or pairing. Android 8.0 / API 26 or later; the generic APK includes **arm64-v8a and x86_64**. Google Play Services is not required. Bring your own ambient host and a Tailscale network that can reach it; there is no bundled room or account.

1. Install `Pocket.apk` from [Releases](https://github.com/openduo/pocket-android/releases).
2. Choose **Log in to Tailscale** and authorize using the link or a QR code on another device.
3. Obtain a **fresh pairing link** from your ambient host, paste or scan it, then join the room.

See [connection details](docs/connection.md) if you need help pairing a room.

## Build

Use Rust 1.100.0, JDK 17, Android SDK 35 / Build Tools 35.0.0 / NDK 28.2.13676358 / CMake 3.22.1, Go 1.27.4 and the x/mobile version pinned in `go.mod`. Gradle wrapper 8.13 and AGP 8.13.2 are pinned. Open the repository directly in Android Studio.

```sh
git submodule update --init --recursive
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
gomobile init
./gradlew :app:assembleDebug
```

The standard build uses Android framework audio. A Gradle task builds the Go bridge for both ABIs; CMake builds Opus from the checksum-pinned source archive. `./gradlew :core:check` runs JVM contracts against the actual host Rust/JNI library without a device.
See [shared protocol integration](docs/rust.md).

For a locally signed release:

```sh
scripts/build-android.sh
# Output: dist/Pocket.apk
# Signing files stay outside the repository. Retain them for future updates.
# POCKET_SIGN_DIR can select a different signing directory.
```

## Modules and maintenance

| Module | Responsibility |
| --- | --- |
| `:core` | Android-independent models/application reducers, Rust protocol facades, request boundaries and Try transport |
| `:protocol` | pocket-rs Java/JNI interface; Rust owns room wire/framing/Ambient/retry rules |
| `:input` | Pure Java actions, input targets and paired key gestures; no Android or business dependency |
| `:ui-policy` | Pure Java window/typography policy; no business state or model-specific layout |
| `:device:base` | Standard Android audio/routing, capability discovery, one microphone lease and the device interface |
| `:device:*` | Optional hardware-specific adapters |
| `:app` (`android/`) | Android composition, persistence, controller, system services, native View pages and window containers |
| `bridge/tsbridge` | Embedded Tailscale, HTTP/WebSocket and speech transport |

Start with [architecture](docs/architecture.md), [window policy](docs/ui.md) and [localization](docs/localization.md). All application copy, native controls, accessibility descriptions, notifications and device errors share the language boundary. Source comments/tooling and public documentation use English; principal guides also have Chinese companions. Run checks relevant to a change; documentation capture does not run a regression matrix. Earlier expanded checks are retained as [historical evidence](docs/acceptance.md).

```sh
python3 scripts/verify-sources.py
scripts/test-host.sh
# Alternative: ./gradlew :core:check
(cd bridge/tsbridge && go vet ./... && go test ./...)
```

Code is FSL-1.1-Apache-2.0. Dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). OpenDuo names, icons and brand assets are excluded from the code license; forks must replace the brand. See the [brand notice](third_party/POCKET-IOS-NOTICE.txt). Screenshot provenance and reproduction are documented in [docs/screens](docs/screens/README.md).
