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
upload limit. Original names and MIME are recorded separately from the stored path. For a media
selection, decodable image types outside JPEG/PNG/GIF/WebP are converted to full-resolution JPEG
at quality 90, matching the upstream media path. A document selection keeps the original bytes.
Camera photos are re-encoded as JPEG with orientation applied and location metadata omitted.
Conversion can read at most 64 MiB of source bytes; the room limit applies to the final encoded
attachment. A bounded full-resolution decode budget avoids silently shrinking imported images.
Unsupported or over-budget native decodes remain their original files, subject to the final limit.
Valid HEIC decoding on the E2 remains an explicit gap; a deliberately undecodable fixture proves
the file fallback only.

`RoomRepository.importDraft` commits text, quote, ordered chips and a delivery receipt in one SQLite
transaction. The receipt is independent of the current room, so recreating a share-launched Activity
cannot append it again after changing rooms, sending, or clearing the room cache. Receipts contain
only the random delivery id and a consumed marker; try deliveries record their disposable generation.
Cancellation stores a terminal receipt before signalling the provider, so reopening an intent
cannot restart a cancelled delivery. A completed admission cannot be replaced by a late cancel.
Acknowledging a manifest retains its files for the draft/outbox. Normal removal/settlement deletes
those files, and startup prunes empty directories. Pending manifests recover oldest first, including
when the wall clock moves backwards. Receipt recovery does not require already-settled files to exist.

A real-room delivery remains scoped to that room if pairing changes during copying. Unassigned
content can enter the unpaired composer and is transactionally merged into the first paired draft;
the transfer also runs on startup to recover a crash after connection settings were saved. A try
delivery is reserved durably before copying, while its text/chips stay in the in-memory try database
and temporary inbox. Leaving/replacing try removes its owned files. A restored old try intent cannot
replay into a real composer.

Every permanent incoming delivery also records an `ImportRequest` in SQLite **before** its provider
work is queued. This retains the original room, delivery id, ordered content URIs, text, media/file
policy and owned-photo name even when the Activity has already consumed a picker result. It is a
request to finish copying, not permission to admit partial bytes. A repeated delivery uses the
original request. Startup prunes interrupted `.partial` directories and resumes recorded requests
oldest first using only read grants Android still retains. Persistable document grants survive a
force-stop; an expired temporary share grant can still fail with the existing explicit import error.
Recovery never substitutes filesystem or web URLs, switches the destination room, or revives a
cancelled/received delivery. Try sources never enter this permanent recovery queue.

The request stays present until the source-release callbacks have run. Thus process death after
draft admission but before callback cleanup can still release a retained document grant without
rereading the source or appending again. Receipts remain independent of request cleanup. Completed
manifests cover recovery after the request is removed, including deferred admission for another
room and owned-photo cleanup. Queued requests are persisted on the state owner rather than behind a
stalled provider on the copy executor.

`core/ImportRoute` captures the delivery, destination and media/file policy before an external
picker or camera is opened. `app/ExternalInputs` saves that route and the photo permission/camera/
import stage through Activity recreation. A returned result belongs to its original room or try
generation. An expired try result is discarded. A restored permission stage resumes once granted;
a late permission callback cannot open a second camera. On Android 8 a stopped Activity's explicit
recreation may be deferred until the picker result resumes it.

Photos use the same handoff after the native camera's result. The manifest remembers only the safe
basename of the app-owned `cache/shared/photo-*.jpg` source. Cleanup validates its canonical parent,
deletes it after a durable copy, then acknowledges the manifest. A crash before acknowledgement can
repeat this cleanup. The Activity retains the pending photo path/id through recreation. Camera
launch requests this app's camera permission and handles a refused launch without crashing.

