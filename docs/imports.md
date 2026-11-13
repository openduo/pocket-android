# Share, document and photo handoff

The reference is the pinned upstream `Shared/ShareInbox.swift`, share extension and
`AppModel+Import.swift`. An incoming share adds content to the composer; it never sends a message.

`app/InboundContent` adapts Android intents. Stream extras retain their explicit order; ClipData
adds any remaining content URIs. Repeated representations of one URI in a delivery are one file.
The document picker instead uses ClipData selection order, with `Intent.data` as a fallback.
Plain ClipData text and HTTP(S) links are supported when `EXTRA_TEXT` is absent. A content data URI
is a final attachment fallback. Filenames and text stay literal.
Legacy external `file://` URIs are not read with the app's private filesystem privileges.

The Activity assigns a private UUID per incoming intent and saves it through recreation. A new
`onNewIntent` is a new delivery. Restoring an already-presented share preserves the selected page.
Pair links have their own consumed flag. The controller serializes
draft admission and maintains callbacks for repeated attempts of an in-flight delivery; temporary
IME/read grants remain held until the copy attempt finishes. Persistable document grants are
reference-counted and released after the app-owned copy, including overlapping selections of the
same document. Work never retains an Activity or View.

`core/ImportCoordinator` owns delivery admission, recovery, readers and cleanup without retaining UI.
`core/ImportInbox` is its Android-independent file handoff, owned by a dedicated single-thread I/O
queue with 32 waiting slots. It does not compete with HTTP/upload tasks. It copies each source into
`<delivery>.partial`, syncs each file and the manifest, then renames the directory to `<delivery>`.
Only the final directory is eligible for admission. A source failure removes the entire partial
batch; it cannot append only half its text/files. Each file is bounded by 64 MiB and any known room
upload limit. Original names and MIME are recorded separately from the stored path.

`RoomRepository.importDraft` commits text, quote, ordered chips and a delivery receipt in one SQLite
transaction. The receipt is independent of the current room, so recreating a share-launched Activity
cannot append it again after changing rooms, sending, or clearing the room cache. Receipts contain
only the random delivery id and a consumed marker; try deliveries record their disposable generation.
Acknowledging a manifest retains its files for the draft/outbox. Normal removal/settlement deletes
those files, and startup prunes empty directories. Pending manifests recover oldest first, including
when the wall clock moves backwards. Receipt recovery does not require already-settled files to exist.

A real-room delivery remains scoped to that room if pairing changes during copying. Unassigned
content can enter the unpaired composer and is transactionally merged into the first paired draft;
the transfer also runs on startup to recover a crash after connection settings were saved. A try
delivery is reserved durably before copying, while its text/chips stay in the in-memory try database
and temporary inbox. Leaving/replacing try removes its owned files. A restored old try intent cannot
replay into a real composer.

Photos use the same handoff after the native camera's result. The manifest remembers only the safe
basename of the app-owned `cache/shared/photo-*.jpg` source. Cleanup validates its canonical parent,
deletes it after a durable copy, then acknowledges the manifest. A crash before acknowledgement can
repeat this cleanup. The Activity retains the pending photo path/id through recreation. Camera
launch requests this app's camera permission and handles a refused launch without crashing.

`DraftText` reconciles native edits with content appended before the editor's acknowledgement. The
controller acknowledges edit ids; full draft/room/send resets advance an editor generation. The
view waits for outstanding local edits, then applies authoritative text even while focused and keeps
valid selection offsets. A stale editor cannot repopulate a replaced draft. Map insertion uses the
same serialized append operation. Attachment-name widths account for the actual viewport and image
thumbnail; horizontal overflow is visible, and the removal target remains 44 dp. Adding a new file
reveals the strip's end after layout; upload-state changes retain the current scroll position.

`PocketFiles` grants only canonical direct children of `cache/shared`. URI metadata preserves the
original display name and MIME when another app opens/shares a UUID/hash-named copy. Display names
cannot contain path separators/control characters. Both view and send intents include ClipData and
a read grant; the camera receives a write grant for its output URI only.

Verification uses real filesystem interruption/corruption contracts, an injected SQLite receipt
failure, actual ContentResolver copies, native focused typing interleavings, Activity recreation,
picker/photo result callbacks and try cleanup. `POCKET_E2_TEST_IMPORT_UI=1` additionally drives the
system document picker against a disposable provider in a separate UID, checks grant release, and
opens the real camera/capture confirmation. The test APK/provider is uninstalled at the end.
The optional UI probe currently runs on API 27: a narrowly scoped shell helper saves/restores the
stock camera's location user-decision flags. The test declines optional location access and disables
location tagging in its first-use screen. The helper and test source never ship in the application.

Remaining acceptance work includes process kill during an unfinished external-provider copy,
recreation during an unanswered first permission request or picker, a
provider that blocks or revokes its stream, low-storage/large-batch cancellation, modern Android,
import-progress UI, and real-room upload/retry/removal. An incomplete copy has no durable content manifest: its recovery
still depends on the OS restoring the source intent/permission. The inbox does not claim to preserve
an expired temporary URI before copying completes. Non-inline image formats remain original files;
upstream's conversion of additional decodable image types to JPEG is still a migration gap.
