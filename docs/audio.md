# Voice and ambient audio ownership

The reference is the pinned upstream `PocketCore/AmbientEdge.swift`, `AmbientInterruption.swift`,
`VoiceUploader.swift` and `App/Engine/AmbientController.swift`. See [Passport](passport.md) for the
separate accessory protocol and its remaining hardware acceptance.

## E2 built-in capture

Firmware 04319's standard Android recording input opens successfully but supplies no useful
microphone signal. During release preparation the generic public-package APK had replaced the
local array build. Recording duration alone therefore did not establish that speech was captured.
The same-package, same-signature array build is restored on E2 without clearing pairing or data.
Use [the explicit local build](devices.md#e2-example), `dist/Pocket-E2.apk`; never replace it with
the generic release APK. Vendor libraries/models remain private local inputs.

A bounded local-only probe recorded three seconds each of voice/natural input while playing a
one-second calibration tone. The restored array route captures the tone in both modes, unlike
the generic input's effectively absent signal after startup. The microphone is released and no
audio is uploaded or sent to the room. A separate generic-build check confirms that the adapter
reports its localized limitation, emits zero packets and releases capture. A selected headset
microphone can still use framework input. These checks establish input restoration, not new human
ASR accuracy or end-to-end voice acceptance.

The composer keeps the hold button in place and displays one recording panel for touch, hardware
and accessibility. The timer and simple bars show recording state. Releasing inside sends; moving
outside and releasing cancels. The same panel shows explicit Send/Cancel controls for recordings
started without touch. The [E2 probe](input.md#bounded-verification) checks both paths, stable touch
position, one send per release and capture cleanup in Try.

The diagnostic probe is opt-in and saves private PCM only to the device's external cache and the
ignored host build directory:

```sh
ANDROID_SERIAL=YOUR_DEVICE_SERIAL POCKET_TEST_VOICE_INPUT_ONLY=1 \
POCKET_TEST_AUDIO_CALIBRATION=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
```

Run only while capture/playback is idle. The calibration tone plays locally at a bounded level
without changing system volume. Omit `POCKET_TEST_AUDIO_CALIBRATION` for quiet input measurement.

## Finishing an ongoing reply while locked

On 2026-11-15 the user reported spoken replies stopping when the screen locks. The previous
controller explicitly stopped non-ambient output on Activity backgrounding, closed the idle live
socket and rejected subsequent binary frames. Only ambient capture/uploads/accessory work could
hold the foreground service; ordinary reply playback had no such lifetime.

An already-started reply or local read now retains its receive/playback lifetime independently
of Activity visibility. `AmbientOutput` tracks the current declaration, decoded duration and
matching `playback/done` duration; completion may arrive before the last audio bytes. Hardware
drain remains a separate condition. Gaps between chunks do not end the reply. An omitted or
incomplete receipt expires after 30 seconds without incoming progress and without pending hardware
audio, so an idle service does not survive indefinitely. Local reads use their existing HTTP-end
and hardware-drain ownership.

`PocketSessionService` declares the `mediaPlayback` type for this work and holds an app-only
partial CPU wake lock while playback is pending. It does not keep the screen on or start a
microphone. Its English/Chinese notification offers Stop playback. Drain, explicit stop or failure
releases playback protection; an otherwise idle background live connection also closes. Existing
ambient capture, uploads and accessory work keep their independent service needs. This fixes
continuation of an already-started reply, not automatic speech initiated after the app is idle
in the background.

The focused `POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY=1 scripts/test-device.sh` probe first failed on
the old generic APK at the actual screen-off generation/connection check. The fixed signed generic
APK passes 15 bounded API35 assertions: Activity stops with the display asleep, the playback
generation and spoken surface survive, native service type is `mediaPlayback`, the CPU wake lock
is held, late incoming packets play, and the exact six-second silent timeline drains before the
service/socket/wake lock release. Settings, pairing and composer are restored. Storage and transport
are isolated, with no network delegate, microphone or actual room sends.

Seven new deterministic reception/completion/idle contracts pass; the host total is 2,622. Generic
Debug and signed Release builds, localization/source checks and public dual-ABI payload checks pass.
The original-package local-array E2 update also builds. The user then requested migration to the
public identity: the old app was uninstalled and the fixed signed `dev.openduo.pocket` APK was
installed and opened on E2. Fresh Tailscale login/pairing and 15 E2 screen-off assertions subsequently
passed. That generic APK used framework audio; the E2 array correction above restores specialized
capture under the same public identity. Earlier human-speech evidence retains its original build
identity. No broader lifecycle/device matrix or actual-service speech test was repeated.

## Retained voice and ambient evidence

On 2026-11-15 one human hold-to-talk turn passed on the local original-package E2 array build.
The actual room UI shows a four-second voice note, recognition, delivery and a reply; the user
confirmed receiving the audible reply after **Play spoken replies** was enabled. Recognition had
a wording error. This bounded result does not establish exact ASR accuracy, full hands-free
ambient conversation, other routes or the public Preview 2 framework-audio path. It is separate
from the historical prepared/synthetic and muted-playback evidence below. See
[short service acceptance](service-acceptance.md); no repeat recording or replay was run to record
the confirmation.

`PocketController` admits protocol, focus and capture events on its state executor. `CaptureSession`
owns its input lease and Opus encoder on the capture thread. `SpeechPlayer` owns its AudioTrack and
hardware playback-head timeline on its worker. The state executor never blocks on playback or
provider/network reads. Audio focus callbacks first enter that same state owner; their request
generation is checked there, after the executor hop, so an abandoned request cannot later pause a
replacement capture.

`AmbientInput` handles connection metadata, hello-after-first-packet, capture-seat takeover and the
room's microphone switch. A held note mutes the room but keeps live bytes flowing to preserve its
seat lease. Recording cancellation never sends the note. A completed note flushes and syncs its
packet file before entering the transactional outbox with a stable id and original surface. System
audio interruption retains a long-enough note as failed and retryable; cancelling or backgrounding
an unfinished hold removes it. A note shorter than fifteen 20 ms packets is not sent.

`VoiceInbox` now persists the hold's identity and originating room before capture begins, flushes
complete packets at least once per fifty packets, and hands the closed file to SQLite through a
transactional receipt. A failed admission remains visible as one retryable note; repeated failure
does not upload or allocate a new message id. An actual force-stop during capture recovers only
the readable complete-frame prefix as failed, requiring explicit retry. A completed SQLite
handoff is acknowledged without replay, and a persisted cancellation is cleaned without revival.
Try recordings remain isolated in cache. The outgoing voice type/duration and unsent placeholder
use the bubble's readable text colour; the failed-note action has a real 48 dp button target.
The separate native storage run passed **14 assertions**; actual recording/commit/discard crash
checks passed **23 native and 15 host-controlled assertions**. Uploads use a prepared sink, so no
surrounding recording leaves the device. Buffered tail loss, power loss, full physical storage and
legacy orphan files remain explicit limits; see `storage.md` for ownership and reproduction.

The same APK's final background regression initially failed its foreground-state check: the
retained id/body, upload attempt and native service were correct, but the controller had returned
to background. Actual power state was Asleep, and system logs record the existing 60-second screen
timeout before the Activity return. Starting an Activity alone did not wake the display. The
harness now deliberately checks actual screen-off while the denied upload remains deferred, then
wakes/unlocks before returning and checks native window focus and foreground within the original
five-second deadline. The expanded **18-assertion** run passes without production or system-setting
changes. Its restoration also wakes before opening the actual room. This establishes this run's
missing wake, not the causes of earlier intermittent background failures. The complete **336-device**,
**77-access** and **eight actual read-only HTTP** regressions pass on this production APK as well.

The room microphone switch outlives its capture socket. `AmbientInput` remembers that a socket may
have turned it off, even after the last press releases: its queued unmute might otherwise be cleared
by immediate teardown. Ambient exit and focus interruption freeze a final `mute{on:true}` before
clearing capture state. The writer attempts it before close on a bounded asynchronous turn. Capture
and the state owner do not wait for that write. The retiring transport stays valid only for this
control; its listener's old generation still cannot admit frames. A later state transition invalidates
the final-close callback, so it cannot close a replacement socket. A lost network can still prevent
this best-effort final write; every new capture hello restates the current microphone switch.

One state-owned upload operation retains the message, room, client and retry generation. It loads
and validates the framed body once on the network executor, observes the server's upload limit,
and reuses the exact bytes/key/display/spoken/place on every attempt. `VoiceRetry` uses monotonic
elapsed time for the upstream 25-second budget and 0.5/1/2/4-second backoff. Body preparation,
executor delays and the final clamped wait consume that same budget. Neither the timer nor a
delayed network worker may start a new request after expiry. Transport exhaustion retains a queued
note for reconnection; exhausted service errors stay failed with a specific retry action.

Every real voice dispatch requests the data-sync foreground service, including automatic retries
that start after the Activity has stopped. The previous foreground-only gate left background
reconnection uploads unprotected. If Android rejects the service start, the note stays queued with
an explicit return-to-app explanation; no HTTP upload starts, and passive pumps do not repeatedly
ask for a denied start. Returning to the actual foreground clears the deferral and pumps even if
the live socket is already healthy. The original bytes, message id and request surface remain.
Finishing, live settlement or discarding releases the service as before. Generic modern-Android
foreground-service limits still require hardware acceptance.

`POCKET_E2_TEST_BACKGROUND_VOICE_ONLY=1` reproduced the missing service on the previous APK, then
passed **15 targeted assertions** on the fixed APK. It moves the real Activity to the background,
exhausts the actual 25-second retry budget with prepared transport errors, reconnects and verifies
the native foreground service for the new upload while the Activity remains stopped. An injected
service-start restriction retains the note without HTTP; returning the actual Activity resumes
with the same id/body and settles/releases the service. The disposable SQLite repository and
transport have no network delegate, the fixture is silent Opus and no microphone is acquired.
The actual paired room/composer/online state are restored. This is native Android Activity/service
acceptance with prepared HTTP/live outcomes, not another real ASR or natural voice test.

A later regression failed the foreground-recovery service assertion once and then passed it.
A focused test reproduced a startup publication race: an earlier queued idle notification could
reach a newly started service while the state owner had not yet published upload intent, causing
the service to stop itself. `refreshService` now publishes current intent before requesting
Android startup, for uploads and ambient capture alike. The test holds the state owner inside
startup for 750 ms while Android creates the actual service and delivers the earlier notification.
It failed on the previous ordering and passes on the final APK, whose expanded background-upload
run passed **17 assertions** including retry, denial, foreground recovery and cleanup. The earlier
intermittent failure lacked component diagnostics, so this establishes the reproduced race and
its correction rather than retroactively proving that run's exact trigger. The final expanded
probe retains non-sensitive component diagnostics for any future failure.

Live results outrank HTTP completion. Only sending/accepted notes accept `cleaned`; a late cleaned
event cannot revive a queued/failed item. A failure, authoritative row or user discard releases the
current send slot and invalidates its timers/callbacks. Thus a lost HTTP response cannot downgrade
recognized text, reopen an already delivered note, trigger a false reconnect, or read a side file
removed by row settlement. A real transport failure replaces the old live socket before reconnecting,
even if that socket still reports itself open. Empty recognition removes the pending note and its
file; ASR/service failures preserve the recording and show the corresponding retry reason.

All live-reader errors, text/binary writer errors, queue rejection and exhausted HTTP transport errors
use the same retirement path. It invalidates the connection generation, clears the bounded old writer
queue and closes the transport before scheduling a retry, including when `isOpen()` still returns true.
A delayed close/error from that generation cannot break a newer connection. `LiveRecoveryProbe`
reproduced the former stuck-open failure on the shipping controller before the fix. Its prepared
transport cases stay inside try mode; its native capture cases verify mute restoration on held-note
exit and a background callback, including a stalled final write while local capture releases promptly.

`RedialBackoff` ports PocketCore's 1/2/4/8/16/30-second pauses, resetting on ready, foreground,
explicit reopen or a local press. Short outages retain the same native capture lease without sending
to a closed transport. Continued failures reach the 30-second retry cap after about 31 elapsed
seconds; ambient capture then stops while retry and user intent continue. A local held note retains
its own recording lifetime and is released when cancelled/sent. Recovery clears this link pause only
in the foreground; a ready connection in the background waits for a later foreground callback before
starting a fresh microphone/hello. Reopen reacquires focus, releases the old seat and resets the edge.
Room refusal (1008/PolicyViolation) remains the highest-priority ambient failure until explicit reopen
or session end, even if automatic reconnect succeeds. It does not mark credentials unauthorized.
An independent power control ends ambient capture from retry/takeover states and the expanded
transcript; a reopen action cannot replace the only way to stop the microphone.

The separate 39-assertion `OutageProbe` run exercises real monotonic deadlines at approximately
1/3/7/15/31 seconds, observes the native capture object/epoch across short outages and its release at
the cap, then verifies recovery and a fresh hello. Its transport failures and background/focus events
are prepared inside try mode, not actual Wi-Fi changes or a real Activity background transition.
A separate budget-primed hold overlap avoids repeating the long wait; actual native packets continue
until cancellation, and another local press recovers before the pending 30-second timer. Refusal
survives ready/seat metadata, prepared audio interruption/gain and foreground; the actual narrow-screen
reopen control clears it. A separate power click from refusal releases the real capture lease.
Old transcript/heat text cannot hide the refusal. Native focus competition
is separately covered by the existing audio probe. Android's `NetworkPath` watches the default
network/interface signature, asks tsnet to rebind, and pings an existing live socket before reuse;
healthy paths stay on the same generation, while a failed ping retires and redials it. The live
recovery probe covers both outcomes in the disposable room. Physical Wi-Fi/cellular switching and
long-term path/energy behaviour remain separate acceptance.

`AmbientOutput` is an Android-independent output policy with one current declaration or local read.
It implements these upstream behaviours:

- A new room declaration resets its decoder/accounting while already queued PCM can finish first.
  Old speech watermarks do not credit a newer declaration.
- An unnamed/current `stop_audio` clears buffered answers. A stale named stop, filler stop, or room
  stop during local read has no effect. A remote stop does not send `interrupted` back.
- A local press stops fillers as well as answers and reports the room speech once. Declarations
  received during the press are reported interrupted and their packets stay discarded after release.
- Local read interrupts a room speech. A new room declaration cancels local read, including its
  stream and any late callbacks. Read watermarks never enter the room clock.
- Local hush clears output and sends `hush`, without a second interruption report.
- Muted room audio still decodes to maintain the Opus stream. New blocks are counted as played
  immediately and are not queued on AudioTrack. PCM queued before muting finishes at zero volume.
  Hardware samples and skipped samples contribute to one monotonic cumulative watermark, including
  when the user switches sound back on.
  Closing the ambient session clears its mute flag and restores player output for later reads.

Every hardware receipt carries the `SpeechPlayer` generation from which it originated. The
controller checks that generation on its state executor. This rejects delayed played/active/error
callbacks after a stop even if the server reuses a speech id. Each declaration also gets a private
playback key, so repeated wire ids without a stop cannot combine two streams' cumulative durations.
That key never leaves the client. A stop racing a queue dequeue retains
the first fresh block until a new playing track exists; it cannot write that block into the old
paused track. Playback failure advances the generation only if the failed timeline still owns it.
Pending accounting includes blocks already dequeued by the worker, so ending an HTTP body cannot
mark a read complete before its first block has reached the hardware timeline.
An open read retains its reading state and focus even when a brief block has already drained.
Success or partial failure clears the reading state only after the body ended and the hardware
queue drained. A failure after drain still reports incomplete playback, rather than leaving a
spinner waiting for a hardware callback that already arrived. The native probe injects precisely
that ordering with one real 20 ms Opus block and a delayed transport failure.
Android 8 waits for the effective hardware buffer to fill before starting or restarting an idle
stream. The first block temporarily sets that buffer to at most 20 ms; once the playback head
moves, the allocated capacity is restored. This prevents very short replies from waiting forever
inside a larger streaming buffer. The native probe verifies isolated 20 ms blocks after repeated
stops and after different idle intervals, without padding fake samples into playback receipts.

`AudioFocus` tracks actual grant/loss state. A foreground callback cannot treat a retained request
as a current grant. During ambient interruption, capture pauses while user intent remains on; the
connection releases its seat and reopens with `audio=0`. It resumes with `audio=1` and a new hello
after native focus gain or an explicit resume that obtains focus. Transient loss retains the request
needed to receive gain. The current E2 firmware returns gain after both tested competing transient
and ordinary gain requests; this does not prove phone-call or external-headset behaviour on other
Android versions. AudioTrack volume changes are local to this application; system volume stays intact.

The local interruption notice is now owned by the ambient state, not by each raw focus callback.
It is posted only while ambient intent remains on, the app is backgrounded and capture is paused;
focus gain, foreground return, explicit ambient stop, room rejection and process recreation withdraw
the same fixed notification. This prevents a recovered session from leaving a stale “环境模式被打断了”
notice, while an ordinary read-aloud focus loss cannot claim that ambient mode was interrupted.

Read-aloud HTTP errors follow the upstream `(status, error)` pair: only `422/nothing_to_speak`
uses the empty-content explanation, while other 422 responses retain their status. If the native
AudioTrack fails after a block has started, the controller cancels the open stream, clears the
reading spinner and playback focus, reports the device error, and rejects late packets. A fresh
read creates a new hardware generation. The focused interruption run passes **39 native assertions**;
the focused read-failure run passes **27 native assertions**. Both use actual E2 capture/focus or
AudioTrack ownership with prepared transport outcomes and send no room message.

Verification combines upstream-derived pure output contracts with a separate native audio probe in
the disposable try room. Its prepared stimuli exercise actual E2 capture, JNI Opus and AudioTrack,
observe outgoing frames, and deliberately delay hardware receipts behind the state executor before
stopping and reusing the id. It also obtains competing Android focus requests, checks that actual
capture is released throughout loss despite a foreground callback, and observes resumed uplink and
the connection's audio switch. Silence is used for the output fault cases. No test message leaves
the try room. A separate opt-in real-room probe verifies actual synthesis through decode and muted
hardware completion, cancellation and late-callback rejection.

The separate voice probe delays HTTP at the ordinary client boundary and supplies prepared live
outcomes in the disposable room. It checks live/HTTP ordering, exact retry bytes, deletion during
backoff, empty/ASR/service outcomes and actual elapsed-time expiry. Its optional capture cases use
the E2's real hold path: cancellation, foreground-callback cancellation, independent native focus
loss, preserved Opus and retry under the same key. Those injected recognition outcomes are not
actual ASR of the captured microphone audio.

The next sender correction passed **43 voice-upload assertions** in the isolated room. It uses
the actual JNI registry to check its eight-lifetime bound and pre-start cancellation, then prepared
cancellable HTTP to exercise discard while a worker is returning, discard of its queued successor,
and delivery of the next retained note without changing its key/body or growing sender threads.
Late cancellation errors cannot replace that acceptance or reconnect a healthy live socket.
An actual temporary SQLite trigger rejects the transition to Sending: the original queued bytes
remain intact, no HTTP starts and the registered scope is released. Removing the fault and returning
to the foreground resumes the same message and clears the temporary storage error. The earlier
live/HTTP ordering, retry bytes and real 25-second expiry checks also pass. These are prepared
transport/recognition outcomes with silent Opus; they do not establish natural speech recognition.
The existing **17 native background-upload assertions** also pass on this APK, retaining the
service-start ordering, background retry, denied-start deferral and actual foreground recovery.
See `connection.md` for sender isolation and actual read-only scoped HTTP evidence.

`POCKET_E2_TEST_AUDIO_BURST_ONLY=1` measures a native downlink burst without capture or audible
output. The bundled first English reply's 989 packets (19.78 seconds at 20 ms per packet) were
decoded on the real controller's state owner and stopped in 151 ms on the preceding APK. Real
PCM was pending before stop. This isolated measurement did not reproduce the earlier four-second
first-reply timeout and does not diagnose it; no audio scheduling behaviour was changed on that
basis.

The next access-recovery probe also reproduced an Android foreground-service crash when an upload
was cancelled before the main thread could create/promote its requested service. Android reported
`Context.startForegroundService() did not then call Service.startForeground()`. A deterministic
main-owner hold reproduced the same crash on the APK preceding this service correction.
The controller now publishes idle intent instead of issuing an external stop against a pending
foreground start. The service ignores listener updates until its first foreground promotion,
immediately checks the latest intent after promotion, and uses `stopSelfResult(lastStartId)` so
an older idle callback cannot cancel a newer undelivered start. The **33-assertion access probe**
waits past the native startup deadline after that precise cancellation window and verifies cleanup.
The earlier **17-assertion background-upload suite** also passed after this service correction;
its existing delayed-start publication, denial and recovery checks remain distinct from this new
pending-start cancellation case. Authorization outcomes and HTTP are prepared, with isolated
preferences/SQLite and silent Opus. Modern Android restrictions and actual credential revocation
remain separate. See `connection.md`.

`POCKET_E2_TEST_VOICE_ROOM_ONLY=1` is a distinct explicit real-service test. It synthesizes one
clearly marked known-speech fixture through the actual service and sends that synthetic body through
the normal controller/outbox/client. A gate delays dispatch while the real Activity moves to the
background; Android's foreground upload service remains active. Actual ASR recognizes the marked
speech, the delivered log retains its advertised voice duration, the service replies with the requested
`语音链路验证成功`, and the service/file/outbox settle. A server-log query checks exactly one copy.
The private app-files journal retains this fixture's key across repeats/restarts, so verification
reuses the message instead of creating another. Preserve that journal when repeating acceptance.
No surrounding microphone recording is uploaded by this test, and no system volume changes occur.

Actual synthesis uses 60 ms Opus packets on the observed service. `KnownSpeech` decodes arbitrary
synthesis packets and re-encodes 320-sample/20 ms native packets for new synthetic voice/ambient fixtures.
Only those normalized packets can use native capture ticks or `packetCount * 20` as a duration. The
earlier retained voice fixture verified its advertised duration round trip, not its acoustic duration;
its existing journal and server row are preserved rather than resent or relabelled.

`POCKET_E2_TEST_AMBIENT_ROOM_ONLY=1` verifies a distinct actual ambient path. It first checks
`/api/state` for ready services and an empty capture owner; it never uses a takeover action. The E2
owns the real capture lease and supplies the clock, but the test boundary replaces every outgoing
microphone packet with silence or one canonical known-speech fixture. After three seconds of silence,
342 native packets carry one clearly marked utterance. Actual ASR produced a human log row and the
requested `环境模式连通成功` reply. The server logged that reply as `reflex`, not a brain answer; a brain/tool
turn remains separate acceptance. The actual synthesized downlink contained 37 packets, decoded and
played on AudioTrack at zero local volume. Actual server playback receipts reached `done` at 2220 ms.
No prepared transcript, reply, downlink audio or playback receipt was supplied by this test.

A deliberate local write exception then retired the actual open socket, redialled the actual
Tailscale server, acquired a fresh capture seat and resumed native-paced silence. The normalized
fixture was not sent again. A separate restart passed 18 acceptance checks using the retained actual
rows and output evidence, confirmed one matching server-log row and verified cleanup. A real native
hold switched the actual server microphone off; ending ambient and cancelling the note restored it
to on, confirmed by independent state requests before and after teardown. No voice note was sent.
Its private app-files `ambient-room-20ms-once.json` journal persists before dispatch and retains completion;
an incomplete attempt cannot be automatically replayed. Retained completed speech/playback observations
can settle acceptance after a test interruption without synthesizing another response. Preserve that
journal and the earlier failed fixture journal. The earlier unnormalized attempt is not ASR evidence.

Remaining acceptance includes acoustic microphone-to-room recognition and actual empty/failure outcomes,
natural ambient brain/tool turns, cross-network handover, long streams, actual
calls and external route changes, prolonged background, ambient/upload process death and energy
trials. Native protocol faults, synthetic API speech and zero-volume hardware watermarks do not
prove audible acoustics or recognition of the E2 microphone's speech.

On the resumed P0 run, `POCKET_E2_TEST_AMBIENT_ROOM_ONLY=1 POCKET_E2_TEST_AMBIENT_BRAIN=1` accepted a
distinct once-only actual ambient brain/tool turn with **21 assertions**. The clearly marked
synthetic fixture requested a read-only query of the computer's current date/time and a known
completion phrase. Actual ASR logged the request; the actual service emitted `received`, `thinking`,
`tool`, `speaking`, `done` and `idle`, with a `Bash` call and `Bash ✓` completion. The production
reducer retained a completed tool trace, and the requested answer arrived in the real log. Actual
downlink carried 176 packets; the server echoed two completed playback watermarks at 1500 and
9060 ms. Local playback stayed at zero volume. A deliberate local write failure then redialled the
actual service, and held-note/ambient exit restored the actual room microphone. Native capture
clocked the stream, but all surrounding microphone packets were replaced by silence. The separate
`ambient-brain-20ms-once.json` app-files journal prevents automatic retransmission; preserve it and
the earlier reflex-test journal. This closes the synthetic actual-service brain/tool gap, while
natural acoustic recognition and audible playback remain unaccepted. Physical Wi-Fi and real
Activity background/return connection acceptance are recorded separately in `connection.md`.
