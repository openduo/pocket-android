# Pocket E2

An independent native Android migration of [openduo/pocket-ios](https://github.com/openduo/pocket-ios),
using E2Studio's visual and interaction conventions. **The target is 1:1 functional parity. The
migration is in progress; this repository is not yet a parity-complete release.**

The upstream clone is kept separately at `../pocket-ios`; the initial reference commit is
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. `docs/migration.md` records both implemented paths and
remaining gaps. Prepared try-mode replies never count as a real server response.

The application includes the upstream tsnet bridge bound for Android, API-v2 HTTP and `/live`,
SQLite cache/outbox, native Opus, E2 array capture, ambient audio, a PacketStream read-aloud path,
Passport central/press/reply logic, a scoped session viewer, attachment import/sharing, native map
selection and Camera2 H.264 media-v1 uplink. Each path has its own verification status in the matrix.

The latest local checkpoint passed **148 host contract assertions and 122 E2 device assertions**,
plus Go vet/tests and the Android build. Real-room login, API/audio integration and an external
Passport have not been verified. These checks are regression evidence, not a parity-completion claim.

The checkpoint includes native Chinese/English switching, literal user-content preservation,
language-aware notification/Passport frames, occupied-camera waiting/recovery, and a session viewer
with source/activity, grouped lists and recipient-specific notification drafts. English conversation,
settings and session screens were inspected at ~259 dp, including 1.35 font scale, light theme and
the real E2 keyboard. Shares now use an atomic file inbox and transactional delivery receipts,
preserve names/MIME and order, retain concurrent typing, and recover without duplication through
recreation. Real system document-picker and camera returns exercise the same import path.
`docs/migration.md` keeps external-service/hardware gaps separate; `docs/imports.md` describes the
handoff and its remaining failure/lifecycle acceptance work.

## Build

```sh
scripts/build-android.sh       # dist/PocketE2.apk, package top.kuaner.pockete2
scripts/test-host.sh           # pure protocol/reducer contracts
(cd bridge/tsbridge && go vet ./... && go test ./...)
# Install the built app before device tests:
adb install -r dist/PocketE2.apk
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_IMPORT_UI=1 scripts/test-device.sh
```

Requirements: JDK 17, Go 1.27.4, the x/mobile version in `bridge/tsbridge/go.mod` (gomobile and gobind),
Android Platform 35, Build Tools 35.0.0 and NDK 28.2.13676358. The scripts accept `ANDROID_SDK_ROOT`,
`ANDROID_NDK_ROOT` and `POCKET_E2_SIGN_DIR`. A private signing identity is generated once outside the
repository. Only arm64-v8a is currently built.

The optional native import UI probe currently targets API 27, including a disposable document source
in a separate UID. It declines the stock camera's optional location request and restores its prior
user-decision permission flags with an Android-8-only shell helper. The helper, provider and test APK
are removed after the run. The application APK does not include them.

The E2 firmware array libraries/models must be supplied locally under `.build/firmware/{lib,assets}`
or through `POCKET_E2_FIRMWARE_DIR`, matching firmware 04319. This workspace's copies came from the
existing E2Studio build. They are not source dependencies or public redistribution assets. A build
without them can use standard Android input on other devices; E2 array capture reports the missing
components explicitly.

The observed E2 panel is 480×800 px at override density 210. **Current system overscan leaves a
340 px application viewport (~259 dp wide).** The app measures insets and IME on that viewport;
it never changes the user's density, overscan, launcher or E2Studio.

## Structure

- `core/`: immutable values, pure turn/session reducers, single-writer orchestration and SQLite.
- `net/`, `bridge/tsbridge/`: scoped requests, embedded Tailscale and the actual `/live` socket.
- `audio/`: exclusive input lease, firmware adapter, Opus, streamed packet parsing, hardware receipts.
- `passport/`: protocol 1.3, GATT operation queue, press assembly and correlated replies.
- `sight/`, `location/`: H.264 capture/framing and the location/map picker.
- `trymode/`: disposable API-v2 channel behind the same HTTP/live/speech boundary; in-memory SQLite
  room, isolated temporary files, idempotent receipts and prepared scenarios.
- `text/`: immutable application-copy catalogs, scoped native language configuration and Markdown
  processing. UI content and localization boundaries are documented in `docs/localization.md`.
- `ui/`: retained native pages, compact sheets, semantic themes, Markdown and scroll anchoring.
- `tests/`: host contracts and a separately signed instrumentation APK with a disposable database.

The bundled prepared Chinese/English audio and notes are pinned by `third_party/try-assets.sha256`.
Regenerate them from the clean upstream clone with `python3 scripts/prepare-try-assets.py` and
ffmpeg/libopus. Builds use the committed assets and verify their checksums; they do not require ffmpeg.

No remote repository has been created or published. The upstream icon/mark are not reused; this
project uses neutral Pocket E2 branding. Code follows the upstream FSL-1.1-Apache-2.0 terms;
third-party components retain their licences.
