# 1:1 migration acceptance matrix

The long-term goal is **1:1 functional parity** with `openduo/pocket-ios`, initially commit
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. Milestones are checkpoints, not reduced scope.
A prepared reply, placeholder, compiled page or unit contract is not end-to-end verification.

Scope update from the user on 2026-11-14: **external Passport Bluetooth integration is deferred**.
The E2 is itself the handheld device. Conversation and settings omit Passport entry points, while
the existing implementation is retained for later. External Passport hardware acceptance is outside
the current standalone-device release scope; the rest of the 1:1 goal remains.

Evidence at the 2026-11-14 local checkpoint: **213 host contract assertions, Go vet/test, Android APK
build and 283 real E2 device assertions passed**. The complete capture/camera/import/paired-try run
used the standalone-policy APK and verifies no Passport activity or BLE thread. An earlier full
attempt failed the first outage retry timing check at 345 ms; the unchanged standalone outage run
then passed all 39 assertions. A later complete run passed with temporary request-timestamp
instrumentation in that probe, which was removed because it did not establish the failure's cause.
The timing bounds were never loosened. The intermittent failure remains unlocated, so this is a
successful run rather than a claim of consistently reproducible acceptance.
The final standalone correction excludes remembered Passport waits from background connection
gates. That APK passed **21 live-recovery assertions**; the complete suite was not repeated after
this last correction.
After final installation and cold launch, the actual room initially remained offline/retrying.
Android reported validated Wi-Fi and a separate computer received HTTP 200 from the channel's
health endpoint. A later wake/UI inspection showed Online again without login, re-pairing or
network changes. This is recovery evidence, not a diagnosis; the cold-launch delay remains
unlocated and does not establish bounded connection startup.
A separate **two-assertion real-node login run**
decoded the rendered QR to the node's actual authorization URL. The device checks cover SQLite
recovery/isolation,
AndroidKeyStore binding, Opus JNI, real array capture/release, muted AudioTrack watermarks,
Camera2→H.264 and teardown, QR camera frames/release, foreground camera resume, encoder restart with
swapped dimensions and continuous sequence, and bounded RGB viewfinder frames. The same suite checks
the actual narrow viewport, a visible Sogou IME, composer avoidance, retained draft, prepared turns
through ChannelClient/log/outbox, actual image bytes over prepared upload/download plus Android sharing, read-aloud cancellation,
try-mode cleanup/room restoration, and NotificationManager deduplication. They also cover native
English switching/resources, literal Chinese user input, channel-name updates, native theme
recreation, recipient-specific session drafts/send acknowledgement and the notification composer
above the real keyboard. An independent native camera owner keeps capturing while Sight waits;
releasing it resumes a key frame. Unsupported H.264 holds without repeated opens; a replacement
ask resumes, and switching off while occupied cancels recovery. Transient-failure budgets are host
contracts, not an induced physical encoder fault. Dark/light conversation, English settings/session
views and scoped 1.35 font-scale screenshots were inspected. The available camera scene was too dark to identify;
recognisable-scene aiming and physical orientation have not been accepted.

The same final device run covers imported draft/receipt transactions (including an injected receipt
write failure), literal provider names/MIME, ordered multi-file URI deduplication, repeated share
recreation and page retention, focused editor updates and typing/appending interleavings, and
try-to-real isolation. It drives the actual system picker against a disposable provider in another
UID, releases its persisted grant, and takes/confirms a stock-camera photo through the granted output
URI. The returned JPEG decodes and its latest chip/removal target is visible in the narrow viewport.
An earlier run also exercised the first-use camera path with optional location access declined;
its original permission decision flags were restored. The current run verifies a granted permission
stage restored through recreation and ignores a late duplicate callback; an unanswered permission
dialog has not been recreated. It requests recreation while the real picker is open and verifies
Android's deferred replacement still admits its returned file. Native decoders verify full-resolution
media conversion, unchanged file selections and inline JPEG/PNG, oriented camera JPEG/GPS removal,
encoded-byte limits and an honestly undecodable HEIC fallback. A separate-UID provider supplies a
real stalled pipe and a stalled second-file open: native cancel stays above the real keyboard,
releases each source promptly, removes partial batches, preserves the draft, blocks replay and
allows the next import. Progress remains attached to the current file's index/name/bytes. Tests
remove their provider, APK and shell helper. Reopened SQLite now checks persisted original import
requests, source ordering/policy and room identity, plus cancellation receipts surviving source cleanup.

