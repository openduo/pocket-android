# Outbox admission and cleanup across process death

`RoomRepository` owns each room's cached rows, pending sends, draft and cursor in the same SQLite
database. Room namespaces remain independent. Only the currently selected controller room can
pump outgoing messages; a separate test namespace does not create outgoing traffic in the paired
room.

Enqueue and pending-state transitions update the visible row and outbox together. After launch,
selecting a room moves interrupted `sending` items back to `queued`, preserving their id, local
voice path, duration, attachments and original display/spoken/place surface. Accepted items remain
pending until a server row or failure settles them. Admission writes the row, removes its matching
outbox item and advances the cursor in one transaction. Repeating a committed row is idempotent.

The sender now also handles a failed transition from queued to sending. A native request scope is
registered before I/O can enter its worker, but if SQLite rejects that transition, the controller
retires the scope, releases upload-service intent and leaves the rolled-back queued row and packet
file intact. No HTTP starts. It shows the existing pending-save error; a later successful pump
retains the original id/body/surface and clears that temporary error. The isolated voice probe
introduces an actual SQLite abort trigger for this boundary, removes it and verifies foreground
recovery, exact retained bytes and an empty request registry. This accepts the sending-transition
boundary under a prepared database fault, not every disk-full or storage-error path.

Owned voice/attachment files need a separate filesystem operation. Before this change, a process
ending after database commit or a failed delete could leave those bytes with no remaining outbox
owner and no subsequent cleanup attempt. Deletion during an enclosing transaction could also
remove bytes before that transaction committed.

Removal now records a list of owned paths in the reserved `@outbox-cleanup` metadata namespace in
the same transaction. The receipt stores paths only, not message text or credentials. It is kept
outside the selected room's metadata so clearing that room cannot erase the cleanup obligation.
Normal admission, discard and clear drain committed receipts after their transaction ends. If an
enclosing native transaction is still open, no file deletion runs; selection of a repository drains
committed cleanup from every room namespace in that database.

A failed deletion keeps its receipt. Ordinary app startup therefore retries it even though the
original item has already left the outbox. A crash after deleting bytes but before removing the
receipt is harmless: the next drain accepts already-absent files. This adds no schema migration;
existing version-1 metadata can store the reserved records. It protects newly recorded obligations,
not untracked files orphaned by an older build. A permanently undeletable file remains recorded for
another retry; no periodic deletion worker or system-storage change is introduced.

## Actual process-death acceptance

```sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_OUTBOX_DEATH=1 scripts/test-device.sh
```

The run passed **24 native assertions** across four phases and **10 host-controlled assertions**.
The host waits for a live native checkpoint before each deliberate force-stop and confirms a new
PID on ordinary cold launch. The fixture uses its own namespace in the production SQLite database,
silent generated Opus and private attachment bytes. No fixture enters the controller's real room,
no microphone is acquired and no room message is sent.

- A native outer transaction contains completed production admission. Its commit callback pauses
  before the actual SQLite commit while voice and attachment files still exist. Terminating the
  real app rolls back row/outbox/cursor/cleanup together. Reopening preserves exact file bytes,
  original id/surface/duration and one queued local row at the previous cursor.
- A second native phase admits that row and deliberately denies file deletion using a read-only
  fixture directory. It also checks discard and clearing another fixture namespace under the same
  denial. The directory permission is restored before the host ends the still-live checkpoint
  process. Ordinary MainActivity startup then drains retained cleanup before the verification
  phase opens its own repository. The committed row/cursor/duration persist, the outbox is empty,
  fixture bytes are gone and replay leaves one local row.
- Cleanup removes only fixture namespaces, files and the private test journal. Pairing, the actual
  draft/quote/attachment paths and an empty actual outbox are checked around the restarts. A missing
  cleanup journal is safe to restore; an interrupted journal remains scoped to its original room.

These are actual force-stop/SQLite/filesystem/reopen checks with prepared rows and a deliberately
introduced deletion failure. They do not establish low-memory termination, a real server replay
interrupted mid-delivery, or remote ASR/message deduplication. The separate held-recording checks
below cover native capture across force-stop with a prepared sink; natural speech and actual-server
interruption remain in `migration.md`.

