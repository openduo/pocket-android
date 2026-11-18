# Shared Rust protocol

English · [简体中文](rust.zh-CN.md)

The shared protocol source is upstream [openduo/pocket-rs](https://github.com/openduo/pocket-rs),
included as a submodule in `third_party/pocket-rs` and tracked on its `main` branch. Initialize it
with `git submodule update --init --recursive` after checkout; update to the latest upstream source
with `git submodule update --remote third_party/pocket-rs`. Validate protocol changes before
committing the updated submodule pointer. Each Android commit records the integrated source revision
so that checkout and release builds remain reproducible; the dependency policy follows upstream
`main`, rather than a fixed pocket-rs release.
Install Rust 1.100.0 and `rustup target add aarch64-linux-android x86_64-linux-android` alongside the
existing JDK17/NDK `28.2.13676358` build requirements. Gradle builds host JNI for `:core:check` and
dual-ABI JNI for APKs. A local SDK checkout can be selected with `-PpocketRustDir=/path/to/pocket-rs`;
the default uses the submodule revision recorded in the Android commit.

`:protocol` contains only the library's Android-free Java binding. `RustProtocol` maps app domain
values to ABI v1 and structured errors to existing localized app errors. Java AmbientInput,
AmbientOutput, PacketStream, Endpoint and retry facades no longer implement protocol reducers.
ChannelClient builds requests in Rust; SpeechReader uses shared speech request/framing, and the
controller validates live events and delegates media uplink/downlink to the shared owner.

The migration does not hand platform resources to Rust: Android retains UI, SQLite atomicity,
credentials, Tailscale networking, microphone/Opus/AudioTrack/focus and foreground service. Its
existing OutboxSender/RoomLink schedule/cancel those effects and use shared retry policy. It does
not also dispatch the library's aggregate Session, which is an alternative for new Linux hosts.
Stream workers close native parser handles even on cancellation; controller-lifetime owners retain
their existing app lifetime. JNI copies returned buffers to Java then frees Rust memory once.

The resumed [Sight adaptation](sight.md) corrects one SDK rule exposed by the released media service:
`media=1` adds kind bytes only to uplink. The shared Ambient owner and JNI input preserve each
downlink Opus packet unchanged, including one beginning with `0x01`. The native loopback fixture
now follows the same server rule. Preview 3 used the pocket-rs 0.1.1 commit containing this
correction; the current Android source has since advanced to the latest upstream `main` revision.
Twelve new JVM assertions bring the local suite to 2,634; the workspace Rust suite passes 21 cases.

The public APK includes `libpocket_core.so` and `libpocket_jni.so` for arm64-v8a and x86_64,
with API26 and 16 KiB alignment. Core/JNI have no Go dependency. The existing Go/Tailscale adapter
remains the platform network implementation. Native names/constructors have consumer keep rules
for future minification. Shared-library and dependency licenses are bundled in the APK.

Relevant checks are `./gradlew :core:check :app:assembleDebug` and a matching signed Release build.
For the connected paired E2, the opt-in checks are `POCKET_TEST_RUST_MIGRATION_ONLY=1 scripts/test-device.sh`
and `POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY=1 scripts/test-device.sh`, with `ANDROID_SERIAL` selecting
the intended device. These use prepared Try/local content and silent native playback; no microphone
or actual room sends. Do not run the historical full device suite. See [status](status.md) for actual
evidence and the SDK's [migration plan](https://github.com/openduo/pocket-rs/blob/main/docs/migration.md)
for native Linux and future iOS boundaries.

The remaining session-watch/notification command serialization in SessionViewer and the
Passport/Sight/location protocols keep their existing feature owners. They are not claimed as
part of this Chat/Ambient protocol migration. [CI for the executable integration](https://github.com/openduo/pocket-android/actions/runs/43675746653)
passes as earlier integration evidence. Preview 3 adds the media downlink correction described above;
its release validation is recorded in [status](status.md) and the release notes.
