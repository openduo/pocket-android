# Current status: implemented, remaining and priorities

English · [简体中文](status.zh-CN.md)

Updated 2026-11-17. The Android release candidate shares its adaptive UI across devices, with E2
as a hardware adapter example. Full 1:1 parity remains a future comparison. Earlier test results
are retained with their own build identities and limits.

Published: [0.4.0 · RC 1](https://github.com/openduo/pocket-android/releases/tag/v0.4.0-rc.1)
([signed APK](https://github.com/openduo/pocket-android/releases/download/v0.4.0-rc.1/Pocket.apk),
version code 4). It includes the new chat header, compact composer, shared recording feedback and
window layouts described in [the UI guide](ui.md). The public package and signature are retained.
The English gallery was recaptured from the RC using prepared Try content.

## Historical Preview 3 release verification

[Preview 3](https://github.com/openduo/pocket-android/releases/tag/v0.4.0-android.3) introduced
Sight service adaptation, pocket-rs 0.1.1, screen-lock playback, Passport 1.3 and key bindings.

- 2,634 actual-JNI JVM assertions, source/ownership checks, 653 catalog entries across 145 production
  Java sources, Go vet/tests, dual-ABI Debug and signed generic Release builds pass.
- The APK retains `dev.openduo.pocket`, version name `0.4.0` and the original public signing key;
  version code advances to 3. Only public native libraries for arm64-v8a and x86_64 are included.
  `Pocket.apk` and `SHA256SUMS` are the application release assets.
- SDK 0.1.1 is pinned at `86fae5df622ddefc436861bd0f0df7d1fa390864`. Its renewed physical Linux
  ARM64 installation/C consumers, macOS C/Swift/JNI consumers and Apple/Android packages pass.
- The Sight service check and prior bounded Rust/lockscreen/Passport/input checks below retain
  their own build identities and limits. Metadata/version changes do not repeat capture or room
  sends. Matching remote CI links and published asset checksums are in the release notes.

## E2 recording restoration and touch feedback

The generic APK had replaced the E2 array build during the public-package migration. Firmware
04319's framework recorder opens but supplies no useful microphone signal. The same-signature
public-identity local array APK is now restored with data/pairing retained. Local voice/natural
calibration captures the played tone through the native array; capture releases afterward and no
recording is uploaded or sent to the room. The generic-build guard separately passes: localized
unavailable-input reason, zero packets and released microphone. Headset input keeps its framework
route. This confirms restored input, not new human ASR accuracy.

Touch, hardware and accessibility use the same recording panel, with an animation that indicates
recording state. The touch button stays in place; recordings started without touch show Send and
Cancel controls in the panel. The [bounded E2 input check](input.md#bounded-verification) used
injected events, actual capture and Try-only sends, then restored the paired room Online.

Explicit firmware builds write local-only `dist/Pocket-E2.apk`, keeping `dist/Pocket.apk` available
for generic publication. E2 must stay on the adapted APK. No proprietary libraries/models enter
public assets. See [audio evidence](audio.md#e2-built-in-capture) and [local build](devices.md#e2-example).

## Sight resumed with the updated room service

At the user's request on 2026-11-15, Sight resumed against the released ambient `0.4.0-rc.2`
protocol. Actual E2 acceptance now passes 14 guards: `media=1`, the real 1280×720 camera ask and
master seat, first native key frame and five uplink frames, cleared server `vision_off`, explicit
camera off, room-microphone restoration and preserved pairing/composer/Online. Native camera
resources are released afterward. Microphone packets were replaced with paced silence; no speech,
text message or notification was sent.

The generic encoder path now tries the request's swapped orientation when the upright dimensions
are unsupported, rotating/aspect-fitting YUV with neutral black padding. The Rust owner/JNI now
preserve raw Opus downlink on media connections; only uplink has kind framing. The updated signed
public-package APK is installed on E2. The 2,634 JVM assertions (12 new), 21 Rust tests, Clippy,
Debug/Release and source/localization checks pass. [Sight details](sight.md) retain scene-recognition,
sustained-rate/thermal and other-hardware limits. This supersedes the earlier deferral and ships
in Preview 3 with SDK 0.1.1.

## Shared Rust protocol adoption

The current source uses [pocket-rs](https://github.com/openduo/pocket-rs) through the new Android-free
`:protocol` Java/JNI boundary. Pairing/endpoints, Chat/file/speech/live request construction, voice
framing, live event validation, Ambient seat/input/output and retry rules now execute in Rust.
Their Java facades retain existing domain APIs without a second protocol reducer. Android keeps
its UI, SQLite transactions, credential/Tailscale networking, codec/device resources and foreground
policy. OutboxSender/RoomLink coordinate those platform effects; the aggregate Rust Session is not
also dispatched. See [shared-library integration](rust.md).

The actual JNI-backed JVM contracts pass 2,622 assertions. The dual-ABI public-identity Release APK
is installed on E2 with the existing signature, data, pairing and node. The bounded native check
passes 16 Rust/ART assertions plus 22 Try text/prepared-voice/files/read/reconnect assertions, followed
by 15 separate actual screen-off playback assertions. Audio is prepared and silent; no microphone
or actual room test content is sent. Settings/composer are restored and the real room returns Online.
This supplements the earlier lockscreen evidence with the E2/Rust path. Preview 3 includes this
integration and the subsequent media-v1 downlink correction. Rust protocol migration is not a claim of complete 1:1 app parity or new human ASR acceptance.


## Delivered

| Area | Concrete result |
| --- | --- |
| Build boundaries | Standard Gradle wrapper/AGP/CMake; pure `:core`, `:protocol`, `:ui-policy` and `:input`, framework `:device`, example `:device-e2`, and `:app`; source-boundary checks and GitHub workflow |
| Windows | Shared size classes, bounded columns, short-window/IME/Insets and page-state handling; business logic does not branch on E2 or physical-panel dimensions |
| Hardware | Default Android capture/routing and capability discovery; MIC fallback; E2 model/firmware/system-bar and optional array integration isolated in one module |
| Identity | DuoDuo Pocket / 多多, `dev.openduo.pocket`, `duoduopocket` links, upstream brand mark, legacy/adaptive/themed icons, one version source; arm64+x86_64 APK |
| Internationalization | English/Simplified Chinese and system fallback, 653-entry current-source copy catalog (617 in the first preview), localized native controls/accessibility/notifications/errors; all production modules and native resources checked; English public docs with Chinese companions and English screenshots |
| P0 implementation | Tailscale login/pairing/live and network recovery; text/attachments/history; durable/idempotent outbox/drafts; voice upload/retry, ambient playback/interruption |
| P1 implementation | Sight negotiation and Camera2/H.264, sessions/details/notification controls, maps/coordinates/geocoder/arrival ledger, import/share/Try/diagnostics |
| Real recovery fix | Restored Wi-Fi left tsnet caching network-down; recovery injects a netmon event. Earlier physical E2 build recovered Online in about 2.7s, same process, independently observed from the computer |
| Handoff | Architecture, device/UI/localization guides, English screenshots and future iOS gap record; firmware, credentials, signing and private evidence excluded |

## Historical Preview 1/2 verification

- 325 JVM contracts pass, including Gradle `:core:check`; module/window/hardware/attribution boundaries and 617-entry localization checks pass.
- Go vet/test and Gradle Release pass. Public APK contains arm64-v8a+x86_64 and only the public native payload.
- Three representative API35 windows pass: 320×640dp, 1280×800dp and 740×360dp. The final asset update did not change layout/business behavior; the native smoke was repeated on the icon-updated release candidate. The last launcher-label fallback change was build-checked only.
- Five native smoke assertions pass for explicit opt-in, Opus JNI, actual standard capture/packets and microphone release. Optional local E2 JNI compilation passes.
- English documentation captures use prepared Try content on a physical E2 and phone/tablet/landscape emulators. They do not exercise microphone/camera or send real room content. See [screenshot provenance](screens/README.md).
- [Remote CI for Preview 2](https://github.com/openduo/pocket-android/actions/runs/43609534160) passes, including host contracts, source/localization checks, Go checks and the dual-ABI APK build. Localization checks cover all 117 production Java sources, direct untranslated English labels, native resources and E2 array read-error translation. The signed release is build/contract-checked; audio/window smoke evidence remains attached to the first candidate. Published APK/checksum asset SHA-256 values match the local release.

Earlier actual text/history/files/session reading, E2 array/camera and synthetic-ASR → brain/tool → muted-playback results belong to earlier application IDs/APKs. They are useful evidence, not full acceptance of the new package. The eight-hour task ended early and is not restarted. See [verification limits](acceptance.md) and [historical status](status-2026-11-14-history.md).

## Lockscreen spoken-reply fix

The user-reported interruption is reproduced: the previous generic APK stopped non-ambient playback
and closed its socket when the actual display went to sleep. Ongoing replies and local reads now
keep their receive/playback lifetime independent of Activity visibility. The session service protects
playback with `mediaPlayback`, a partial CPU wake lock and a localized Stop playback action. Matching
completion plus native drain releases otherwise idle background work; missing receipts have a bounded
idle fallback. See [ownership and evidence](audio.md#finishing-an-ongoing-reply-while-locked).

The fixed signed generic APK passes 15 short API35 screen-off assertions, including exact six-second
silent native playback with packets received after screen-off and service/socket/wake-lock cleanup.
The full lightweight JVM check passes 2,622 assertions (seven new). Generic Debug/Release, source and
650-entry localization checks, and public dual-ABI payload checks pass. No microphone, actual room
content or broad matrix was used. At the user's subsequent request, the old E2 app was uninstalled
and the fixed signed `dev.openduo.pocket` APK was installed and opened. Fresh Tailscale login/pairing is complete; E2 screen-off acceptance now passes in the Rust migration
check described above. That APK used standard Android framework audio; the E2 recording correction
above subsequently restored the local array adapter under the same public identity. Earlier human
ASR evidence keeps its original build identity. This fix ships in Preview 3. Starting a new spoken reply after the app is already idle in the background remains separate.

## Ownership decomposition and stopping point

The follow-up moves draft/quote/attachments and edit acknowledgement into `:core/Composer`,
bounded history pagination into `:core/HistoryPager`, live generations/queues/final controls into
`:core/RoomLink`, and cancellable upload/download jobs into `:core/AttachmentTransfers`.
Android `AttachmentStore` supplies only file-storage operations. `:app/TailnetSession`,
`:app/Microphone` and `:app/RoomPlayback` own authorization, capture and output lifetimes.
`ConversationComposer` separates editor/IME/attachment state from the conversation feed.
The owners share the controller's state executor; extracted state is not duplicated in the controller.

This decomposition stops after an ownership audit of the remaining controller and larger classes.
Pairing/Try/credential transitions, database admission, audio focus, permissions and foreground-service
ordering remain application coordination. Existing feature owners, transactional SQLite and keyed
feed rendering retain their cohesive boundaries. No new module, runtime container or broad forwarding
interface was introduced. See the [owner table and stopping decisions](architecture.md#stopping-point-and-ownership-audit).

The final lightweight JVM check passes 2,615 assertions, including 38 new deterministic assertions
for draft/import admission, bounded history and its late bootstrap, live generations/final controls,
and per-attachment cancellation/result admission. Generic Debug and same-package local E2 Release
builds pass. Source/localization checks pass across 143 production Java sources and 646 catalog
entries; the generic dual-ABI APK contains only the public native payload.

The final installed original-package E2 build passes 24 short coordination assertions: prepared
Try-only file import/upload/download, editor-to-draft acknowledgement, text/prepared voice sends,
silent native playback/drain/cancellation, live reconnect, and a brief real capture cancelled without
sending its recording. The original room returns Online with pairing and composer retained; the probe
restores reply-audio, mute and input preferences. No actual room test content, human turn, notification,
camera, rotation or long-background matrix was run. The explicit runner is
`POCKET_LEGACY_IDENTITY=1 POCKET_TEST_COORDINATION_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh`
with `ANDROID_SERIAL` selecting the intended device and matching local build/classpath.
This checkpoint is source/local legacy-build evidence. Preview 3 now distributes the refactor;
the device assertions above retain their original build identity.

## Outbox and read-aloud ownership refactor

After the input refactor, `:core/OutboxSender` owns the active HTTP delivery, immutable voice
body/surface, request registration, retry budget, cancellation and bounded send worker.
`:core/SpeechReader` owns read identity, stream framing/cancellation and current completion;
`:app/ReadAloudOutput` owns the stream-thread decoder and shared playback admission. Database
transactions, foreground-service gating, audio focus and room effects remain in the controller,
on the same state executor. There is no duplicate controller send/read generation state or
device-specific delivery implementation. See [architecture and handoffs](architecture.md).

The 71 new deterministic assertions cover the changed asynchronous boundaries, including
pre-dispatch storage rejection, stale/unauthorized completions, fixed retry identity/body,
voice/Passport budgets, occupied worker recovery, stream framing/cancellation, decoder ownership
and playback-drain identity. The full lightweight JVM check totals 2,577 assertions. Generic
Debug and same-package local E2 Release builds pass; all retained device probes compile, and
source/localization checks pass (646 catalog entries, 135 production Java sources).

One original-package E2 check passes 14 bounded coordination assertions: Try-only text and
prepared voice sends, actual silent Opus/AudioTrack playback and drain, shared stop/cancellation,
and restoration of the existing room Online with pairing/composer retained. It does not record
audio or send actual room content. Human speech, notification receipt, physical keys and broad
device/lifecycle tests were not repeated. The checkpoint used a local legacy build; Preview 3
now distributes the refactor. The explicit short runner is
`POCKET_LEGACY_IDENTITY=1 POCKET_TEST_COORDINATION_ONLY=1 scripts/test-device.sh`
with `ANDROID_SERIAL` set to the intended device and a matching local build/classpath.

At that checkpoint, connection/reconnect, draft/attachment/history and ambient audio still lived
in the controller. The current follow-up above extracts their independently owned state/resources
and records why the remaining application ordering stays together.

## Foreground input refactor after Preview 2

The current source adds a pure `:input` module and shared touch/key/remote actions. E2 REC
starts/sends voice; MARK cancels recording or stops speech. Settings can learn, reassign, disable
or remove bindings per input device. Native D-pad/OK/Back navigation, filled focus states and
map/image pan-mode exit support use without touch. `:core/VoiceNotes` now owns note bytes,
source identity, durable admission and recovery; the controller coordinates capture without
another copy of recording state. See [input architecture and extension rules](input.md).

Generic Debug and same-package local E2 Release builds pass, along with the relevant new JVM
contracts and architecture/localization checks (646 catalog entries). The final local E2 build
passes 27 bounded input assertions: D-pad navigation, on-device key learning/action selection,
one Try voice send, MARK cancellation, touch/hardware ownership, repeat and window-focus
cancellation, and persistence/restoration of overrides. Actual native-array capture was used;
Android key events were injected. The original room returned Online with its pairing and data
preserved; no new tailnet authorization/node or real room test message was needed. The probe
restored the initial voice-input setting and all bindings it changed.

Physical REC/MARK presses, a real remote and target-speaker hardware remain unaccepted. No
rotation, service-message, long-background or broad device matrix was added. These changes are
included in Preview 3. The recorded input probe used the earlier local legacy build.

## Client scope and capability follow-up included in Preview 3

The client now shares a pure credential-scope policy across session controls and controller
actions. Read-only pairings explain why notifications require `notify` scope; a recipient's
capability cannot override that scope. A master connection without media support shows a
missing-video-capability explanation even without a camera frame, and a new connection clears the old
negotiation state. The notification probe now requires an explicitly selected dedicated
ambient/keepalive recipient and preserves a unique, once-only delivery journal.

The local signed Release build, 331 JVM contracts and 618-entry localization/source checks pass.
One API 35 prepared-camera UI run passes 14 targeted assertions with capture disabled, including
the no-camera-frame warning and its removal when media support is declared.
These changes ship in Preview 3. One human voice-note/audible-reply
turn and actual notification receipt now pass on the local original-package E2 build. The user
initially deferred Sight on 2026-11-15; the resumed acceptance at the top supersedes that checkpoint.
[Short service checks](service-acceptance.md) record the prerequisites and bounded
results.

At that earlier checkpoint on 2026-11-15 the user confirmed there was no new room-service release
to install, so Sight waited for media support. The later resumed check above supersedes that state. Speech/audible replies and scoped notification
delivery can proceed independently. The video message now describes missing capability rather
than asking for an available update; that correction is included in the later local builds.

At the user's request, testing now uses an explicit compatibility build on the original
`top.kuaner.pockete2` package. Its original signature, preferences, database, AndroidKeyStore alias
and tailnet directory are retained. A same-signature update on the physical E2 reached actual
Online state with the existing `pocket-e2` node, `notify` scope and 23 cached rows; the computer
independently saw that one original node online. No new authorization or pairing was performed.
The local E2 test APK explicitly includes the local array payload; it is not a public artifact.
The public package remains `dev.openduo.pocket`; its default build was separately checked for
that identity and public-only native payload after switching back from the opt-in firmware build.
Both signed builds pass. Node-name UI now follows the selected build identity. See
[same-package testing](devices.md#same-package-testing).

The initial prerequisite check was read-only. The subsequent actual notification run used a new
isolated keepalive recipient provisioned through the already paired room, with no outgoing
notification route. Ten actual-service assertions pass: the shipping controller receives the
correlated server success, the recipient contains exactly one uniquely marked notification,
and the original room composer/outbox are preserved. The dedicated test job was then archived.
The short probes keep only their own window awake after one prerequisite attempt was interrupted
by the existing system screen timeout. Those notification checks did not change system settings
or use microphone, camera or playback. Sight remains release-dependent. No public APK rebuild or
broader regression was needed for this QA/documentation follow-up.

The retained E2 **Play spoken replies** preference was off. It is now enabled through the app
and the original hold-to-talk composer is restored. On 2026-11-15 the user completed one actual
human turn: the room UI shows a four-second voice note, recognition text, delivered status and a
reply, and the user confirmed receiving the voice reply. Recognition had a wording error. This
accepts that microphone-to-room/audible-reply path on the local E2 array build, not exact ASR
accuracy, full hands-free ambient conversation or the public framework-audio APK. No further
recording or replay was run to document the result.

## Passport protocol follow-up included in Preview 3

Passport was resumed on 2026-11-15. The current source implements the iOS 1.3 wire codec,
press assembly, RESULT/reconnect deadlines, reply/WORK/quiet/replay rules and persistent reply
memory against iOS `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. Pure reducers and the bounded
outbox live in `:core`; Android BLE and controller integration remain in `:app`. Settings exposes
optional pairing again. Try mode suspends BLE and cannot forward prepared replies to an accessory.

Sixty scenarios generated by the unmodified upstream Swift code pass on the JVM and E2 ART
(1,050 comparisons), alongside 1,093 official Unicode grapheme cases. The latter fixed an actual
old-Android family-emoji truncation discrepancy. The host suite totals 2,484 assertions; Release
build and 618-entry localization/source checks pass across 123 production sources. No physical
Passport was connected, no room content was sent and no APK was installed for these protocol
checks. Actual pairing/CCCD, a hardware press/reply/alert and background KEEPALIVE wake remain
unaccepted. Preview 3 includes the implementation. See [Passport](passport.md).

## Remaining work and priorities

| Priority | Remaining or unverified | What a next milestone needs |
| --- | --- | --- |
| P0 actual use | Full hands-free ambient conversation and long-term background recovery remain unaccepted | One actual human voice-note/audible-reply turn now passes on the local original-package E2; do not repeat or expand this check without a relevant need |
| P1 actual services | Physical visits remain unverified; selected-session notification receipt now passes on the local original-package E2 build | Visits need a device with location/geocoder; notification disconnect uncertainty and broader target/platform coverage remain future work |
| Sight follow-up | Actual negotiation/uplink now passes on the updated public-package E2 build; scene recognition and sustained rate/thermal behavior remain unaccepted | See [bounded evidence](sight.md); do not repeat the successful check without a relevant change |
| Future hardware | No target display-equipped smart speaker accepted; OEM array/AEC/background and actual remote/key delivery remain open; shared foreground no-touch navigation is implemented | Establish API/ABI/input capabilities, verify the standard path, add an adapter for actual differences |
| Future compatibility | No ARMv7/32-bit package; not all OEM/foldable/external-display/lifecycle combinations covered | Add ABI/device/window support when a real target requires it |
| Maintenance | Ownership decomposition has reached the documented stopping point; application ordering and Android persistence/system services remain in app | Extend the existing owner when appropriate; extract again only for independently owned state/resources or demonstrated coupling, rather than file length |
| Passport physical acceptance | Protocol 1.3 fixed vectors and E2 ART pass; actual BLE pairing/CCCD, physical press/reply/alert and background wake remain open | Use an explicitly available accessory without taking E2Studio's connection; see [protocol evidence](passport.md) |
| Deferred | Android push registration/relay/receiver, AES-GCM/HMAC/ledger/upstream vectors | Explicitly resume those areas in a later milestone |
| Old-package migration | No cross-package database/AndroidKeyStore migration | Use the explicit same-package/same-signature build to retain the original node and data; the separate public package requires its own login/pairing |

Smart-speaker readiness currently means reusable architecture and framework paths. Wake words, kiosk/always-on launcher behavior, privileged background execution and OEM playback engines are not implemented. The [migration record](migration.md) preserves the longer-term 1:1 comparison; the preview version does not imply iOS feature parity.

The [integration CI](https://github.com/openduo/pocket-android/actions/runs/43675746653) passes for
executable source `4208864`; the final documentation and SDK-documentation pin change no executable source.