## Held recording admission

`VoiceInbox` is an Android-independent file handoff owned by the controller's state queue. Before
the microphone starts, it creates a stable UUID, the originating room namespace, a creation time
and a `recording` manifest beside `files/voice/<uuid>.packets`. Try mode uses a separate cache inbox
and never promotes a recording into the paired room. Manifest replacement writes and syncs a
temporary file before an atomic move. This is separate from the existing outbox-removal journal.

The capture writer flushes every fifty 20 ms packets. Normal release closes the writer, validates
the packet framing and syncs the file before recording a ready handoff. Recovery of an unfinished
hold accepts only its readable complete-frame prefix of at least fifteen packets. It keeps the
original UUID, room, creation time and surviving duration, trims a broken trailing frame on durable
handoff, and creates a failed note requiring explicit retry. It never automatically sends an
unfinished hold. Bytes still in the application's buffer when the process dies can be lost; the
one-second flush is not a power-loss durability guarantee. Explicit Activity-background cancellation
continues to cancel an unfinished hold rather than saving it as an outgoing message.

`RoomRepository.enqueueVoice()` inserts the visible row, pending item and a `@voice-handoff`
receipt in one SQLite transaction. The controller removes the file manifest before removing the
receipt. A crash after commit can therefore acknowledge the manifest without recreating or
downgrading a sending/delivered/discarded note, even if normal outbox cleanup already removed its
packet file. Receipts live outside the selected room's metadata and survive room clearing until
the corresponding manifest is gone. They contain the recording UUID and room namespace, not
credentials or audio.

If SQLite rejects admission, the file handoff remains and the current room displays one failed
note with `录音尚未保存 · 轻点重试`. Retrying while storage still fails sends no HTTP and keeps the
same identity. After storage recovers, admission and upload retain the original bytes and key.
Discard first atomically renames the manifest to `.discarded`; startup completes failed file
deletion without resurrecting the cancelled note. Recovery skips an active hold and notes already
awaiting admission, rejects paths outside its inbox, and reports unreadable manifests without
assigning them to the current room.

The native storage probe passed **14 assertions**, including actual E2 hold capture, a temporary
SQLite abort trigger, visible deferred admission, continued-failure retry, one prepared upload
with exact captured bytes, receipt replay and discard. It also checks the actual compact-screen
voice labels' contrast and a fully visible 48 dp retry target with a native button role. Its HTTP
sink is disposable; no surrounding microphone recording is sent to the actual service.

```sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_VOICE_STORAGE_ONLY=1 scripts/test-device.sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_VOICE_DEATH=1 scripts/test-device.sh
```

The second command passed **23 native assertions** across five phases and **15 host-controlled
assertions**. It reaches two live checkpoints and performs three actual force-stop/cold-launch
cycles: during a real native hold, after SQLite admission but before manifest acknowledgment,
and after acknowledgment/discard. Ordinary MainActivity startup recovers the first recording
under its original isolated namespace, acknowledges the committed handoff and cleans a staged
cancellation. Activating only that namespace permits explicit retry to a prepared sink; a further
cold launch cannot revive the discarded recordings. The actual pairing, room, composer and idle
microphone are checked around the restarts, and the private QA journal is removed after restoration.

The first process-death attempt reached both live checkpoints and passed post-commit checks, then
stopped because the harness assumed an instrumentation process would remain alive after finishing.
The harness now explicitly launches the Activity before the next force-stop. Its restore path also
closes the prior live transport before reconnecting. The subsequent complete run passed all phases;
the earlier partial run is not counted as full acceptance.

These are real microphone/file/SQLite/process checks with prepared network outcomes. Full physical
disk exhaustion, power loss, low-memory termination, natural speech recognition and interrupted
actual-server delivery remain separate acceptance. Files orphaned by older APKs without a voice
manifest are not automatically adopted or deleted. Unreadable files retain their manifest for a
later recovery attempt; this does not guarantee an immediately usable message for every physical
storage failure.
