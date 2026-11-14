# Pocket E2

An independent native Android migration of [openduo/pocket-ios](https://github.com/openduo/pocket-ios),
using E2Studio's visual and interaction conventions. The current milestone closes the agreed P0/P1
implementation and improves maintainability and general Android window adaptation. Complete 1:1
parity is a documented future comparison, not this milestone's release requirement.

Start with the Chinese [current status and handoff](docs/status.md). It separates implemented paths,
verified conditions and the remaining real-use acceptance. [The migration matrix](docs/migration.md)
retains the upstream comparison and historical evidence. The reference clone is `../pocket-ios`,
initially pinned to `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`.

The user deferred **immediate Android push and external Passport Bluetooth** on 2026-11-14. Push
registration/relay/receiver, upstream push crypto/identifiers/ledger and fixed vectors remain future
work. Existing local notifications and periodic catch-up remain. Passport code is retained, while
standalone policy omits normal shortcuts, automatic accessory resume and accessory background work.

## Current implementation

- Embedded Tailscale/tsnet, native browser-free login QR/link/status, pairing, API-v2 HTTP and `/live`.
- Transactional SQLite cache/outbox/cursor, stable retry identity, durable import/voice inboxes,
  cleanup receipts, history reconciliation and generation-scoped cancellation/access rejection.
- Native Opus and E2 array capture, voice-note upload/retry, ambient ASR/audio/tool flow and streamed
  read-aloud with exclusive capture, focus handling and bounded foreground-service lifetimes.
- Scoped sessions/rows/notify UI, Markdown/tools/reply/copy/share, attachments and Android
  picker/camera/share handoff, native map/geocoder/coordinate insertion and arrival ledger.
- Camera2 H.264 media-v1 uplink with ask/gate/refusal/recovery, viewfinder and backpressure policy.
- Chinese/English, semantic light/dark/system themes, diagnostics, local notifications and Try mode.
- Shared window policy and native page-state restoration for compact/medium/expanded widths,
  landscape, IME, split/freeform windows and font scaling; keyboard and accessibility actions.

Implementation does not imply all actual services and failure cases have been accepted. Known
synthetic speech has traversed real ASR → brain/tool → reply → muted hardware playback; natural
microphone recognition and audible replies remain unaccepted. Actual Sight negotiation, session
notify delivery, physical visits and actual identity/cross-network/external-audio/long-term lifecycle
scenarios also remain open. Prepared Try replies never count as real server responses.

## Verification checkpoint

| Verification | Accepted scope |
| --- | --- |
| Host / source checks | 325 contracts, architecture boundaries, 616-entry localization catalog, Go vet/test and Android build |
| Emulator UI | 22 API 35 window/font/language/theme cases, 9,616 checks; actual display rotation 20, freeform 8 and system split 12 checks |
| Actual accessibility services | TalkBack 15.0 expanded runner: 12 scenes, 404 checks; legacy Google source Switch Access 6.1.1 scan/order/scroll/activation 113 checks |
| Actual system camera permission | TalkBack 46 / Switch Access 33 checks: real denial, repeated request/grant and scan-sheet return; other permissions remain separate |
| Physical E2 | Previous APK checkpoint: complete capture/camera/paired-Try regression 342 checks; current APK after feed accessibility fix: native accessibility/capture target 27 checks |
| Actual framework Geocoder | API 35 public-landmark forward/reverse query; not E2 GPS or physical visit acceptance |
| Physical E2 lifetime | 900 seconds idle background, process present at 60 samples, trim request and actual foreground Online; not natural LMK/overnight/ambient acceptance |

Reports retain separate APK digests and exact conditions in ignored `.build/acceptance/`. See
[acceptance](docs/acceptance.md), [UI architecture](docs/ui.md) and
[accessibility evidence and limits](docs/accessibility.md). Current Play Switch Access, all OEMs and
all system permission-dialog/lifecycle combinations are not covered by these runs. Remote CI has
not run. There is no feature-parity percentage.
The E2's final read-only location probe reports no framework Geocoder and no enabled providers;
search/arrival needs an available backend/source. Coordinate insertion/map selection remains usable.

## Build and install

Requirements: JDK 17, Go 1.27.4, the pinned x/mobile version in `bridge/tsbridge/go.mod`, Android
Platform 35, Build Tools 35.0.0 and NDK 28.2.13676358. Only arm64-v8a is currently built. Scripts accept
`ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT` and `POCKET_E2_SIGN_DIR`. A private signing identity is generated
once outside the repository; keep it for in-place upgrades.

```sh
scripts/build-android.sh
scripts/test-host.sh
python3 scripts/check-architecture.py
python3 scripts/check-localization.py
(cd bridge/tsbridge && go vet ./... && go test ./...)
adb -s YOUR_DEVICE_SERIAL install -r dist/PocketE2.apk
```

Application: `top.kuaner.pockete2`; artifact: ignored `dist/PocketE2.apk`. Login/pairing instructions
and actual-service boundaries are in [connection.md](docs/connection.md).

E2 firmware array libraries/models must be supplied locally under `.build/firmware/{lib,assets}` or
through `POCKET_E2_FIRMWARE_DIR`, matching firmware 04319. This workspace's copies came from the
existing E2Studio build. They are not source dependencies or public redistribution assets. Without
them, other devices can use standard Android input and E2 array capture reports the missing parts.

## Test commands

Select the device explicitly. Physical capture requires E2Studio recording/work and other active
Pocket E2 work to be idle. Paired Try probes retain the actual room/draft and use a disposable
transport; they refuse active capture/camera or a nonempty outbox. The test APK is built separately
with the same local signing identity and does not enter the application build.

```sh
# Full physical capture/camera/Try regression on an already paired, idle E2.
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_PAIRED_TRY=1 scripts/test-device.sh
# Native accessibility, capture, keyboard and cancellation.
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
# Optional separate-UID picker/camera test; currently API 27 only.
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_IMPORT_UI=1 POCKET_E2_TEST_PAIRED_TRY=1 scripts/test-device.sh
# Isolated emulator window/font/IME and real rotation/freeform/split acceptance.
python3 scripts/test-ui-matrix.py --serial YOUR_API35_EMULATOR
python3 scripts/test-window-ui.py --serial YOUR_API35_EMULATOR
# Actual services on a rooted arm64 API 35+ Google APIs emulator.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback
python3 scripts/prepare-switch-access.py --serial YOUR_ROOTED_API35_EMULATOR
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service switch
```

The emulator runners restore changed display/font/rotation/accessibility settings. Use `--installed`
only when matching app/probe APKs are already installed. Each emulator has one QA lock. Switch
Access's pinned legacy source QA build is separate from the product and is not current Play-version
acceptance. Details and additional opt-in probes live in the feature documents below.

Real-service probes may create marked messages/uploads, while identity recovery requires a working
login and fresh pairing path. Read each probe's documented scope before opting in. Once-only
journals retain original identifiers across repeats; do not remove them to repeat a send.
[Audio](docs/audio.md), [imports](docs/imports.md), [storage](docs/storage.md) and
[connection](docs/connection.md) describe the relevant opt-in commands and limits.

The E2 panel observed in this workspace is 480×800 px at existing override density 210. Existing
system overscan leaves a 340 px application viewport, approximately 259dp wide. The app uses its
current window and insets and never changes density, overscan, launcher, screen timeout or
E2Studio configuration. It does not take over E2Studio's Passport BLE connection.

## Structure and maintenance

| Area | Responsibility |
| --- | --- |
| `core/` | Immutable values, pure reducers, single-writer controller, SQLite, durable draft/outbox/inboxes |
| `net/`, `bridge/tsbridge/` | Scoped requests, embedded Tailscale and actual HTTP/live/speech transport |
| `audio/` | Exclusive input, firmware adapter, Opus, incremental packet parsing and hardware receipts |
| `sight/`, `location/` | Video capture/framing, map and location/arrival adapters |
| `ui/` | Window policy, insets, containers, page-state restoration, shared controls and snapshot-driven pages |
| `text/` | Scoped application copy/language catalogs and Markdown processing |
| `trymode/` | Disposable API-v2 transport, in-memory room/storage and prepared scenarios |
| `passport/` | Deferred BLE protocol/GATT/press/reply implementation |
| `tests/`, `scripts/` | Host contracts, isolated device probes and reproducible acceptance runners |

Use `AdaptiveMetrics` for size/font tokens, `AdaptivePage`/`AdaptiveColumn`/`InsetsFrame` for window
layout, `PageStates` for native view state and `Ui`/`Sheets`/`ViewportNavigation` for shared controls.
Business state and durable composer text belong to `PocketController`; it must not read window
geometry. See [ui.md](docs/ui.md) for breakpoints and the architecture checks that enforce this.

Continue from [status.md](docs/status.md), then [acceptance.md](docs/acceptance.md) and
[migration.md](docs/migration.md). Feature ownership and evidence remain in
[connection](docs/connection.md), [audio](docs/audio.md), [storage](docs/storage.md),
[history](docs/history.md), [imports](docs/imports.md), [location](docs/location.md),
[protocol](docs/protocol.md), [design](docs/design.md) and [localization](docs/localization.md).

The prepared Chinese/English Try audio/notes are pinned by `third_party/try-assets.sha256`.
`python3 scripts/prepare-try-assets.py` regenerates them from the clean upstream clone with
ffmpeg/libopus; ordinary builds verify the committed assets and do not require ffmpeg.

No remote repository has been created or published. Neutral Pocket E2 branding does not reuse the
upstream icon/mark. Code follows FSL-1.1-Apache-2.0; third-party components retain their licences.
