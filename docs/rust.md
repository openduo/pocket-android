# Shared Rust protocol

English · [简体中文](rust.zh-CN.md)

The source pins [openduo/pocket-rs](https://github.com/openduo/pocket-rs) in
`third_party/pocket-rs`. Initialize with `git submodule update --init --recursive` after checkout.
Install Rust 1.100.0 and `rustup target add aarch64-linux-android x86_64-linux-android` alongside the
existing JDK17/NDK `28.2.13676358` build requirements. Gradle builds host JNI for `:core:check` and
dual-ABI JNI for APKs. A local SDK checkout can be selected with `-PpocketRustDir=/path/to/pocket-rs`;
the default uses the committed submodule revision. CI always builds that pinned revision.

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
