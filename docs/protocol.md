# Protocol boundaries

Source: `../pocket-ios/docs/ble-protocol.md`, client API major 2, Pocket link 1.3. The real upstream
Go bridge is used for HTTP and `/live`; there is no cursor-polling substitute for live events.

Anonymous requests are limited to `/healthz` and `/api/pair`. `DoAt` and `StreamAt` bind each
concurrent request to an immutable endpoint/token, while the one `/live` socket is guarded by a
connection generation. Device tokens use AndroidKeyStore AES-GCM and are excluded from backups.
The Android bridge sets private Tailscale log/temp directories; iOS-only Tailscale patches are not
applied.

A SQLite transaction admits server rows, reconciles matching `utt_id` outbox entries, and advances
the cursor. A 202 remains pending until the server row arrives. Text transport errors queue until
reconnect; HTTP errors (including 5xx) need manual retry. Voice retries transport/5xx errors under
one idempotency key, frozen validated body and a monotonic 25-second budget. Backoff and executor
delays consume the same budget; expired operations cannot start an extra request. The initial
display/spoken/place surface survives retries and process death. Cleaned/failed events may arrive
before the 202 and retain precedence. Only sending/accepted items accept cleaned text; a late HTTP
failure cannot override a live result or logged row. Discard and live settlement invalidate scheduled
retries and release the send slot. Exhausted transport errors keep voice queued and replace the old
live socket for reconnect; a healthy socket stays open after an HTTP service failure.

Voice bodies are `[u16 little-endian length][20 ms 16 kHz mono Opus packet]…`. `hello` follows actual
input capture, `mute.on=true` means the room microphone is ON, and downlink speech declarations
reset decoder state without discarding prior queued PCM. Playback watermarks come from AudioTrack's
hardware head and are cumulative per speech id. Read aloud streams incrementally and detects cut
packets. Outgoing JSON/audio/video share an ordered bounded writer.
Writer failure or queue rejection retires the current connection before retrying, even when the
transport still reports open. Old queued frames and delayed close callbacks stay invalid. Ambient
teardown retains a best-effort room microphone restoration through one asynchronous bounded writer
turn before closing; local capture stops immediately. The retiring transport remains current for
that write while its old listener is excluded by the connection generation.
Live retry pauses are 1/2/4/8/16/30 seconds. Short outages keep capture; sustained failures reaching
the cap release ambient capture while keeping any independent held note. Recovery in the background
waits for foreground before starting a fresh native mic/hello. Ready, foreground, explicit reopen and
a local press reset backoff; press/reopen attempt connection immediately. A 1008/PolicyViolation
remains a visible ambient refusal until explicit reopen/session end, independently of later ready or
seat metadata. Automatic connection retry continues, matching the pinned upstream; credentials are
invalidated only by the separate unauthorized path (for example 4401). Prepared outage acceptance
uses actual retry deadlines and native capture, with lifecycle stimuli distinguished in `audio.md`.
`AmbientOutput` keeps the current speech's hardware and immediately skipped muted samples separate
until forming one monotonic watermark. Hardware receipts retain their playback generation through
the state-executor hop. Local presses/read can report an interruption; remote `stop_audio` and local
`hush` do not add that report. Focus loss reopens without audio and only actual gain or an explicit
successful resume can restart capture. Private per-declaration keys isolate repeated wire speech
ids while hardware buffering preserves the order of the already received PCM. See `audio.md` for
owner and acceptance details.

Media version 1 prefixes audio with 0x01. Video uses 0x02, flags, u32 LE sequence, u64 LE capture
milliseconds, optional u16 LE JSON config blocks and Annex-B H.264. Dropped/oversized chunks require
a new key frame. Capture parameters come from the server; unsupported sizes/profiles are declined.

Passport uses the upstream service/TX/RX UUIDs. It is a BLE **central**, writes CCCD before link-ready,
uses negotiated MTU, and serializes GATT operations. Direction-specific PDU validation, 4 KiB bounds,
UTF-8 fitting, sequence wrap/loss/duplicates, implicit press and link-loss completion are explicit.
RESULT follows cleaned/failed settlement; answers correlate through `message_ids`, and quiet replies,
monotone persisted ids and live/log deduplication are separate from press assembly.
APP_STATE includes the resolved UI-language byte (0 for Chinese, 1 for English) after reachability;
changing language resends it only to this app's already-ready accessory.

Session watches follow visible foreground UI, are restored after `/live` reconnect, and end on page
exit. One correlated notification may be in flight. A connection loss marks its delivery uncertain.
Lists and row windows validate atomically, including wire types; malformed data cannot partially
replace a visible session. Activity order compares absolute instants. Notification receipts retain
the original key/id/body after the reader changes sessions, so acknowledgements cannot clear a
different recipient's draft or text edited since submission. Viewer reset advances a generation
that invalidates retained UI drafts. The refusal words are `forbidden`, `not_notifiable`,
`not_found` and `failed`, under the wire key `reason`.

`ChannelConnection` is the common HTTP/live/incremental-speech boundary. `GoTransport` owns the
native tsnet implementation; `TryChannel` handles those same API-v2 paths locally and cannot accept
a real origin. Try-mode rows, outbox, drafts and cursors use a separate in-memory SQLite repository.
Receipts remain idempotent, delayed voice recognition and turn frames use the real application
reducers, and attachments carry actual SHA-256 digests and are downloaded through the ordinary API.
Prepared speech traverses PacketStream, Opus and AudioTrack, including cancellation. Session notify
samples stay local and are explicitly labelled. Closing try invalidates the local socket, scheduled
turns and streams, removes owned files and restores the permanent room repository. Entering try
does not initialise tsnet or use stored room credentials.

Attachment wire rows retain remote name, MIME, digest, size and path; they cannot supply an Android
local file path or a local upload-state flag. App-owned local paths are restored only through local
draft/outbox persistence or the committed import inbox. The share handoff and its transactional
delivery receipts are specified in `imports.md`.

Before provider I/O, permanent imports persist their original room/source/text/policy in SQLite.
Startup uses retained Android grants to resume incomplete copies. Only complete synced manifests
can enter the draft transaction, while cancelled/received receipts prevent replay. Source-request
records remain until lease-release callbacks finish, so restart can clean retained grants even if
the draft was already admitted. Disposable try sources never enter this permanent recovery queue.

Rear viewfinder pixels come from the currently owned Camera2 YUV image and never require a second
camera. The UI receives one bounded RGB frame, not a recording. Encoding and viewfinder use the same
buffer rotation. Serious heat lowers the declared rate and bitrate in place; critical heat and
backgrounding stop the camera. API27's battery-temperature hint is an Android substitute and is not
equivalent to a calibrated system thermal-pressure signal.
Camera availability is observed only while an asked, authorized foreground camera is enabled.
An occupied camera is not opened. Native transient failures keep opt-in and use finite backoffs;
unsupported parameters hold until a new ask/lens. Ending the ask, changing the connection, switching
off or backgrounding invalidates pending recovery. A resumed stream must send a new key frame but
keeps the connection's monotonically increasing sequence.