A separate run passed **27 host-controlled and 12 native-phase process-death assertions**. It selects
an actual system document from another UID, observes its
first 8192 bytes and withheld EOF, then force-stops Pocket E2 outside instrumentation. Termination
closes the descriptor while the provider remains alive. Cold launch uses a new PID, resumes the
persisted request and admits the complete 16384-byte file alongside the existing draft/quote. A
second force-stop/cold launch keeps one chip without another provider open. Native checks verify exact
bytes, receipt, removed partial directory, released document grant and empty outbox. Setup/cleanup
restore the actual composer and preferences, and remove the QA provider/APK. This is actual force-stop
and cold-launch evidence; low-memory task recreation and expired temporary grants remain unaccepted.
See `imports.md`.

The current native audio suite also ports the upstream output mute/interrupt/read policy into pure
`AmbientOutput`. Native capture remains active in a disposable room while protocol stimuli verify
immediate muted-sample watermarks, mixed queued/skipped durations, filler press interruption, dropped
speech tails, local read/live priority, hush and mute reset. A state-queue delay deliberately holds
real hardware receipts until after stop/id reuse; playback generations reject them. Private
per-declaration playback keys also isolate repeated wire ids without clearing earlier PCM. Competing
Android focus requests verify actual capture release, persistent ambient intent, `audio=0` while
interrupted, foreground/seat-metadata gating and renewed `audio=1` hello/uplink after gain. Short
20 ms hardware blocks play after twelve immediate stops and several idle intervals. These checks
also verify a short read retaining focus across an open HTTP body, then clearing its reading state
with an explicit partial-playback error when a delayed injected failure follows hardware drain.
They use prepared protocol stimuli and real audio hardware; they are not actual microphone recognition
or ambient server acceptance. See `audio.md`.

A separate **21-assertion live-recovery run** verified text/audio write failure while the transport
still reports open, stale close callbacks, a saturated 128-entry writer queue and real held-note
capture during ambient exit. It also delays a final unmute across a background callback: capture and
the state executor release promptly, the unmute precedes close, and an idle background room does not
redial. These prepared faults remain in the disposable room. Production now retires the whole failed
connection before retry and retains the room-microphone restoration through asynchronous teardown.

A separate **39-assertion sustained-outage run** verifies upstream 1/2/4/8/16/30-second backoff with
actual retry deadlines and native capture. Short failures preserve the same capture lease through
the first 15 seconds; the next failed attempt at about 31 seconds releases it. A prepared background
recovery keeps capture paused until foreground, then sends one fresh hello. A separately budget-primed
overlap preserves a local held note during link pause, cancels cleanly and retries immediately on a
new press. Prepared 1008 refusal survives ready/seat metadata, focus events and foreground without
invalidating credentials; an actual compact-screen reopen click clears it. An independent power
control exits a refused room and releases the native mic while keeping reopen reachable. Its
background/focus stimuli are injected on the state executor, not physical network changes, a real
background transition or an actual call. The full suite retains the separate real competing-focus checks.

The same device run verifies voice lifecycle ordering at the ordinary controller/client/repository
boundary: cleaned/failure/row before HTTP completion, frozen retry bytes after side-file changes,
discard during backoff and queued-send ordering. Two fault cases each consume the actual 25-second
budget: exhausted HTTP 503 stays failed without breaking the healthy socket; exhausted transport
failure stays queued, replaces a still-open live socket and resumes under the same key/body/surface.
Prepared empty/ASR/service outcomes check file/outbox cleanup or retained retryability. Actual E2
held capture checks cancellation, foreground-callback cancellation and native focus interruption,
then retries the retained real Opus into the disposable transport. Recognition outcomes in these
fault cases are prepared; no captured surrounding speech leaves the try room.

