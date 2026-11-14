# Migration evidence and future 1:1 comparison

The user changed the milestone on 2026-11-14: finish the known P0/P1 implementation, improve
maintainability and support general Android windows. **Complete 1:1 parity is not the current
release requirement.** Keep `openduo/pocket-ios`, initially commit
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`, as the future comparison. A prepared reply,
placeholder, compiled page or unit contract is not end-to-end verification.

Read the Chinese [current status](status.md) and [expanded acceptance](acceptance.md) first.
The current UI layer has a complete 22-case API 35 window/font matrix (9,616 checks), actual display
rotation (20), freeform (8) and system split (12), actual TalkBack 15.0 traversal (final runner 241)
and legacy Google source Switch Access 6.1.1 scan/activation (11). The final physical E2
capture/camera/paired-Try regression passes 342 checks and the accessibility target passes 27.
Actual API 35 public-landmark Geocoder search/reverse also passes. The E2 probe confirms no
framework Geocoder and no enabled provider (NOT_RUN); E2 fixes/visits do not follow from the API 35
result. The real E2 also passes a 900-second idle background/trim-request/foreground Online trial,
separate from natural LMK or sustained capture. [UI architecture](ui.md) and [accessibility](accessibility.md) describe reproducible
runners and coverage limits. Actual natural speech, Sight negotiation, notify/visit, identity,
cross-network/external-audio and sustained-lifetime acceptance remain open.

## Scope and historical checkpoints

The chronological records below retain the conditions and limitations of each earlier checkpoint;
their "not repeated" or "open" statements describe that checkpoint. The final comparison table
and current status documents reflect the later UI/service runs. Separate APK digests remain in the
ignored evidence; do not treat different reports as a single byte-identical APK acceptance.

Scope update from the user on 2026-11-14: **external Passport Bluetooth integration is deferred**.
The E2 is itself the handheld device. Conversation and settings omit Passport entry points, while
the existing implementation is retained for later. External Passport hardware acceptance is outside
the current standalone-device release scope. Other future parity gaps remain recorded below.

The user also deferred **immediate Android push** on 2026-11-14, alongside Passport. Push
registration, relay, receiver and the upstream push crypto/identifiers/ledger, including fixed-vector
validation, are outside the current implementation scope until explicitly resumed. Existing local
notifications and periodic catch-up remain in place. Both deferred areas remain recorded as gaps
against the long-term 1:1 goal.

The user resumed **P0 and P1** on 2026-11-14: connection reliability, natural voice and full ambient
turns first; then Sight, session notifications, location/arrival, compact-screen accessibility and
failure recovery. Immediate push and Passport alone remain deferred. The resumed connection work
reproduced diagnostic HTTP blocking the live dial and an uncancelled retired handshake, separated
live dialing/ping onto a bounded connector, added foreground rebind/idle cleanup, and protected
retry deadlines from passive connection checks. Actual cold launch, a physical Wi-Fi outage and
actual Activity background/return passed nine read-only room checks, with Online observed at
4058/4214/314 ms respectively. This does not diagnose the original cold-start incident or establish
prolonged standby acceptance. A separate 21-assertion actual ambient run accepted one marked
synthetic ASR → brain → Bash tool/completion → reply → zero-volume playback turn, followed by actual
local-fault redial and room-microphone restoration. Natural speech remains separate. See
`connection.md` and `audio.md` for evidence boundaries and retained once-only journals.

The resumed complete capture/camera/import/paired-try run passed **295 device assertions**. The
final location-only corrections then passed **seven targeted assertions**; the complete suite was
not repeated after these last corrections. Actual coordinate insertion is reachable in the narrow
viewport, preserves the original draft/quote/outbox, and remains usable when location is disabled.
The actual E2 app query returned `passive,gps` with no enabled providers. This is provider/state
evidence, not proof of missing or functioning GPS hardware. Missing-provider handling, bounded
lookup work, stale-result rejection and search/location timeouts are implemented; broader live
geocoder, map tile, granted/denied/approximate permission and visit acceptance remains open.
Shared icon controls have 48 dp minimum targets and an accessibility button role; a full screen-reader
audit remains open. An opt-in actual own-room session notification probe returned **NOT_RUN** because
the paired room was not an eligible notification target; no notification was sent. Another real
recipient needs an explicit user-selected test target. This does not revoke existing session access
or require re-pairing.

A subsequent **29-assertion Sight-only run** passed on the updated APK. Native Camera2/H.264
stops on a prepared server refusal without emitting late video or a reciprocal decline; a fresh ask
resumes a key frame with continuous numbering. Separate prepared control frames on the actual
compact UI verify that refusal, obsolete-channel and invalid-parameter explanations remain visible
without camera controls. Heard/held-note/thermal updates and the expanded transcript no longer
hide those explanations. Unknown server reasons stay literal; ordinary stop and socket replacement
clear them. Both room/transcript screenshots were inspected. This uses prepared server control
frames and real native camera/UI; it does not establish actual server Sight negotiation, recognisable
scenes or physical rotation. The full device suite was not repeated for this camera-only change.

A subsequent arrival update passed **215 host assertions**, the Android build and **25 targeted
device assertions** on the final APK. Prepared fixes/permission/geocoder outcomes exercise native
distance, monotonic clock and isolated preference storage, rejection of duplicate/old/invalid fixes,
reboot/opt-in boundaries and late results after off/on, missing permission or work expiry. Pending
and accepted places are shared safely between monitor instances. Actual Android ordered broadcasts
finish within budget while the real arrival worker is deliberately blocked, and an overflow
broadcast finishes promptly. The actual disabled-provider state produces a paused explanation
under a prepared granted-permission state. No system location setting, actual stored place or room
message was changed. Settings distinguish permission/source/geocoder waits and expose the relevant
native settings. This does not accept a real physical visit, actual geocoder or background relaunch;
the full device suite was not repeated for this arrival update. See `location.md`.

A following P0 background-upload correction passed **15 targeted assertions** on the fixed APK,
after the same probe reproduced the missing foreground service on the preceding APK. The actual
Activity remains stopped while prepared transport errors exhaust the real 25-second budget;
automatic reconnection now reacquires Android's data-sync foreground service for the original
voice id/body/surface. A prepared service-start refusal keeps the note queued without HTTP until
the real Activity returns; passive pumps do not repeatedly ask for a denied start. Native service
cleanup and restoration of the actual paired room/online/composer state pass. The silent Opus
fixture, separate SQLite repository and transport without network delegation cannot send a real
message or capture microphone audio. This accepts Activity/service behaviour with prepared
HTTP/live outcomes; modern Android restrictions, process death and natural ASR remain separate.
Host/architecture/localisation checks and the APK build passed; the full suite was not repeated.

The subsequent storage correction passed **24 native phase assertions** and **10 host-controlled
assertions** across two actual force-stop/cold-launch boundaries. A native enclosing transaction
contains completed production row/outbox/cursor admission before its actual commit: termination
rolls it back while exact voice/attachment bytes, id, original surface and duration survive. A
committed admission, discard and clear then retain cleanup under an introduced directory-permission
failure. After permissions are restored and the still-live process is terminated, ordinary startup
drains the path-only cleanup receipts, preserving one local delivered row/cursor/duration and an
empty fixture outbox. Cleanup records share the removal transaction and survive clearing room
metadata. The actual paired room/composer are preserved, no microphone is acquired and no fixture
message is sent. This is actual native storage/process evidence with prepared rows in separate
namespaces, not an interrupted actual-server replay or natural recording/upload. See `storage.md`.

That APK also passed **17 expanded native background-upload assertions** after fixing service
publication ordering. An earlier queued idle notification could make a newly started service stop
itself before current upload intent was published. A 750 ms state-owner hold inside native startup
reproduced that failure on the preceding ordering; publishing intent before requesting Android
startup preserves the actual service through that same window. The expanded run also accepts
background retry, prepared service-start denial and actual foreground recovery, with the new
repository's normal file cleanup. One earlier foreground-recovery assertion failed intermittently
without component diagnostics; the reproduced race is corrected, but its exact earlier trigger
is not retroactively established. The storage crash checks used this same repository code before
the final service-only ordering correction. The complete device suite was not repeated.

A subsequent history correction passed **226 host assertions** and **54 targeted E2 assertions**.
The previous APK reproduced a correlated attachment answer failing to end its working bubble.
Wire `message_ids` now survive attachment mapping, immutable replacement and SQLite reopen;
live/day/older/periodic admission all settle correlated turns. `ready` also checks two cached log
days beyond the UI row limit. Provisional/log reconciliation preserves tool traces and distinct
same-turn answers through all six orders and duplicate batches. Another preceding-build check
reproduced a history loading indicator surviving connection retirement; loading now belongs to its
connection generation, and old empty/failure responses cannot affect the replacement or continue
scanning dates. Actual Activity/background NotificationManager checks retain one notice across
attachment catch-up and keep first-cursor history silent. A compact attachment/completion screenshot
was inspected, and the real room/composer/Online state were restored without a real send. Service
outcomes and connection retirement are prepared; old cache ids already lost need replay, and
actual-server interruption/physical handover remain separate. A broader no-capture/no-camera
paired-try run passed **163 device assertions** on the same APK after an initial first-reply log
assertion failure without row-state diagnostics. Diagnostic output was added, with no condition or
deadline changes; the exact earlier cause remains unlocated. This is a successful regression run,
not consistently reproducible full-suite acceptance. The full capture/camera/import run was not
repeated. See `history.md`.

A following P0 sender correction reproduced an independent blocked-send defect on the preceding
APK: three held ordinary HTTP workers prevented a prepared message from reaching HTTP despite an
Online live connection. A dedicated one-worker/one-waiting-slot sender now separates body
preparation and HTTP retries from those workers. A pre-registered native request lifetime cancels
node readiness, headers and body reads when the send retires, rejects queued late starts and keeps
one deadline across readiness/HTTP. The fixed APK passed **21 live-recovery**, **43 voice-upload**,
**17 native background-upload** and **eight actual-room read-only HTTP assertions**, alongside
**234 host assertions**, Go vet/race tests and architecture/localisation checks. Native SQLite
failure injection retains the queued note and exact bytes without starting HTTP or leaking a
request registration; foreground recovery resumes its original identity. Prepared cancellation and
queue cases exercise discard, bounded work and the next note without real sends. The actual-room
checks use only authenticated GETs and preserve pairing/Online/composer. A separate real native
989-packet audio-burst measurement completed in 151 ms and did not reproduce the earlier first-reply
timeout. Neither result establishes that earlier failure's cause. See `connection.md` and `audio.md`.
The complete capture/camera/import/paired-try regression then passed **311 device assertions** on
the same APK. It includes the expanded send cancellation/storage-fault cases, actual capture and
camera owners, system provider/camera handoff, and compact keyboard/language/theme paths. The
keyboard and 1.35-font conversation screenshots were inspected. The first-reply assertion passed
in this run; its earlier intermittent failure remains unlocated, so this is a successful full run,
not proof of consistently reproducible acceptance. Prepared room outcomes remain distinct from
actual-service natural recognition, audible playback, Sight and visit acceptance.

The next P0/P1 correction passed **33 native access-recovery assertions**. Current room rejection,
node login/approval loss and explicit logout retire the outbox request, clear socket work and
release capture/playback/upload intent. Room rejection persists Needs Pairing; node authorization
preserves the distinct room credential. Queued sends retain exact bytes/id/surface and resume with
replacement access. Late callbacks cannot resume rejected access, and superseded queued native
login/logout commands are fenced and serialized. A native SQLite pause-write failure still
releases resources; reconnection first recovers interrupted state durably. Prepared try-mode
unauthorized responses cannot invalidate the real pairing or spin resends. The test uses isolated
preferences/SQLite, actual AndroidKeyStore/service and no-network fixtures. The public logout is
superseded before its held worker is released; the actual node is never logged out. One preceding
run stopped at the actual-room idle precondition without component diagnostics; the original
trigger remains unlocated. Broader authenticated HTTP caller/revocation/logout parity remains open.

The same work reproduced Android's pending-foreground-start crash with the main owner held while
upload intent was cancelled. External stop is now replaced by published idle intent; the service
promotes before stopping and uses its acknowledged start id to avoid cancelling newer undelivered
starts. The 33-assertion probe waits past the native startup deadline for that same cancellation
window. The updated APK also passes **17 background-upload assertions** and the complete
capture/camera/import/paired-try regression's **336 device assertions**. Access/HTTP faults are
prepared; natural microphone recognition, audible output and modern Android restrictions remain
separate. See `connection.md` and `audio.md`.

Primary conversation/import/sheet controls now use actual 48 dp bounds instead of explicit 44 dp
constraints. Shared actions expose a button role, bottom navigation exposes selection, and the
hold control supports keyboard/accessibility start/send plus named cancellation. Permission
results belong to an activation generation and cannot start capture after replacement or detach.
The targeted native run passes **27 assertions**, including actual keyboard/action APIs, E2 capture
and prepared local voice delivery/cancellation without a real send. Permission callbacks are
prepared, not system-dialog acceptance. It is included in the broad 336-assertion run; a later
test-only playback cleanup adjustment retained the same APK and passed all 27 targeted assertions.
Actual compact keyboard and 1.35-font screenshots were inspected. **234 host assertions**,
architecture/localisation checks and APK build pass. **Eight actual-room read-only HTTP checks**
also pass, preserving actual pairing/Online/composer. Full screen-reader, natural acoustic, real
Sight/visit/session-notify and broader authorization acceptance remain open. See `accessibility.md`.

A subsequent P0 correction unifies ordinary HTTP, outbox and speech access in pure-Java
`RoomAccess`. It binds origin/token/room/namespace, registers ordinary work at dispatch and
retains the sender's one registration across retries. Exact authenticated 401 unauthorized closes
the generation immediately; anonymous health/pairing, other 401/403, Try responses and retired
credentials cannot revoke the current pairing. The state owner rechecks generation ownership for
history, chips, downloaded files, speech and catch-up. Settled/discarded individual sends cannot
reject still-current credentials with a late response. Node login/logout and room/Try replacement
cancel active requests and prevent queued HTTP dispatch. Background completion runs once on
success, rejection or cancellation; local originals and cached files remain readable offline.

The expanded isolated access run passes **77 native assertions**, including caller-by-caller
rejection, three blocked HTTP workers with queued catch-up/history, old-token success/401,
already-returned upload admission, discarded-send rejection, offline cache/original access and
the preceding native service/storage faults. **260 host assertions**, Go vet/race checks and the
APK build pass. Android speech now uses the shared native request registry, with in-flight and
late-start cancellation covered by local Go server tests; the old upstream stream API remains
available. Prepared access outcomes do not establish real token revocation or a fresh node login.

The final APK passes **336 complete capture/camera/import/paired-try assertions**, **17 native
background-upload assertions**, **eight actual-room scoped HTTP assertions** and **17 actual-room
session/file/speech assertions**. The latter uses real authenticated download/digest validation,
real synthesis and zero-volume AudioTrack completion/cancellation; it sends no new chat message
or session notification. Keyboard and 1.35-font screenshots were inspected. One earlier complete
run stopped at the sustained-outage mic-release check without component diagnostics. The probe
now waits for native lease release and the asynchronous controller callback within its original
deadline. A separate 41-assertion outage run and two later full runs pass, but did not observe the
intermediate callback state; the original trigger remains unlocated. See `connection.md` for the
exact observation and remaining acoustic, revocation, real Sight/visit/notify and standby limits.

The next P0/P1 correction reproduces a held recording losing its visible owner when SQLite rejects
its initial admission after file close. A pure-Java `VoiceInbox` now persists identity/room before
capture and bridges file completion to transactional row/outbox/receipt admission. Failed storage
retains one visible failed note, repeated retry starts no HTTP until admission succeeds, and later
retry keeps the same key and captured bytes. An unfinished hold after process death recovers only
its readable complete-frame prefix as failed for explicit retry. A committed handoff is acknowledged
without replay or state downgrade; durable cancellation prevents restart from reviving the note.
Current capture and other-room/Try recordings are not reassigned to the active room.

The isolated storage run passed **14 native assertions** with actual E2 hold capture and an actual
SQLite abort trigger. It checks continued storage failure, exact prepared-upload bytes, receipt
replay/discard, readable outgoing voice labels and a fully visible 48 dp native retry button on the
compact screen. Separate real force-stop/cold-launch checks passed **23 native assertions** across
five phases and **15 host-controlled assertions**, reaching live recording and committed-handoff
checkpoints before termination and verifying no revival after a third restart. Pairing, composer
and idle microphone survive restoration. **275 host assertions**, architecture/localisation checks
and the Android build pass. The first crash attempt reached both checkpoints but stopped at a
post-instrumentation PID assumption; the harness now launches the Activity before the next crash
and closes the prior transport during restoration. The subsequent complete run passes. These
network sinks are prepared, and no surrounding microphone recording is uploaded to the real room.
Unreadable files, unflushed tails, full physical storage, power loss, legacy orphan files, low-memory
termination and actual-server recording/upload interruption remain distinct limits. See `storage.md`
and `audio.md` for ownership and reproduction; natural acoustics remain unaccepted.
The same production APK then passed **336 complete capture/camera/import/paired-try assertions**.
The actual compact keyboard and 1.35-font conversation screenshots were inspected. The new
voice-storage and process-death probes run separately through the explicit flags in `storage.md`;
their assertion counts are not included in the complete suite's 336.
Final regressions pass **77 isolated access-recovery**, **18 native background-upload** and **eight
actual-room read-only HTTP assertions** on this same production APK. The first background run
failed its foreground-state check while id/body/service remained correct. Actual power and system
logs show the configured 60-second screen timeout before that return. The harness now explicitly
checks screen-off, wakes/unlocks before return and observes native focus/foreground within the
original five-second deadline. The subsequent run passes without production or system-setting
changes. This identifies this run's missing wake; the earlier background failures remain separately
unlocated. These regressions send no new real message. See `audio.md`.

The next P0/P1 correction ports upstream `UpgradeNotice` as an Android-independent typed state.
Messages 410 `update_required` identifies an app update, messages 404 identifies a channel upgrade,
and absent/old live API identifies a channel upgrade. A compatible ready clears only the channel
requirement; app update takes priority. Clearing ordinary errors cannot bypass the outbox gate.
Failed and queued messages retain their original identity/body/surface; the explanation and fresh
live recheck preserve the composer. Current-room generation checks exclude stale ready frames and
discarded-send responses, and Try/room replacement clears transient compatibility state.

The preceding APK reproduced a messages 404 without upgrade guidance. The fixed native probe
passes **26 isolated assertions** through the real controller, SQLite, silent JNI Opus and compact
UI, including exact text retry, retained voice bytes, unrelated 410, old/new ready, app-update
priority, late discarded responses, Try-to-real isolation and visible 48 dp native sheet actions.
**300 host assertions** include the pinned upstream status/API cases plus state priority and strict
API types; architecture/localisation/build checks pass. All version and message outcomes are
prepared inside Try. The actual service is not downgraded or made to require an update, no new
real message is sent, and Android has no public update handoff/automatic installer. Those limits
remain explicit; see `connection.md` for the exact state rules and reproduction command.
An added E2 capture probe then reproduced the new recheck omitting old-socket microphone restoration
during an ambient/hold overlap. Recheck now uses the existing bounded final-control path before
close, and the native run passes **31 live-recovery assertions**, including restore-before-close,
fresh held-note mute and retention of the same actual capture object/unfinished note. The **26
upgrade assertions** also pass on this correction. Wire/version outcomes remain prepared in Try;
neither run uploads surrounding speech to the real service or accepts natural acoustics.
The final APK then passed **338 complete capture/camera/import/paired-try assertions**, including
the two new native recheck-overlap checks. Keyboard and 1.35-font compact screenshots were inspected.
The separate upgrade probe is not included in the complete-suite count. The earlier complete run
passed 336 before adding/correcting that new microphone recheck boundary; the final result covers
the corrected production path.
The expanded access regression passes **78 native assertions**, and **eight actual-room read-only
HTTP assertions** pass on the same production APK. The first post-suite access run reached its last
fixture checkpoint but failed the combined real-room restore assertion while the display was
Asleep after the existing 60-second timeout. It lacked component diagnostics. The harness now
explicitly exercises actual screen-off, reports Offline/background/asleep before return and
wakes/unlocks/reopens before its original 35-second Online wait. Its restored settings, pairing,
draft, quote, chip count, empty outbox and unchanged compatibility state pass; failure assertions
cannot prevent those settings from being restored. This corrects the test's return flow without
changing production code or system settings, and does not retroactively identify every failed
term in the first combined assertion. No actual node logout/revocation or real message occurs.

Earlier standalone-policy evidence at the 2026-11-14 checkpoint: **213 host contract assertions, Go vet/test, Android APK
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
microphone-to-room recognition, cross-network handover and prolonged background behaviour remain
unverified against the user's services. The resumed actual-service tool and Wi-Fi checks above
cover distinct, explicitly bounded acceptance.
External Passport is deferred. Prepared mode exercises the real client boundary, reducers
and hardware; its content is still prepared. Test counts do not represent a completion percentage, and none
of the rows below is a claim of complete parity.

## Current comparison table

The UI maintenance pass centralises window/font geometry in `AdaptiveMetrics`, bounded columns in
`AdaptivePage`, short-window/IME chrome in `AdaptiveColumn`, window-local insets in `InsetsFrame`,
and native per-page form/scroll/focus state in `PageStates`. Shared controls remain under `Ui`,
`Sheets` and `ViewportNavigation`. Business drafts stay in `PocketController`; architecture checks
forbid controller geometry and business imports in the adaptation classes. The physical ~259dp E2
is one compact window, alongside the 22-case API 35 matrix and actual window/accessibility runs
summarised above. See [ui.md](ui.md). The final idle-background run observes 900 seconds on the real
E2, requests RUNNING_CRITICAL and accepts actual Online on foreground return; it does not accept
natural LMK, overnight standby or sustained ambient capture.

| Upstream area | Android owner and current implementation | Remaining parity / acceptance work |
| --- | --- | --- |
| Tailscale / pairing | `GoTransport`, `Endpoint`, `SecretStore`, editable pasted/opened/shared links, local QR decode and scan sheet; native login QR/link/status sheet for devices without browsers, explicit interactive login/refresh preserving pending auth; actual node URL decoded from the visible E2 sheet in a dedicated two-assertion login run; real user authorization and link-to-pair succeeded; node login/approval pauses and generation-fenced explicit logout; shared `RoomAccess` credential generation across HTTP/outbox/speech, exact authenticated unauthorized classification, native cancellation and late-result guards; prepared native caller-by-caller rejection and durable recovery acceptance | Actual logout/relogin and revoke/re-pair, browser handoff on a browser-equipped device, actual displayed pairing QR into a room and cross-network handover acceptance. Physical Wi-Fi loss/restoration is separately accepted; Passport BLE-wake acceptance is deferred. See `connection.md` |
| Client API compatibility | Pure `UpgradeNotice` matching upstream messages 410/404 and live-ready version rules; app-update priority, typed outbox gate independent of dismissible errors, retained id/body/surface and fresh-ready recheck; bounded compact explanation sheet with native 48 dp targets; prepared native failure/retry and Try isolation acceptance | Actual-service forced version rejection and external Android update handoff remain unaccepted. No public Android update URL/automatic installer; APK installation is manual. See `connection.md` |
| Live/cache/outbox | API v2 `/live`, SQLite cursor transaction, replay, stable ids, persisted retry surface/draft/chips, generation guards; common retirement for reader/writer/queue/HTTP transport failure; upstream redial delays and foreground/press reset; default-network path monitoring with tsnet rebind and live ping; still-open/stale-callback/queue-saturation and actual retry-deadline fault acceptance; live-first bounded dialing/ping independent of HTTP, cancelled retired handshakes, foreground rebind/idle cleanup, generation-scoped metadata and protected retry deadlines; separate bounded sender with pre-registered cancellable native requests, one readiness/HTTP deadline and actual-room read-only JNI acceptance; after-row `brain_unreachable` resend preserves the failed logged row; real history/text/reply, physical Wi-Fi loss/restoration and actual background/return accepted; path-only cleanup receipts commit with outbox removal and recover failed deletion, discard and clear on ordinary startup; actual pre-commit rollback and post-commit cleanup across force-stop/cold launch with prepared rows; explicit answer ids survive attachment/renamed identities and SQLite reopen, shared live/history/catch-up settlement, cached two-day ready settlement, provisional/log reconciliation and generation-owned history loading with prepared native replay/failure acceptance | Different-Wi-Fi/cellular handover and long-term standby/path/energy acceptance; process death during real server admission/replay and upload; natural speech and low-storage/power-loss recovery; low-memory termination and remaining historical/unsequenced identity cases. Lost ids in older attachment/renamed cache rows need replay. Earlier intermittent timing and cold-start triggers were not retroactively diagnosed. See `storage.md` and `history.md` |
| Thread and tools | Keyed bubbles, day/time separators, heard fold and speaker labels, spoken/partly-spoken metadata, live room notes, Markdown, copy/share/reply/read, tool archive and jump-to-latest; completed traces follow explicit ids/text onto attachment or renamed log rows, prepared native final/log ordering and same-text/different-turn checks | All dynamic message/tool/attachment accessibility subtrees and actual-service live/log interruptions; remaining tool identity edge cases. The page/window/font matrix is accepted separately |
| Composer | Text/voice modes, saved draft and acknowledged native edits, serialized share/map append without losing interleaved typing, raw-touch hold/cancel/resume, keyboard/accessibility start/send and named cancel with permission-generation guards, actual 48 dp primary controls, thumbnails, EXIF, image paste/keyboard input, zoom and uploaded chips; viewport-bounded names, overflow and newly-added chip reveal; real file upload/download/local removal accepted; interrupted uploads retain retryable chips/originals, stale credential callbacks cannot replace them, and local/cached files remain readable offline | Real upload limit/error/progress and retry acceptance; full clipboard lifecycle; more dense composer/font/keyboard combinations |
| UI layout and accessibility geometry | Pure `AdaptiveMetrics` Window Size Class policy (`<600/600–839/>=840dp`), bounded `AdaptivePage`/navigation, `AdaptiveColumn` short-window/IME behaviour, API-specific `InsetsFrame`, native `PageStates`, growable controls, shared `Ui`/`Sheets`/`ViewportNavigation`; 22 API 35 cases (9,616 checks), real display rotation (20), freeform (8), system split (12), actual TalkBack 15.0 (final runner 241) and Google source Switch Access 6.1.1 (11), E2 complete regression (342) and accessibility target (27) pass | Current Play Switch Access, all dynamic message/long-list/scroll subtrees and system permission dialogs, OEM/cutout/fold/external-display and in-progress external-operation lifecycle coverage; exhaustive 1:1 visual audit remains future work. See `ui.md`, `accessibility.md` and `acceptance.md` |
| File/photo/share handoff | `ImportCoordinator`, pure `ImportInbox` and transactional draft/receipt admission; durable original `ImportRequest` before provider queueing and source-release cleanup after admission; ordered multi-file URI dedup, ClipData text/links, temporary/persisted read-grant release; launch-scoped `ImportRoute` and restored external-operation stages; additional native-decodable media→JPEG, file-picker byte preservation and full-resolution oriented camera JPEG; actual byte progress, send guard and durable cancellation with provider signals/polling; first-pair draft transfer and try isolation; real separate-UID picker, force-stop/cold-start recovery of an unfinished provider copy and second-restart dedup, stock camera capture/confirmation; actual path-only server upload/download accepted with a production-derived digest | Low-memory task recreation, expired temporary grants, unanswered permission dialog restoration, revoked/noncooperative providers, low storage and large batches; valid HEIC decoding on E2; modern Android and real-room limit/failure/retry acceptance. See `imports.md` |
| Voice notes | Exclusive E2/native/headset input, real RMS, Opus framing, room-scoped durable voice manifests and transactional SQLite admission receipts; visible failed-storage recovery, native held-recording/commit/discard force-stop acceptance and manual retry of complete prefixes with prepared uploads; frozen body and original key/surface on retries, monotonic 25-second budget, live-result precedence and cancellation; bounded independent send work, cancelled queued/running lifetimes and native sending-transition failure recovery with exact retained bytes; actual E2 hold cancellation/background callback/focus interruption and retained-packet retry under prepared outcomes; one marked synthetic fixture through actual ASR/delivery/reply with the real Activity backgrounded and native upload-service cleanup; actual Activity background retry reacquires native data-sync service, prepared service-start refusal defers without HTTP and actual foreground return resumes; pending foreground starts promote before idle self-stop and acknowledge start ids, with native cancelled-before-create crash reproduction and recovery | Acoustic microphone-to-room recognition; actual empty/ASR/service failure outcomes and offline paths; external mic/SCO routing, full physical storage/power loss, legacy orphan files, upload process death and prolonged background lifetime; modern Android service-start restrictions. Prepared failures/native focus requests and synthetic API recognition are distinct evidence; see `audio.md` |
| Ambient | `AmbientInput` handles capture-before-hello, meta/seat/takeover, microphone-on semantics, overlapping presses and final mic restoration before asynchronous exit/focus teardown; pure `AmbientOutput` owns immediate muted-block clocks, fillers/presses, read priority and stop/hush distinctions; generation-scoped hardware receipts, mute-session reset, native focus pause/gain and `audio=0/1` reconnect; interruption local notice follows paused ambient intent and withdraws on recovery/foreground/stop/rejection/process recreation; upstream short-outage capture retention, sustained-outage release and foreground-only resume with actual deadlines/native mic under prepared faults, persistent 1008 refusal and explicit reopen; foreground service, full read-only conversation, transcript/room notes; marked synthetic actual ASR/reflex/TTS and a separate actual brain/Bash/completion/reply turn, completed zero-volume hardware timelines and local-fault actual-server redial | Natural acoustic recognition and audible playback; ambient capture during physical Wi-Fi/cellular switching and long-term path/energy acceptance; actual calls/route changes and external headsets, background/process death and energy trials. Native competing focus requests are accepted; **39-assertion interruption probe** uses native focus/capture with prepared room outcomes and does not establish physical background/call acceptance |
| Read aloud | Shared incremental speech transport, PacketStream, cancellable Go/local streaming, bounded PCM and cut/error distinctions; prepared and real synthesis decoded/played to hardware watermarks with muted output; repeated real request/stop ignores late callbacks; native prepared local-read/live overlap accepted; 20 ms startup/idle restart and in-flight block accounting; shared credential rejection and retirement, registered native stream cancellation without late-start/late-stop tombstones; upstream `(status,error)` classification including exact `nothing_to_speak`; native AudioTrack failure cancels the read generation and rejects late packets | Actual server live-output overlap, external routing, failed synthesis and prolonged-stream acceptance; **27-assertion read-failure probe** uses prepared HTTP and native failure injection |
| Passport (deferred by user) | Existing BLE central, press/reply protocol and persistent memory retained; normal conversation/settings entries removed; standalone policy currently disables automatic resume, press routing and accessory background work | Outside current acceptance scope. Resume external pairing/reconnect, wake/result timing and firmware logs only if requested; preserve E2Studio's connection |
| Session viewer | Scope gate, watch/list/open/gaps/Markdown/reconnect; source/activity, five-row group folds, room return, failed tool summaries; strict atomic wire validation and cached immutable snapshots; notify outcomes retain recipient/id/body and separate drafts through theme recreation; actual scoped session list and first-session row window accepted | Actual notify delivery and disconnect uncertainty, large real lists/fold accessibility, live cross-session identity and failure acceptance; retained drafts are not process-death storage |
| Sight | `SightSession` owns validated asks/gates/restarts; Camera2/MediaCodec YUV, Annex-B/v1, keyframe/backpressure/numbering, rate/thermal handling, first-key status, rear colour viewfinder, actual lens availability and remembered lens, physical orientation; availability observation and finite `SightRecovery` without clearing opt-in; refusal/error state remains visible separately from controls and room details, including expanded transcript; unknown reasons stay literal and socket replacement clears diagnostics; prepared refusal/re-ask verified against native capture | Real server negotiation/backpressure/rate acceptance, recognisable-scene aiming and physical orientation; external-app eviction, actual transient encoder faults and thermal trials; fold/stand equivalents on relevant hardware |
| Location picker | Native draggable OSM tiles, keyboard/accessibility pan/zoom, scrollable search/selection, actual minimum map area, coordinate insertion; bounded lookup queue and 12/15-second deadlines, stale-result guards, coarse-only permission and missing-provider feedback; API 35 actual public-landmark forward/reverse Geocoder returns results without a substituted backend or fix | The E2 reports no framework Geocoder and no enabled provider; actual fixes/visits and tile/search/offline/permission flows remain unaccepted; modern Android approximate-permission acceptance. API 35 Geocoder is separate from physical visits |
| Arrival place | Android passive/network dwell monitor, geocoded arrival ledger, percent-encoded place header and settlement, opt-in/permission/source settings, boot/package/provider refresh; monotonic freshness/order/accuracy gates and reboot/opt-in boundaries; shared cross-instance mutation lock, generation/candidate/permission/expiry checks around geocoding, opt-in cutoff for old acknowledgements; bounded broadcast work with actual overflow/deadline acceptance under prepared blocked work | Real visits/geocoder/privacy/permission/process-death acceptance; verify Android monitoring reliability against iOS visit behaviour; legacy geocoder calls can occupy the bounded worker until they return or the process ends. See `location.md` |
| Background answers | Local notifications, runtime/settings permission UI, room-specific live/log dedup, silent first cursor bootstrap, 15-minute JobService catch-up and explicit service lifetimes; explicit correlation shared by live and attachment catch-up, actual Activity stop/NotificationManager dedup/bootstrap under prepared outcomes; catch-up respects node login/logout/pairing intent, cancels retired work, ignores old-token responses and completes once on every path under prepared native outcomes | Actual-service background notification/recovery, modern Android and battery acceptance remain. Immediate push is separately deferred below |
| Immediate Android push (deferred by user) | No immediate server push equivalent to APNs/FCM. Existing local credential AES-GCM and arrival PlaceLedger serve separate purposes | Outside current implementation scope until requested. Missing upstream push AES-GCM format, HMAC answer/thread identifiers, PushLedger and fixed-vector validation; Android push key/token registration, relay and receiver are also absent |
| Try mode | Disposable `TryChannel` behind the same HTTP/live/speech transport, isolated in-memory SQLite/outbox/cursor/draft, idempotent receipts, voice framing/cleaned/turn/rows, actual hashed files and downloads, prepared ambient/read-aloud audio, tools and scoped sessions/notify; Chinese/English scripts/resources/UI and isolated language restart | Exhaustive rapid entry/exit/cancel/process-death and no-real-traffic trials; prepared mode does not prove real recognition or synthesis |
| Settings and diagnostics | Audio/haptics/keep-awake/background/headset preferences, themes, route/config issues/arrivals/notifications, actual app/optional-tsnet diagnostic ZIP, Passport app events and licences; scoped Chinese/English UI/resources/error/status catalog with build-time coverage checks; shared actions expose a native button role, navigation exposes selection, and primary 48 dp geometry is accepted through native nodes/actions; actual keyboard capture/cancel and isolated late-permission rejection acceptance | Remaining dynamic/permission-dialog accessibility, real settings/failure/route acceptance, actual firmware log protocol/export and modern Android permission coverage; page traversal is accepted under the documented TalkBack/legacy Switch conditions |
| Engineering / distribution | Separate namespace, arm64 native build, private signing, pinned licences/dependencies/assets, architecture checks, host/device probes, reproducible window/font/accessibility/lifetime runners, Chinese handoff and CI definition | Remote CI has not run; generic modern-Android acceptance, sustained stress/energy/lifecycle regressions, final full parity audit and release packaging |

Acceptance requires real service/device evidence where applicable, process death/relaunch and
failure/cancellation checks, no duplicate/lost sends, a single microphone owner, and all actions
reachable on the current window, including the ~259 dp E2 viewport and the accepted larger windows. Keep E2Studio, its data, default launcher and existing
BLE connection intact.
