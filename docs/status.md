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
These changes are not included in the published Preview 2. The three actual-service paths remain
pending; [short service checks](service-acceptance.md) describe their prerequisites and bounded
acceptance steps.

On 2026-11-15 the user confirmed there is no new room-service release to install. Sight is
waiting for a released service with media support. Speech/audible replies and scoped notification
delivery can proceed independently when work resumes; the new Android package still needs its
own login/pairing. The video message now describes missing capability rather than asking for an
available update. This wording-only correction is source/localization-checked; build and UI
evidence above belongs to the preceding candidate.

## Remaining work and priorities

| Priority | Remaining or unverified | What a next milestone needs |
| --- | --- | --- |
| P0 actual use | Natural speech with audible replies/full ambient conversation and long-term background recovery are not fully accepted | Short target-device login/online/voice/reply checks first, then fix actual failures |
| P1 actual services | Sight awaits a released media-capable room service; the old notification probe selected the host-hidden own room, so no notification was sent; physical visits unverified | Resume Sight after the required service release; independently complete notify-scoped pairing with an explicit dedicated recipient; visits need a device with location/geocoder |
| Future hardware | No target display-equipped smart speaker accepted; OEM array/AEC/background and remote/no-touch interaction remain open | Establish API/ABI/input capabilities, verify the standard path, add an adapter for actual differences |
| Future compatibility | No ARMv7/32-bit package; not all OEM/foldable/external-display/lifecycle combinations covered | Add ABI/device/window support when a real target requires it |
| Maintenance | Android controller remains large; persistence/system services remain in app | Gradually split by business owner within the established protocol/window/hardware boundaries |
| Deferred | Android push registration/relay/receiver, AES-GCM/HMAC/ledger/upstream vectors; external Passport Bluetooth | Explicitly resume those areas in a later milestone |
| Old-package migration | No cross-package database/AndroidKeyStore migration | Fresh login and pairing in the new app; preserve the old app/data |

Smart-speaker readiness currently means reusable architecture and framework paths. Wake words, kiosk/always-on launcher behavior, privileged background execution and OEM playback engines are not implemented. The [migration record](migration.md) preserves the longer-term 1:1 comparison; the preview version does not imply iOS feature parity.