The real embedded node was authorized and paired on 2026-11-14. The actual Activity received the
room link and completed pairing; **Online**, historical rows, **Delivered** and the requested exact
`POCKET-E2-LIVE-OK` reply were observed for one marked text integration message. Pairing survived
in-place APK updates and the paired try/UI suite's app restarts. A final relaunch was **Online**
with the verified reply retained. A separate opt-in run passed **17 real-room assertions**: actual
scoped session lists and first-session rows; normal file import/upload, authenticated remote download
with exact-byte digest validation, and local draft removal; actual incremental read-aloud synthesis
through PacketStream, Opus and muted AudioTrack completion, followed by cancellation of a second
in-flight request and rejection of its late callbacks. The current server omits the optional upload
digest: production now derives it from the exact request bytes and verifies any supplied digest.
The test no longer injects download metadata. No room messages or session notifications were sent
by this run. An additional explicit real-voice run passed **15 assertions**. It synthesized one clearly
marked known-speech fixture through the actual service, sent it through the normal outbox/client
after the real Activity moved to the background, and observed the native foreground upload service.
Actual ASR recognized the marked speech, the voice row was delivered, and the brain supplied the
requested `语音链路验证成功` reply. The note retained its duration; its file/outbox/service settled;
a server-log query found exactly one copy. A separate **11-assertion cold restart** retained that
delivered row/duration/reply, confirmed one server-log copy, and reused the private app-files journal
without sending again. This is actual API recognition of synthetic known speech; the retained voice
duration is the advertised metadata, not an independent acoustic-duration measurement. New synthetic
fixtures normalize the actual service's 60 ms packets into native 20 ms packets before pacing/counting.

The actual ambient path passed a separate **18-assertion restart/recovery run**. A canonical marked
fixture, paced by real E2 capture with all surrounding microphone bytes replaced, produced actual ASR
and one matching human server-log row. The service supplied `环境模式连通成功` as a `reflex`, followed by
37 actual downlink packets and server playback `done` at 2220 ms from zero-volume AudioTrack output.
This accepts a service reflex; it does not establish a brain/tool turn. A local write fault closed the
actual socket, redialled the actual server and resumed a new capture seat/uplink. The private once-only
journal and retained actual output observations allowed verification after restart without replaying
the utterance. Independent actual state requests also verified mic-off during a native hold and
mic-on after ending ambient and cancelling the note, without a note upload. An earlier unnormalized
attempt produced no accepted ASR and is excluded. Acoustic
microphone-to-room recognition, physical network outages and prolonged background behaviour remain
unverified against the user's services.
External Passport is deferred. Prepared mode exercises the real client boundary, reducers
and hardware; its content is still prepared. Test counts do not represent a completion percentage, and none
of the rows below is a claim of complete parity.

