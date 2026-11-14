# Current status: implemented, remaining and priorities

English · [简体中文](status.zh-CN.md)

Updated 2026-11-15. The milestone delivers a maintainable `openduo/pocket-android` project and Android preview, with E2 as an adapter example. Full 1:1 parity remains a future comparison. Testing is deliberately limited to relevant checks, with older expanded evidence retained separately.

Published: [0.4.0 · Preview 2](https://github.com/openduo/pocket-android/releases/tag/v0.4.0-android.2)
([signed APK](https://github.com/openduo/pocket-android/releases/download/v0.4.0-android.2/Pocket.apk),
version code 2). This adds the localization follow-up to the first preview and can update it in place.

## Delivered

| Area | Concrete result |
| --- | --- |
| Build boundaries | Standard Gradle wrapper/AGP/CMake; pure `:core`, pure `:ui-policy`, framework `:device`, example `:device-e2`, and `:app`; source-boundary checks and GitHub workflow |
| Windows | Shared size classes, bounded columns, short-window/IME/Insets and page-state handling; business logic does not branch on E2 or physical-panel dimensions |
| Hardware | Default Android capture/routing and capability discovery; MIC fallback; E2 model/firmware/system-bar and optional array integration isolated in one module |
| Identity | DuoDuo Pocket / 多多, `dev.openduo.pocket`, `duoduopocket` links, upstream brand mark, legacy/adaptive/themed icons, one version source; arm64+x86_64 APK |
| Internationalization | English/Simplified Chinese and system fallback, 617-entry copy catalog, localized native controls/accessibility/notifications/errors; all production modules and native resources checked; English public docs with Chinese companions and English screenshots |
| P0 implementation | Tailscale login/pairing/live and network recovery; text/attachments/history; durable/idempotent outbox/drafts; voice upload/retry, ambient playback/interruption |
| P1 implementation | Sight negotiation and Camera2/H.264, sessions/details/notification controls, maps/coordinates/geocoder/arrival ledger, import/share/Try/diagnostics |
| Real recovery fix | Restored Wi-Fi left tsnet caching network-down; recovery injects a netmon event. Earlier physical E2 build recovered Online in about 2.7s, same process, independently observed from the computer |
| Handoff | Architecture, device/UI/localization guides, English screenshots and future iOS gap record; firmware, credentials, signing and private evidence excluded |

## Current verification

- 325 JVM contracts pass, including Gradle `:core:check`; module/window/hardware/attribution boundaries and 617-entry localization checks pass.
- Go vet/test and Gradle Release pass. Public APK contains arm64-v8a+x86_64 and only the public native payload.
- Three representative API35 windows pass: 320×640dp, 1280×800dp and 740×360dp. The final asset update did not change layout/business behavior; the native smoke was repeated on the icon-updated release candidate. The last launcher-label fallback change was build-checked only.
- Five native smoke assertions pass for explicit opt-in, Opus JNI, actual standard capture/packets and microphone release. Optional local E2 JNI compilation passes.
- English documentation captures use prepared Try content on a physical E2 and phone/tablet/landscape emulators. They do not exercise microphone/camera or send real room content. See [screenshot provenance](screens/README.md).
- [Remote CI for Preview 2](https://github.com/openduo/pocket-android/actions/runs/43609534160) passes, including host contracts, source/localization checks, Go checks and the dual-ABI APK build. Localization checks cover all 117 production Java sources, direct untranslated English labels, native resources and E2 array read-error translation. The signed release is build/contract-checked; audio/window smoke evidence remains attached to the first candidate. Published APK/checksum asset SHA-256 values match the local release.

Earlier actual text/history/files/session reading, E2 array/camera and synthetic-ASR → brain/tool → muted-playback results belong to earlier application IDs/APKs. They are useful evidence, not full acceptance of the new package. The eight-hour task ended early and is not restarted. See [verification limits](acceptance.md) and [historical status](status-2026-11-14-history.md).

## Unreleased client follow-up

The client now shares a pure credential-scope policy across session controls and controller
actions. Read-only pairings explain why notifications require `notify` scope; a recipient's
capability cannot override that scope. A master connection without media support shows a
missing-video-capability explanation even without a camera frame, and a new connection clears the old
negotiation state. The notification probe now requires an explicitly selected dedicated
ambient/keepalive recipient and preserves a unique, once-only delivery journal.

The local signed Release build, 331 JVM contracts and 618-entry localization/source checks pass.
One API 35 prepared-camera UI run passes 14 targeted assertions with capture disabled, including
the no-camera-frame warning and its removal when media support is declared.
These changes are not included in the published Preview 2. Natural speech/audible output and
Sight remain pending; actual notification receipt now passes on the local original-package E2
build. [Short service checks](service-acceptance.md) record the prerequisites and bounded results.

On 2026-11-15 the user confirmed there is no new room-service release to install. Sight is
waiting for a released service with media support. Speech/audible replies and scoped notification
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
by the existing system screen timeout. No system setting, microphone, camera or playback changed.
Natural speech/audible output remains unaccepted; Sight remains release-dependent. No public
APK rebuild or broader regression was needed for this QA/documentation follow-up.

The retained E2 **Play spoken replies** preference was off. It is now enabled through the app
and the original hold-to-talk composer is restored, ready for one actual human turn. This setup
does not itself establish recording, recognition or audible output.

## Remaining work and priorities

| Priority | Remaining or unverified | What a next milestone needs |
| --- | --- | --- |
| P0 actual use | Natural speech with audible replies/full ambient conversation and long-term background recovery are not fully accepted | The original package is Online with retained credentials; next confirm one actual microphone/recognition/audible-reply turn |
| P1 actual services | Sight awaits a released media-capable room service; physical visits remain unverified; selected-session notification receipt now passes on the local original-package E2 build | Resume Sight after the required service release; visits need a device with location/geocoder; notification disconnect uncertainty and broader target/platform coverage remain future work |
| Future hardware | No target display-equipped smart speaker accepted; OEM array/AEC/background and remote/no-touch interaction remain open | Establish API/ABI/input capabilities, verify the standard path, add an adapter for actual differences |
| Future compatibility | No ARMv7/32-bit package; not all OEM/foldable/external-display/lifecycle combinations covered | Add ABI/device/window support when a real target requires it |
| Maintenance | Android controller remains large; persistence/system services remain in app | Gradually split by business owner within the established protocol/window/hardware boundaries |
| Deferred | Android push registration/relay/receiver, AES-GCM/HMAC/ledger/upstream vectors; external Passport Bluetooth | Explicitly resume those areas in a later milestone |
| Old-package migration | No cross-package database/AndroidKeyStore migration | Use the explicit same-package/same-signature build to retain the original node and data; the separate public package requires its own login/pairing |

Smart-speaker readiness currently means reusable architecture and framework paths. Wake words, kiosk/always-on launcher behavior, privileged background execution and OEM playback engines are not implemented. The [migration record](migration.md) preserves the longer-term 1:1 comparison; the preview version does not imply iOS feature parity.
