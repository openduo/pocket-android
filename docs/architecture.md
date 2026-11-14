# Architecture

English · [简体中文](architecture.zh-CN.md)

Protocol, window policy and hardware integration have separate build boundaries. The app keeps its native Android View stack and shares pages across device types.

```mermaid
flowchart TD
    app[app: composition / controller / storage / Views]
    core[core: pure Java protocol and reducers]
    ui[ui-policy: pure Java window metrics]
    input[input: pure Java actions and key gestures]
    device[device: framework audio and hardware interface]
    e2[device-e2: optional E2 integration]
    bridge[Go Tailscale / HTTP / WebSocket]
    app --> core
    app --> ui
    app --> input
    device --> input
    app --> device
    app --> e2
    app --> bridge
    e2 --> device
```

`:core` has no Android, window or hardware dependency. It owns API-v2 and live framing, message/attachment/voice values, idempotency, retry/cancellation policies, session models and arrival ledgers. Android Settings, KeyStore, SQLite and background services stay in `:app`. JVM checks use a pinned JSON implementation; Android uses its platform JSON API.

`:ui-policy` owns `AdaptiveMetrics`, which accepts current-window dp and font scale. `InsetsFrame`, `AdaptivePage`, `AdaptiveColumn`, `PageStates` and shared controls in app own insets, the IME, scrolling, column bounds and transient View state. Pages observe controller snapshots. Window changes do not change room, outbox or microphone behavior.

`:input` defines user intents, input targets and paired key-gesture reduction without Android or business dependencies. `app/input` supplies Android events, persistent overrides, permission handling and a shared action dispatcher. Touch/accessible controls use the same target; only adapters select default key bindings. Native D-pad/OK/Back navigation and visible focus are shared UI policy. See [keys and remotes](input.md).

`:device` defines `DeviceAdapter`, standard AudioRecord/routing and capability discovery. Every input passes through the same `ExclusiveRecordingInput` lease. `:device-e2` depends only on this common hardware module. `PocketApplication` is the composition root that registers concrete adapters. OEM integrations cannot own room state or choose window geometry. A display-equipped smart speaker can start with the default adapter and add an OEM module only for actual driver differences.

`PocketController` remains the Android coordinator and single state writer. Separate bounded workers handle networking, sends, capture, camera and playback. SQLite transactions own rows/outbox/cursors and cleanup receipts. `VoiceNotes` now owns recording bytes, source ownership, durable admission and recovery in `:core`, with a store interface for SQLite. The controller coordinates capture on the same state executor rather than duplicating the note state. The coordinator still owns connection/outbox/audio orchestration; further separation should follow those business owners. This milestone does not claim every Android business component is a separate module.

Gradle wrapper 8.13, AGP 8.13.2 and CMake provide the standard build. Go tasks declare inputs and outputs; JNI names follow the Java package; Opus comes from a checksum-pinned source archive. Signing material stays outside the repository. Generic builds contain no E2 firmware payload. CI checks source boundaries, JVM contracts, Go and a generic Debug APK. Device probes are explicit opt-in.

`scripts/check-architecture.py` checks module purity, UI/business boundaries, window-pixel ownership and OEM model detection. Gradle dependencies also prevent pure protocol code from importing Android/UI. See [devices](devices.md), [UI](ui.md) and [localization](localization.md) for extension rules.
