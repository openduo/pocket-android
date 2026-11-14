# Verification scope and historical evidence

English · [详细中文历史记录](acceptance.zh-CN.md)

The current milestone uses necessary build/contracts and short representative checks. It does not restart the earlier eight-hour background task or extend rotation/accessibility matrices. Prepared Try replies, protocol fixtures and compiled pages are separate from real-service acceptance.

| Current Android package | Result |
| --- | --- |
| Pure Java contracts | 325 assertions passed; also run through Gradle `:core:check` |
| Source boundaries/localization | Module, UI, window, OEM and SPDX checks passed; 617-entry catalog |
| Go | vet/test passed with the network-recovery fix retained |
| Android build | Gradle Release passed; arm64-v8a+x86_64; public-payload check passed |
| Representative windows | 320×640dp, 1280×800dp, 740×360dp passed on an API35 emulator |
| Native smoke | Five assertions: explicit capture opt-in, JNI Opus roundtrip, actual framework input/packets and microphone release |
| E2 adapter | Local optional native adapter compiled; proprietary payload excluded from public APK |
| Documentation captures | English prepared-content screenshots on physical E2 and emulators; no actual room/audio/camera operation, not feature acceptance |

The window run preceded a theme-aware icon/legal-assets update; the native smoke was repeated on the icon-updated release candidate. A final launcher-label fallback change was build-checked; the native smoke was not repeated for that resource-only change. No business behavior changed in that final asset update. Exact local APK hashes/results remain in ignored `.build/`. Screenshot provenance is public in [screens](screens/README.md).

Preview 2 (version code 2) adds an E2 error-translation fix and broader build-time localization checks.
Its host contracts, source checks, signed Release build and public-payload check pass. The window,
audio and screenshot evidence above belongs to the earlier candidates; those probes were not rerun
for this patch. [Remote CI](https://github.com/openduo/pocket-android/actions/runs/43609534160) also
passes. The downloadable release includes its own SHA-256 checksum, verified against the published assets.

Earlier `top.kuaner.pockete2` builds had actual text/history/files/session reading, native E2 array/camera capture, prepared-ASR-to-brain/tool/muted-playback, short network recovery, storage/force-stop and extensive window/accessibility evidence. Each has its own APK identity and limits; do not combine them into acceptance of one release binary. Detailed records are in [the Chinese archive](acceptance.zh-CN.md), [historical status](status-2026-11-14-history.md) and feature documents.

The user-reported Wi-Fi incident was reproduced: tsnet retained a cached all-links-down state after Wi-Fi returned. Recovery now injects a netmon event before rebind/restun. One real E2 background outage/return recovered Online in about 2.7 seconds with the same process and an online peer observed from the computer. That evidence does not establish overnight stability.

Natural speech with audible replies, actual Sight media negotiation, notification receipt, physical arrival, target smart-speaker acoustics and long-term background behavior remain open. Push and Passport are deferred. See [status](status.md) and [migration gaps](migration.md).

The unreleased client follow-up passes 331 JVM contracts, 618-entry localization/source checks
and a signed Release build. It enforces device scope independently of recipient capability,
explains a missing media declaration without requiring a camera frame and replaces the
host-hidden own-room notification target with an explicit dedicated test recipient. The
[short actual-service guide](service-acceptance.md) records what must still be verified. These
client checks do not complete actual voice, Sight or notification acceptance.

One targeted API 35 run passes 14 Sight UI assertions with microphone/camera capture disabled.
Prepared metadata covers a master seat without any camera frame, clearing the warning after media
support is declared, and the existing refusal/reset states. The notification probe compiles; it
has not sent to an actual recipient.