The composer observes queued/running imports through immutable `ImportProgress` snapshots. It
shows the actual filename, file number and bytes read, with a 44 dp cancel control above the text
row. Updates are coalesced; there is no fabricated total-byte percentage. Draft editing remains
available while sending waits for the import to settle or be cancelled. Provider query/open calls
receive an Android cancellation signal. Pipe reads poll at bounded intervals so cancellation does
not wait for a stalled writer's EOF. Binder cancellation runs on a separate bounded executor rather
than the UI or state thread. `ImportSource` calls a document provider's cancellable file API directly:
Android 8's wildcard typed-asset helper drops the signal before `openDocument`. The provider lease
remains held until the descriptor closes. Other content retains asset-offset/length semantics.
Providers that ignore cancellation inside an unsupported Binder call
(including `getType`, which has no cancellation parameter) remain a limitation.

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

Upload responses require a remote path but may omit SHA-256, as the current real server does.
`ChannelClient` derives a canonical digest from the exact uploaded request bytes; it never trusts
stale attachment metadata. A supplied server digest must match those bytes. The digest is persisted
and sent with message attachments so the existing authenticated download URL and cache validation
work with either response shape. A separate opt-in real-room probe imports a tiny random fixture,
uploads it through the ordinary controller, downloads a remote-only copy with that production digest,
verifies the original bytes and removes its local draft/cache copies. It sends no room message; the
API has no client remote-delete operation, so the tiny server fixture remains.

Verification uses real filesystem interruption/corruption contracts, an injected SQLite receipt
failure, actual ContentResolver copies, native focused typing interleavings, Activity recreation,
picker/photo result callbacks and try cleanup. `POCKET_E2_TEST_IMPORT_UI=1` additionally drives the
system document picker against a disposable provider in a separate UID, checks grant release, and
opens the real camera/capture confirmation. The test APK/provider is uninstalled at the end.
The current paired-device suite explicitly opts in with `POCKET_E2_TEST_PAIRED_TRY=1`. It preserves
the real room/draft and uses the disposable try transport for uploads and sends. The real picker
result survives Android's deferred recreation; a restored granted permission stage launches the
camera once despite a late callback. The separate-UID provider also stalls after 8192 actual bytes
and while opening a second file. The visible cancel action releases both within two seconds,
removes partial files, preserves existing text/chips and rejects a repeated cancelled delivery.
The next normal import succeeds. Native image checks distinguish unchanged inline/document bytes
from converted media/camera pixels and test the encoded upload limit. These are provider/UI and
native-codec checks; their uploads are prepared. The separate real-room probe supplies the limited
actual upload/download/removal acceptance described above.

`POCKET_E2_TEST_PROCESS_DEATH=1` is a separate host-controlled acceptance run. It selects a real
document through the shipping narrow composer and system picker from the test provider in another
UID. The provider emits 8192 bytes and withholds EOF. Outside instrumentation, `am force-stop` ends
the actual Pocket E2 process and closes that pipe while the provider process survives. A normal
launcher start obtains a different PID, resumes the persisted request under its retained document
grant and admits the complete 16384-byte fixture once. A second force-stop/relaunch retains the chip
and existing draft/quote without another provider read. Native verification checks the exact file
bytes, receipt, pruned partial directory, empty outbox and released persistable grant. Preparation
and cleanup preserve the original room, text, quote, attachments and input preference. The one small
fixture may upload through the normal real-room attachment path, but no chat/session message is
sent. Its remote copy follows the upload API's existing no-delete limitation. The QA provider and
test APK are removed afterward; raw trees and synthetic control logs stay in ignored `.build/`.
This verifies actual force-stop and cold launch, not Android low-memory task recreation or device
power loss. The E2 firmware protects recently backgrounded processes from the ordinary `am kill`
path; the test checks termination and PID replacement instead of assuming that command succeeded.
The optional UI probe currently runs on API 27: a narrowly scoped shell helper saves/restores the
stock camera's location user-decision flags. The test declines optional location access and disables
location tagging in its first-use screen. The helper and test source never ship in the application.

Remaining acceptance work includes low-memory task recreation, recreation during an unanswered
first permission dialog, expired temporary grants and revoked/noncooperative providers,
low-storage/large-batch cancellation, modern Android, valid HEIC conversion, and real-room
upload limit/failure/retry. An incomplete copy has no durable content manifest; its durable request
can resume only while Android retains source access. The inbox does not claim to preserve expired
temporary URI data before copying completes.
