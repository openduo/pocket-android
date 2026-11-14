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
seat lease. Recording cancellation never sends the note. A completed note enters the transactional
outbox with a stable id, original surface and owned packet file.

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

Remaining acceptance includes real microphone-to-room recognition and its empty/failure outcomes,
ambient ASR/TTS and reconnect against the actual server, long streams, actual calls and external
route changes, background/process death and energy trials. Native protocol fault tests and muted
hardware watermarks do not prove audible acoustics or real speech recognition.
