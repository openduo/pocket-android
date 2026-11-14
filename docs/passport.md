# Passport protocol and Android integration

English · [简体中文](passport.zh-CN.md)

The current source implements Passport protocol **1.3**, referenced to Pocket iOS
[`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`](https://github.com/openduo/pocket-ios/tree/b9d91bb9b77a12c3e59e6f359af1e4537a4ded39).
Passport was resumed on 2026-11-15. Sight and immediate Android push remain deferred.
This follow-up is not included in the published Preview 2 APK.

## Ownership and behavior

| Owner | Responsibility |
| --- | --- |
| `:core` / `PocketProtocol` | Service/TX/RX UUIDs, little-endian wire layout, direction/length validation, 4 KB reassembly, ATT fragmentation and quiet flags |
| `:core` / `PressAssembler` | Explicit/implicit starts, sequence wrap, duplicate/gap accounting, superseding presses and disconnect completion |
| `:core` / `VoiceResultWait`, `PassportResults` | Recognition and admission ordering, RESULT codes 0–4, reconnect generations, a monotonic 25-second deadline and one settlement per note |
| `:core` / `ReplyTracker` | Multiple pending notes, WORK phases, partial/final replies, shared reply IDs, live/log dedup, replay selection, quiet rules and persistent delivery memory |
| `:core` / `PassportOutbox` | Serialized fragmented messages, queued-partial replacement and bounded backpressure |
| `:core` / text | CommonMark plain text for the device and UTF-8 fitting at Unicode grapheme boundaries; spoken text keeps its separate filtering rules |
| `:app` / `PassportLink` | Android BLE scan/connect, system numeric-comparison pairing, MTU/CCCD, finite operation timeouts, reconnects and permission handling on one BLE thread |
| `:app` / `PassportCoordinator` | Controller-owned reducers, room-scoped reply memory, APP_STATE language/reachability, background wake/catch-up and Try isolation |
| `:app` / `PassportPage` | Scrollable settings, pairing entry, firmware/protocol/battery and latest-answer presentation; no protocol state in views |

All device-to-phone messages (`INFO`, `PRESS_START`, `AUDIO`, `PRESS_END`, `STATUS`,
`KEEPALIVE`) and phone-to-device messages (`RESULT`, `REPLY`, `REPLY_DONE`, `APP_STATE`,
`WORK`) are implemented. A matching major version is accepted, including older minor versions;
an incompatible major reports upgrade state and cannot route presses. Unknown charging remains
unknown. The device receives the selected Chinese/English language.

Recognition success starts answer waiting; an HTTP admission alone does not. A cleaned result may
precede the admission callback, and a logged row may supply recognition on reconnect. RESULT
expiry uses the remaining press deadline for HTTP work as well. Replies to a device press always
alert; unrelated replies are quiet while the app is in front. Replays first select answers to
pending notes, otherwise the newest eligible answer. A final reply is remembered at the same
link-queue handoff boundary as iOS; write-without-response does not prove device receipt.

## Upstream differential checks

`tests/fixtures/passport/cases.json` contains synthetic inputs. The Swift exporter in
`tests/reference/PassportVectors.swift` executes the **unmodified pinned PocketCore sources** to
produce `upstream-vectors.json`; it does not reproduce Android's implementation as an oracle.
The generator rejects changed reference sources and pins swift-markdown to 0.9.0.

```sh
# Only needed to regenerate the committed oracle; requires macOS/Swift and the iOS checkout.
POCKET_IOS_DIR=../pocket-ios scripts/generate-passport-vectors.sh

# Normal deterministic checks use the committed oracle, without Swift or BLE.
./gradlew :core:check

# Optional: run only the protocol on one explicitly selected Android runtime.
ANDROID_SERIAL=<serial> scripts/test-passport-protocol.sh
```

The 2026-11-15 result is **60 upstream scenarios / 1,050 comparisons**, plus **1,093 Unicode 16
grapheme conformance cases**, passing on both the JVM and the physical E2's ART runtime. These
cover wire/MTU/quiet, malformed/interleaved/oversize PDUs, press sequencing, Markdown and Unicode
fitting, WORK/waits, replay/dedup/persistence and recognition outcomes. The host suite totals
2,484 assertions including other retained contracts. Release build and source/localization
checks pass.

The Android check copies a small DEX runner and fixtures to a temporary shell directory and
removes them afterward. It does not install or open an app, connect BLE, change app data, record,
play sound or contact the room. Private evidence stays under ignored `.build/passport-device/`.

The E2 check found that its old regex engine split a family ZWJ emoji. Protocol text fitting now
uses pinned Unicode 16 UAX #29 rules instead of OEM regex/ICU behavior. Generated ranges and the
official conformance fixture are reproducible with `scripts/generate-grapheme-data.py`, which
checks official source SHA-256 values. See the bundled Unicode licence in
`third_party/Unicode-LICENSE.txt`.

## Physical acceptance still open

No physical Passport was paired or disconnected for this work; E2Studio's connection is intact.
Protocol and ART results establish the tested protocol behavior, not radio or acoustic acceptance.
When an accessory is available for this app, use Settings → Passport, grant Bluetooth permissions
(location for scanning on API 26–30), and confirm matching numbers on both devices through the
system dialog. The app never confirms pairing automatically. Forgetting in the app stops reconnect;
removing an obsolete system bond remains an explicit system-settings action.

The remaining short acceptance is real pairing/CCCD and reconnect, one physical press through
recognition and answer display/alert, and a background KEEPALIVE wake. Check modern Android
connected-device restrictions on an actual target when needed. Long-range, OEM/background and
firmware-log/export acceptance are not established. No broad device matrix or overnight task is
required to preserve this protocol work.
