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
interrupted mid-delivery, process death during natural microphone recording, or remote ASR/message
deduplication. Those acceptance items remain in `migration.md`.