| Upstream area | Android owner and current implementation | Remaining parity / acceptance work |
| --- | --- | --- |
| Tailscale / pairing | `GoTransport`, `Endpoint`, `SecretStore`, editable pasted/opened/shared links, local QR decode and scan sheet; native login QR/link/status sheet for devices without browsers, explicit interactive login/refresh preserving pending auth; actual node URL decoded from the visible E2 sheet in a dedicated two-assertion login run; real user authorization and link-to-pair succeeded | Revoke/re-pair, browser handoff on a browser-equipped device, actual displayed pairing QR into a room, physical network-path switching and BLE-wake ping acceptance. See `connection.md` |
| Live/cache/outbox | API v2 `/live`, SQLite cursor transaction, replay, stable ids, persisted retry surface/draft/chips, generation guards; common retirement for reader/writer/queue/HTTP transport failure; upstream redial delays and foreground/press reset; default-network path monitoring with tsnet rebind and live ping; still-open/stale-callback/queue-saturation and actual retry-deadline fault acceptance; after-row `brain_unreachable` resend preserves the failed logged row; real history, delivered text, exact reply and local-fault actual-server redial verified | Physical Wi-Fi/cellular switch and long-term path/energy acceptance; process death during admission/replay; historical identity edge cases |
| Thread and tools | Keyed bubbles, day/time separators, heard fold and speaker labels, spoken/partly-spoken metadata, live room notes, Markdown, copy/share/reply/read, tool archive and jump-to-latest | Full accessibility and more pages/font sizes; real live/log interruptions and tool identity edge cases |
| Composer | Text/voice modes, saved draft and acknowledged native edits, serialized share/map append without losing interleaved typing, raw-touch hold/cancel/resume, thumbnails, EXIF, image paste/keyboard input, zoom and uploaded chips; viewport-bounded names, overflow and newly-added chip reveal; real file upload/download/local removal accepted | Real upload limit/error/progress and retry acceptance; full clipboard lifecycle; more dense composer/font/keyboard combinations |
| File/photo/share handoff | `ImportCoordinator`, pure `ImportInbox` and transactional draft/receipt admission; durable original `ImportRequest` before provider queueing and source-release cleanup after admission; ordered multi-file URI dedup, ClipData text/links, temporary/persisted read-grant release; launch-scoped `ImportRoute` and restored external-operation stages; additional native-decodable media→JPEG, file-picker byte preservation and full-resolution oriented camera JPEG; actual byte progress, send guard and durable cancellation with provider signals/polling; first-pair draft transfer and try isolation; real separate-UID picker, force-stop/cold-start recovery of an unfinished provider copy and second-restart dedup, stock camera capture/confirmation; actual path-only server upload/download accepted with a production-derived digest | Low-memory task recreation, expired temporary grants, unanswered permission dialog restoration, revoked/noncooperative providers, low storage and large batches; valid HEIC decoding on E2; modern Android and real-room limit/failure/retry acceptance. See `imports.md` |
| Voice notes | Exclusive E2/native/headset input, real RMS, Opus framing, synced packet-file admission, frozen body and original key/surface on retries, monotonic 25-second budget, live-result precedence and cancellation; actual E2 hold cancellation/background callback/focus interruption and retained-packet retry under prepared outcomes; one marked synthetic fixture through actual ASR/delivery/reply with the real Activity backgrounded and native upload-service cleanup | Acoustic microphone-to-room recognition; actual empty/ASR/service failure outcomes and offline paths; external mic/SCO routing, low storage, process death during recording/upload and prolonged background lifetime. Prepared failures/native focus requests and synthetic API recognition are distinct evidence; see `audio.md` |
| Ambient | `AmbientInput` handles capture-before-hello, meta/seat/takeover, microphone-on semantics, overlapping presses and final mic restoration before asynchronous exit/focus teardown; pure `AmbientOutput` owns immediate muted-block clocks, fillers/presses, read priority and stop/hush distinctions; generation-scoped hardware receipts, mute-session reset, native focus pause/gain and `audio=0/1` reconnect; upstream short-outage capture retention, sustained-outage release and foreground-only resume with actual deadlines/native mic under prepared faults, persistent 1008 refusal and explicit reopen; foreground service, full read-only conversation, transcript/room notes; marked synthetic actual ASR/reflex/TTS, completed zero-volume hardware timeline and local-fault actual-server redial | Acoustic recognition and ambient brain/tool turns; physical Wi-Fi/cellular switching and long-term path/energy acceptance; actual calls/route changes and external headsets, background/process death and energy trials. Native competing focus requests are accepted; injected lifecycle recovery does not establish physical background/call acceptance |
| Read aloud | Shared incremental speech transport, PacketStream, cancellable Go/local streaming, bounded PCM and cut/error distinctions; prepared and real synthesis decoded/played to hardware watermarks with muted output; repeated real request/stop ignores late callbacks; native prepared local-read/live overlap accepted; 20 ms startup/idle restart and in-flight block accounting | Actual server live-output overlap, external routing, failed synthesis and prolonged-stream acceptance |
| Passport (deferred by user) | Existing BLE central, press/reply protocol and persistent memory retained; normal conversation/settings entries removed; standalone policy currently disables automatic resume, press routing and accessory background work | Outside current acceptance scope. Resume external pairing/reconnect, wake/result timing and firmware logs only if requested; preserve E2Studio's connection |
| Session viewer | Scope gate, watch/list/open/gaps/Markdown/reconnect; source/activity, five-row group folds, room return, failed tool summaries; strict atomic wire validation and cached immutable snapshots; notify outcomes retain recipient/id/body and separate drafts through theme recreation; actual scoped session list and first-session row window accepted | Actual notify delivery and disconnect uncertainty, large real lists/fold accessibility, live cross-session identity and failure acceptance; retained drafts are not process-death storage |
| Sight | `SightSession` owns validated asks/gates/restarts; Camera2/MediaCodec YUV, Annex-B/v1, keyframe/backpressure/numbering, rate/thermal handling, first-key status, rear colour viewfinder, actual lens availability and remembered lens, physical orientation; availability observation and finite `SightRecovery` without clearing opt-in | Real server negotiation/backpressure/rate acceptance, recognisable-scene aiming and physical orientation; external-app eviction, actual transient encoder faults and thermal trials; fold/stand equivalents on relevant hardware |
| Location picker | Native draggable OSM tiles, search/system geocoder, current/approximate fix, editable coordinate insertion | Real map/search/offline/permission states on E2; approximate permission UX |
| Arrival place | Android passive-location dwell monitor, geocoded arrival ledger, percent-encoded place header and settlement, opt-in/permission settings, boot/package refresh | Real visits/geocoder/privacy/permission/process-death acceptance; verify Android monitoring reliability against iOS visit behaviour |
| Background answers / push | Local notifications, runtime/settings permission UI, room-specific live/log dedup, silent first cursor bootstrap, 15-minute JobService catch-up and explicit service lifetimes | Immediate server push equivalent to APNs/FCM is absent. No upstream push AES-GCM format, HMAC answer/thread identifiers, PushLedger or fixed-vector validation has been ported. Android push key/token registration, relay and receiver are also absent. Local credential AES-GCM and arrival PlaceLedger serve separate purposes. Real background notifications/modern Android recovery and battery acceptance remain |
| Try mode | Disposable `TryChannel` behind the same HTTP/live/speech transport, isolated in-memory SQLite/outbox/cursor/draft, idempotent receipts, voice framing/cleaned/turn/rows, actual hashed files and downloads, prepared ambient/read-aloud audio, tools and scoped sessions/notify; Chinese/English scripts/resources/UI and isolated language restart | Exhaustive rapid entry/exit/cancel/process-death and no-real-traffic trials; prepared mode does not prove real recognition or synthesis |
| Settings and diagnostics | Audio/haptics/keep-awake/background/headset preferences, themes, route/config issues/arrivals/notifications, actual app/optional-tsnet diagnostic ZIP, Passport app events and licences; scoped Chinese/English UI/resources/error/status catalog with build-time coverage checks | Full accessibility, real settings/failure/route acceptance, actual firmware log protocol/export and modern Android permission coverage |
| Engineering / distribution | Separate namespace, arm64 native build, private signing, pinned licences/dependencies/assets, architecture checks, host/device probes and CI definition | Remote CI has not run; generic modern-Android acceptance, sustained stress/energy/lifecycle regressions, final full parity audit and release packaging |

Acceptance requires real service/device evidence where applicable, process death/relaunch and
failure/cancellation checks, no duplicate/lost sends, a single microphone owner, and all actions
reachable on the current ~259 dp viewport. Keep E2Studio, its data, default launcher and existing
BLE connection intact.
