# Current status: implemented, remaining and priorities

English · [简体中文](status.zh-CN.md)

Updated 2026-11-14. The milestone delivers a maintainable `openduo/pocket-android` project and Android preview, with E2 as an adapter example. Full 1:1 parity remains a future comparison. Testing is deliberately limited to relevant checks, with older expanded evidence retained separately.

## Delivered

| Area | Concrete result |
| --- | --- |
| Build boundaries | Standard Gradle wrapper/AGP/CMake; pure `:core`, pure `:ui-policy`, framework `:device`, example `:device-e2`, and `:app`; source-boundary checks and GitHub workflow |
| Windows | Shared size classes, bounded columns, short-window/IME/Insets and page-state handling; business logic does not branch on E2 or physical-panel dimensions |
| Hardware | Default Android capture/routing and capability discovery; MIC fallback; E2 model/firmware/system-bar and optional array integration isolated in one module |
| Identity | DuoDuo Pocket / 多多, `dev.openduo.pocket`, `duoduopocket` links, upstream brand mark, legacy/adaptive/themed icons, one version source; arm64+x86_64 APK |
| Internationalization | English/Simplified Chinese and system fallback, 617-entry copy catalog, localized native controls/notifications/errors; English public docs with Chinese companions and English screenshots |
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
- Remote CI/publication results are recorded separately from local checks.

Earlier actual text/history/files/session reading, E2 array/camera and synthetic-ASR → brain/tool → muted-playback results belong to earlier application IDs/APKs. They are useful evidence, not full acceptance of the new package. The eight-hour task ended early and is not restarted. See [verification limits](acceptance.md) and [historical status](status-2026-11-14-history.md).

## Remaining work and priorities

| Priority | Remaining or unverified | What a next milestone needs |
| --- | --- | --- |
| P0 actual use | Natural speech with audible replies/full ambient conversation and long-term background recovery are not fully accepted | Short target-device login/online/voice/reply checks first, then fix actual failures |
| P1 actual services | Sight host did not confirm media=1; current room was ineligible for session-notification receipt; physical visits unverified | Capable host, an explicit eligible recipient and a device with location/geocoder |
| Future hardware | No target display-equipped smart speaker accepted; OEM array/AEC/background and remote/no-touch interaction remain open | Establish API/ABI/input capabilities, verify the standard path, add an adapter for actual differences |
| Future compatibility | No ARMv7/32-bit package; not all OEM/foldable/external-display/lifecycle combinations covered | Add ABI/device/window support when a real target requires it |
| Maintenance | Android controller remains large; persistence/system services remain in app | Gradually split by business owner within the established protocol/window/hardware boundaries |
| Deferred | Android push registration/relay/receiver, AES-GCM/HMAC/ledger/upstream vectors; external Passport Bluetooth | Explicitly resume those areas in a later milestone |
| Old-package migration | No cross-package database/AndroidKeyStore migration | Fresh login and pairing in the new app; preserve the old app/data |

Smart-speaker readiness currently means reusable architecture and framework paths. Wake words, kiosk/always-on launcher behavior, privileged background execution and OEM playback engines are not implemented. The [migration record](migration.md) preserves the longer-term 1:1 comparison; the preview version does not imply iOS feature parity.
