# 1:1 migration acceptance matrix

The long-term goal is **1:1 functional parity** with `openduo/pocket-ios`, initially commit
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. Milestones are checkpoints, not reduced scope.
A prepared reply, placeholder, compiled page or unit contract is not end-to-end verification.

Evidence at the 2026-11-14 local checkpoint: **98 host contract assertions, Go vet/test, Android APK
build and 57 real E2 device assertions passed**. The device checks cover SQLite recovery/isolation,
AndroidKeyStore binding, Opus JNI, real array capture/release, muted AudioTrack watermarks,
Camera2→H.264 and teardown, QR camera frames/release, foreground camera resume, encoder restart with
swapped dimensions and continuous sequence, and bounded RGB viewfinder frames. The same suite checks
the actual narrow viewport, a visible Sogou IME, composer avoidance, retained draft, prepared turns
through ChannelClient/log/outbox, real image upload/download/sharing, read-aloud cancellation,
try-mode cleanup/room restoration, and NotificationManager deduplication. Dark/light conversation
and scoped 1.35 font-scale screenshots were inspected. The actual camera scene was almost black;
recognisable-scene aiming and physical orientation have not been accepted.

No real room is paired yet: the embedded node is at Tailscale login, so room API/audio, accessory
and session integration remain unverified against the user's services. Prepared mode exercises
the real client boundary, reducers and hardware; its content is still prepared. Test counts do
not represent a completion percentage, and none of the rows below is a claim of complete parity.

| Upstream area | Android owner and current implementation | Remaining parity / acceptance work |
| --- | --- | --- |
| Tailscale / pairing | `GoTransport`, `Endpoint`, `SecretStore`, editable pasted/opened/shared links, local QR decode and scan sheet, asynchronous one-tap auth URL handoff | Real browser/login/pair/revoke/re-pair, actual displayed pairing QR into a room, network-path and BLE-wake ping acceptance |
| Live/cache/outbox | API v2 `/live`, SQLite cursor transaction, replay, stable ids, persisted retry surface/draft/chips, generation guards; after-row `brain_unreachable` resend preserves the failed logged row | Real offline/reconnect/history and send lifecycle; process death during admission/replay; historical identity and failure acceptance |
| Thread and tools | Keyed bubbles, day/time separators, heard fold and speaker labels, spoken/partly-spoken metadata, live room notes, Markdown, copy/share/reply/read, tool archive and jump-to-latest | Full accessibility and more pages/font sizes; real live/log interruptions and tool identity edge cases |
| Composer | Text/voice modes, saved draft, raw-touch hold/cancel/resume, file/photo/share import, thumbnails, EXIF, image paste/keyboard input, zoom and uploaded chips | Real upload limit/error/progress acceptance; clipboard/share and camera-picker return; full attachment removal/retry lifecycle |
| Voice notes | Exclusive E2/native/headset input, real RMS, Opus framing, outbox admission and idempotent retries | Real recognition/empty/failed/delivery outcomes, held-note interruptions, external mic/SCO routing, background upload lifetime |
| Ambient | `AmbientInput` handles capture-before-hello, meta/seat/takeover, microphone-on semantics and overlapping presses; mute/hush/surface, foreground service, hardware receipts, full read-only conversation, transcript/room notes and interruptions | Real room ASR/TTS and reconnect, actual focus/route changes and external headsets, background/process death and energy trials |
| Read aloud | Shared incremental speech transport, PacketStream, cancellable Go/local streaming, bounded PCM and cut/error distinctions; actual prepared Opus/AudioTrack path tested | Real synthesis/stop/restart, live-output overlap, failed synthesis and prolonged/cancelled-stream acceptance |
| Passport | BLE central scan/select/bond/CCCD/MTU/write queue, PDU/press/result logic, room-specific persistent reply memory, last-fragment GATT completion, remembered-accessory foreground lifetime and app-side logs | Real external pairing/press/reconnect/stale bond; background wake/recovery and result timing; firmware log/export protocol if available; do not take E2Studio's connection |
| Session viewer | Scope gate, watch/list/open/gaps/tool folds/Markdown/notify correlation/reconnect | Real sessions/notify scope, disconnect uncertainty and narrow-screen list/thread acceptance |
| Sight | `SightSession` owns validated asks/gates/restarts; Camera2/MediaCodec YUV, Annex-B/v1, keyframe/backpressure/numbering, rate/thermal handling, first-key status, rear colour viewfinder, actual lens availability and remembered lens, physical orientation sensing | Real server negotiation/backpressure/rate acceptance, recognisable-scene aiming and physical orientation; thermal and camera-busy recovery trials; fold/stand equivalents on relevant hardware |
| Location picker | Native draggable OSM tiles, search/system geocoder, current/approximate fix, editable coordinate insertion | Real map/search/offline/permission states on E2; approximate permission UX |
| Arrival place | Android passive-location dwell monitor, geocoded arrival ledger, percent-encoded place header and settlement, opt-in/permission settings, boot/package refresh | Real visits/geocoder/privacy/permission/process-death acceptance; verify Android monitoring reliability against iOS visit behaviour |
| Background answers / push | Local notifications, runtime/settings permission UI, room-specific live/log dedup, silent first cursor bootstrap, 15-minute JobService catch-up and explicit service lifetimes | Immediate server push equivalent to APNs/FCM is still absent; real background notifications/modern Android recovery and battery acceptance |
| Try mode | Disposable `TryChannel` behind the same HTTP/live/speech transport, isolated in-memory SQLite/outbox/cursor/draft, idempotent receipts, voice framing/cleaned/turn/rows, actual hashed files and downloads, prepared ambient/read-aloud audio, tools and scoped sessions/notify; Chinese/English scripts/resources | Full UI localization, exhaustive rapid entry/exit/cancel/process-death and no-real-traffic trials; prepared mode does not prove real recognition or synthesis |
| Settings and diagnostics | Audio/haptics/keep-awake/background/headset preferences, themes, route/config issues/arrivals/notifications, actual app/optional-tsnet diagnostic ZIP, Passport app events and licences | English UI localization, real settings/failure/route acceptance, actual firmware log protocol/export and modern Android permission coverage |
| Engineering / distribution | Separate namespace, arm64 native build, private signing, pinned licences/dependencies/assets, architecture checks, host/device probes and CI definition | Remote CI has not run; generic modern-Android acceptance, sustained stress/energy/lifecycle regressions, final full parity audit and release packaging |

Acceptance requires real service/device evidence where applicable, process death/relaunch and
failure/cancellation checks, no duplicate/lost sends, a single microphone owner, and all actions
reachable on the current ~259 dp viewport. Keep E2Studio, its data, default launcher and existing
BLE connection intact.
