# Pocket E2

An independent native Android migration of [openduo/pocket-ios](https://github.com/openduo/pocket-ios),
using E2Studio's visual and interaction conventions. **The target is 1:1 functional parity. The
migration is in progress; this repository is not yet a parity-complete release.**

The user deferred external Passport Bluetooth integration on 2026-11-14. The current scope is
standalone E2 use; Passport shortcuts and prompts are omitted from conversation and settings.
Its existing implementation is retained for later, without reducing the remaining migration goal.

The upstream clone is kept separately at `../pocket-ios`; the initial reference commit is
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. `docs/migration.md` records both implemented paths and
remaining gaps. Prepared try-mode replies never count as a real server response.

The application includes the upstream tsnet bridge bound for Android, API-v2 HTTP and `/live`,
SQLite cache/outbox, native Opus, E2 array capture, ambient audio, a PacketStream read-aloud path,
a scoped session viewer, attachment import/sharing, native map selection and Camera2 H.264 media-v1
uplink. Each path has its own verification status in the matrix.

The latest local checkpoint passed **189 host contract assertions and 227 E2 device assertions**,
plus Go vet/tests and the Android build. A separate two-assertion run verified the actual Tailscale
login QR. Real-room authorization, pairing and text send/reply also passed. A separate opt-in run
passed **17 real-room assertions** covering actual session lists/rows, file upload/download/removal
and streamed synthesis/completion/cancellation with muted hardware playback. A further **15 real-voice
assertions** verified one marked synthetic note through actual ASR/delivery/reply and native background
upload. **11 cold-restart assertions** reused its journal without sending again. Acoustic recognition
from the E2 microphone and ambient server audio remain unverified. These checks are regression evidence,
not a parity-completion claim.
An independent process-death run passed **27 host-controlled and 12 native-phase assertions**, including
two actual force-stop/cold-launch cycles around an unfinished system-picked document copy.

The checkpoint includes native Chinese/English switching, literal user-content preservation,
language-aware notification/Passport frames, occupied-camera waiting/recovery, and a session viewer
with source/activity, grouped lists and recipient-specific notification drafts. English conversation,
settings and session screens were inspected at ~259 dp, including 1.35 font scale, light theme and
the real E2 keyboard. Shares now use an atomic file inbox and transactional delivery receipts,
preserve names/MIME and order, retain concurrent typing, and recover without duplication through
recreation. Real system document-picker and camera returns exercise the same import path.
Picker/camera routes survive recreation; media conversion preserves dimensions, while document
selection preserves bytes. The composer reports observed import bytes and can cancel stalled external
reads/opens without losing its existing draft or admitting part of a batch.
Permanent import requests are saved in SQLite before provider work enters the queue. A force-stop
during a real system-picked file copy now resumes on cold launch using Android's retained document
grant; a second restart keeps one complete chip without rereading its source. Receipts keep source
cleanup separate from draft admission, and expired temporary grants remain an explicit limit.
For older servers that acknowledge an upload path without a SHA-256, the client retains the digest
of the exact uploaded bytes; supplied digests must match. Actual authenticated download then verifies
the original content without test-only metadata.
`docs/migration.md` keeps external-service/hardware gaps separate; `docs/imports.md` describes the
handoff and its remaining failure/lifecycle acceptance work.

Ambient output follows the upstream mute/interrupt/read policy through an independent reducer.
Muted new room blocks acknowledge immediately; native receipts carry playback generations through
the state queue, and local press/hush events are distinct from remote stops. Focus loss retains
ambient intent while releasing capture and reopening without room audio. A native competing focus
request verifies pause, foreground gating, gain and renewed uplink. Ownership and the precise
verification limits are recorded in [audio.md](docs/audio.md).

For E2 login without a browser and the room-pairing sequence, see [connection.md](docs/connection.md).
The native login sheet displays an authorization QR, copyable link and live backend state. Its QR
was decoded from the actual E2 screenshot against the actual node's auth URL. The user has since
authorized the node and supplied a room link:
real pairing, historical rows, delivered text and an exact requested live reply were verified on
the E2. Pairing remained intact through an in-place APK update.

## Build

```sh
scripts/build-android.sh       # dist/PocketE2.apk, package top.kuaner.pockete2
scripts/test-host.sh           # pure protocol/reducer contracts
(cd bridge/tsbridge && go vet ./... && go test ./...)
# Install the built app before device tests:
adb install -r dist/PocketE2.apk
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_IMPORT_UI=1 scripts/test-device.sh
# Opt in to the isolated try/UI suite when a real room is already paired:
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_IMPORT_UI=1 POCKET_E2_TEST_PAIRED_TRY=1 scripts/test-device.sh
# Explicit real-service acceptance on an already paired, idle device:
POCKET_E2_TEST_ROOM_ONLY=1 scripts/test-device.sh
# Isolated voice faults/elapsed-time retries, optionally with real hold capture:
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_VOICE_ONLY=1 scripts/test-device.sh
# Explicitly send one marked synthetic voice fixture to the actual ASR/brain:
POCKET_E2_TEST_VOICE_ROOM_ONLY=1 scripts/test-device.sh
# Explicitly force-stop/cold-start during a real system-picked provider copy:
POCKET_E2_TEST_PROCESS_DEATH=1 scripts/test-device.sh
```

Requirements: JDK 17, Go 1.27.4, the x/mobile version in `bridge/tsbridge/go.mod` (gomobile and gobind),
Android Platform 35, Build Tools 35.0.0 and NDK 28.2.13676358. The scripts accept `ANDROID_SDK_ROOT`,
`ANDROID_NDK_ROOT` and `POCKET_E2_SIGN_DIR`. A private signing identity is generated once outside the
repository. Only arm64-v8a is currently built.

The optional native import UI probe currently targets API 27, including a disposable document source
in a separate UID. It declines the stock camera's optional location request and restores its prior
user-decision permission flags with an Android-8-only shell helper. The helper, provider and test APK
are removed after the run. The application APK does not include them.
On a paired device the try/UI tests require `POCKET_E2_TEST_PAIRED_TRY=1`; they refuse active
recording/camera work or a nonempty outbox and restore the original room, draft and presentation
preferences. Their notifications and sends stay inside the disposable try transport. Omitting
the flag runs the hardware/storage probes and cold start, which is a smaller acceptance scope.

The separate `POCKET_E2_TEST_ROOM_ONLY=1` run requires the previously accepted live integration reply.
It reads sessions permitted by the existing scope, uploads one small clearly named random fixture,
downloads/verifies it through the ordinary controller and removes its local draft/cache copies. The
server's upload API has no client delete operation, so the small remote fixture remains. It also
requests actual read-aloud synthesis and cancels a second request. Only its own AudioTrack is muted;
system volume is untouched. It preserves the real room/draft/files and sends no room messages or
session notifications. It refuses active work in either Pocket E2 or E2Studio.

`POCKET_E2_TEST_VOICE_ONLY=1` uses a disposable room, delayed HTTP, prepared recognition outcomes
and actual 25-second retry budgets. With capture enabled it also verifies the real E2 hold path's
cancel/background/focus-loss handling and retry of retained Opus bytes. It sends no real messages.
The separate `POCKET_E2_TEST_VOICE_ROOM_ONLY=1` test sends **one clearly marked synthetic voice
message** to actual ASR and requests the `语音链路验证成功` reply. It exercises a native Activity
background transition and the foreground upload service, then checks recognition, delivery,
outbox/file/service cleanup and a single server-log row. A private journal in the app's files retains
the original key across repeats/restarts; keep it to verify the same message without sending again.
This proves the actual API path for known synthetic speech. Acoustic recognition from the E2's
microphone, real ASR failure outcomes and prolonged background/process-death handling remain separate
acceptance work. See [audio.md](docs/audio.md).

`POCKET_E2_TEST_PROCESS_DEATH=1` selects one small synthetic document through the real file picker,
force-stops Pocket E2 after a separate-UID provider emits partial bytes, then cold-starts it twice.
It checks resumed complete bytes, one chip, retained draft/quote, released grants and no repeated
provider read. Native setup/cleanup restore the original composer and input preference. The ordinary
attachment path may upload this fixture; no chat or session message is sent. This is force-stop/cold
launch evidence rather than low-memory task restoration. See [imports.md](docs/imports.md).

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
