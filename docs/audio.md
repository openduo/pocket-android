# Voice and ambient audio ownership

The reference is the pinned upstream `PocketCore/AmbientEdge.swift`, `AmbientInterruption.swift`,
`VoiceUploader.swift` and `App/Engine/AmbientController.swift`. External Passport integration is
deferred by the user; the standalone E2 microphone and output paths remain in scope.

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
calls and external route changes, prolonged background/process death and energy trials. Native
protocol faults, synthetic API speech and zero-volume hardware
watermarks do not prove audible acoustics or recognition of the E2 microphone's speech.

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
