# E2 login and room pairing

Pocket E2 runs its own embedded Tailscale node, named `pocket-e2`. The app's login and
room-pairing steps are separate. Authorizing this node does not pair it with a room.

1. Keep the E2 connected to Wi-Fi and choose **Log In to Tailscale** (`登录 Tailscale`).
2. The login sheet displays the actual node's authorization QR and a selectable/copyable link.
   On an E2 without a browser, scan the QR with a phone and sign in to the Tailscale network
   containing the room server. On a device with a browser, the login action opens it and the
   sheet also retains an explicit browser button.
3. Keep the app open while completing authorization. Backend changes update the sheet. If the
   network requires device approval, approve `pocket-e2` in the Tailscale admin console.
   **Refresh login status** requests the current state without replacing a pending auth URL
   or signing out a connected node.
4. After **Tailscale connected · pocket-e2**, choose **Continue to room pairing**.
5. Generate a fresh pairing link in the room management page. Paste the complete
   `duoduopocket://pair?...` link into **Pairing link**, then choose **Read link**; alternatively
   use **Scan room QR**. The link supplies host, port, HTTPS and the opaque pairing code.
6. Choose **Pair and connect**. A successful response saves this app's room/device credentials
   and opens the conversation. Expired pairing codes require a new link from the room manager.

The login sheet shows progress immediately, keeps its open state through Activity recreation,
and provides an explicit Wi-Fi/refresh message if no auth URL arrives within 20 seconds.
The E2 has no browser registered for HTTPS in the observed firmware; copying a link silently was
the former failure. A native QR avoids depending on another installed application.

For an ADB-assisted handoff, deliver the management page's **actual** pairing link as an Android
`ACTION_VIEW` intent targeting `top.kuaner.pockete2`. Treat pairing and login links as credentials:
keep them out of tracked scripts, logs and screenshots. The scanner decodes QR locally.

`POCKET_E2_TEST_LOGIN_ONLY=1 scripts/test-device.sh` exercises the actual node and the rendered
sheet without authorizing a user or changing room credentials. On the E2 it obtained an actual
auth URL and decoded the visible screenshot QR to that same URL. This proves login-link delivery
and small-screen rendering.

On 2026-11-14 the user completed node authorization and supplied a room link. The real Activity
received that link, **Pair and connect** succeeded, the conversation became **Online**, and
historical messages loaded. One explicitly marked text integration message reached **Delivered**
and received its requested exact live reply, `POCKET-E2-LIVE-OK`. The pairing was retained through
in-place APK updates and the paired try/UI suite's app restarts. A final application relaunch
returned to **Online** with the verified reply retained. This is real room history/send/reply evidence; it does not establish voice,
attachments, session scopes or background recovery. Revocation/re-pairing, scan-to-pair and browser
handoff on a browser-equipped Android device still need separate acceptance. Actual authorization
URLs, pairing links, room identities and conversation captures remain local ignored artifacts. The
live client now monitors Android's default path and probes stale sockets; physical path switching
and Passport BLE-wake ping acceptance remain separate hardware/network checks.

## Background Wi-Fi recovery incident, 2026-11-14

The user reported an actual room connection failure after a background network outage. The app
kept displaying local Tailscale `Running` and retrying, while the computer's Tailscale status showed
the E2 offline. The room host remained online and its `/healthz` returned HTTP 200. Local app logs
recorded a default-network loss followed by restoration; tsnet still logged
`Failed to send handshake initiation: magicsock: network down`. Restarting Pocket E2 alone restored
the node and room without replacing login, pairing, history or drafts.

The bridge's recovery path called the socket `rebind` and `restun` debug actions but did not resample
Tailscale's network monitor. Those actions do not clear a cached all-links-down state. `Rebind` now
calls the node's `NetMon.InjectEvent()` before those actions. This rereads actual interfaces and
notifies the engine; it does not force an unavailable network to be marked up. Existing foreground,
explicit reconnect and default-network-change actions all use this path.

The updated APK was installed in place on the real E2. One actual background Wi-Fi disable/restore
then foreground return passed: the room was Online in approximately 2.7 seconds, the app kept the
same process, and the computer saw the E2 online. No message or notification was sent. Go vet/test
and the Android build passed. This targeted recovery check does not establish prolonged standby
reliability; the preceding eight-hour attempt remains incomplete and is not being restarted.
Private diagnostics, before/after UI, APK identity and the single recovery result are retained only
in ignored `.build/connection-incident/`. Earlier short outage or prepared-failure checks did not
cover this incident and cannot support a broader stability claim.

The resumed P0 work on 2026-11-14 reproduced two independent sources of connection delay: the
controller waited for `/healthz` and `/api/state` before dialing `/live`, and a retired Go handshake
kept its dialing worker until the full timeout. `/live` now dials first on a dedicated, bounded
connector that also owns live pings. Health latency and room metadata run separately; metadata
failures retry without dropping a working live connection, while explicit unauthorized responses
still stop access. Metadata callbacks and retries belong to their connection generation. History
bootstrap runs after the room metadata arrives. A healthy foreground ping refreshes metadata too.
Foreground recovery rebinds tsnet and clears idle HTTP connections. Closing/replacing a Go live
cancels its in-flight handshake promptly; host regressions verified both cancellation paths.

A monotonic retry deadline prevents passive backend-status/connection checks from bypassing an
outstanding 1/2/4/8/16/30-second pause. Foreground, a network path change and explicit user recovery
reset it. The device probe observes failed live dials, rather than using health requests as a proxy,
and now exercises the actual 30-second capped pause as well as the earlier retry intervals. The
source of the earlier intermittent 345 ms observation remains unknown; these changes do not
retroactively diagnose that run. Diagnostics record only generation numbers, delay and reset labels.

`ANDROID_SERIAL=<E2 serial> python3 scripts/test-network-recovery.py` is an explicit real-room
hardware check. It cycles Wi-Fi once, restores Wi-Fi and its persisted preference even on failure,
then backgrounds and returns the actual Activity. It sends no message or notification. The accepted
nine-assertion run observed cold Online in 4058 ms, physical Wi-Fi loss/offline in 4143 ms, restored
Wi-Fi/Online in 4214 ms, and actual foreground/Online in 314 ms. The room, draft, quote and attachment
count survived without reauthorization or pairing. An earlier attempt accepted the physical outage
but failed to wait for asynchronous Activity stopping; the revised probe waits for the actual state
owner and uses a real task-background transition. These are bounded observations in the current
environment, not a latency guarantee or prolonged lockscreen/cross-network acceptance. The original
cold-launch incident's exact trigger remains unproven.

Outbox sends now have an independent single-worker executor with one waiting slot. Occupied
health/state/history/download workers cannot prevent a queued text or voice note from starting.
Voice-body preparation and all HTTP retries use that sender; the ordinary three-worker HTTP pool
is unchanged. The preceding APK reproduced a prepared send waiting behind three deliberately held
ordinary workers despite an Online socket. The fixed APK passed **21 live-recovery assertions**,
including that same held-worker case. This is a separately reproduced defect; it does not establish
the cause of the earlier intermittent first-reply assertion in `history.md`.

Each send registers a native request lifetime before queuing work. Settlement by a live result/log
row, discard, room replacement or try-mode switch retires that lifetime and removes queued work.
The Go bridge cancels node readiness, response-header waits and response-body reads. Registration
before dispatch prevents cancellation racing a late start; immediate removal avoids accumulating
cancellation records. Retries retain the immutable endpoint/token, message id, original body and
surface. Node readiness and HTTP use one remaining timeout budget; the voice operation's overall
25-second retry budget remains unchanged. Go vet and race-enabled tests cover readiness/header/body
cancellation, the shared deadline, retries and separation of successive lifetimes. Java contracts
cover the dispatch boundary, idempotent retirement and retained request parameters.

`POCKET_E2_TEST_REQUEST_ROOM_ONLY=1 scripts/test-device.sh` performs only GET requests through this
new JNI/Tailscale path. The actual paired E2 passed **eight assertions**: health/state access, room
identity, immediate refusal after retirement, a successful replacement request and preserved live
connection/composer. Observed health time was 93 ms for that run. No message, notification,
microphone input or Wi-Fi change is involved. In-flight cancellation on real HTTP is covered by a
local Go test server; this actual-room probe does not interrupt a real send or prove physical
handover/standby behaviour.

The same APK then passed the complete **311-assertion** capture/camera/import/paired-try regression,
including the held-worker send case alongside native capture, camera, file-provider and compact
UI paths. The earlier intermittent first-reply assertion passed in this run; its exact earlier
trigger remains unlocated. This successful full run does not establish prolonged stability.

Room credential rejection and Tailscale authorization loss now retire the active outbox request,
clear queued socket work and release upload/capture/playback intent before waiting for HTTP to
return. A sending item returns to queued with its original id, file and surface; already accepted
items retain their distinct server-owned state. A room rejection persists Needs Pairing. A node
requiring login or machine approval preserves the room token, pauses sends/dials and resumes the
same queue after Running. Native status and room authorization remain separate. Try-mode failures
cannot invalidate the real pairing or enter an immediate unauthorized resend loop.

Explicit logout pauses the room immediately, even when ordinary workers delay the native command.
A late Running callback cannot override that intent. Login/logout commands check a generation and
serialize on the node adapter, so an obsolete queued logout cannot run after a replacement action.
If SQLite rejects the paused-to-queued write, resources still retire. Before a later dial, normal
repository selection must durably recover interrupted sends; a continued storage failure shows
the pending-save error and starts no request.

`POCKET_E2_TEST_ACCESS_ONLY=1 scripts/test-device.sh` passed **33 native assertions** through
isolated Settings/preferences/SQLite, actual AndroidKeyStore and the foreground service. Prepared
live 4401, HTTP unauthorized, node approval/login states, late ready/Running callbacks, replacement
tokens and a temporary SQLite abort trigger exercise the shipping controller. The preceding APK
reproduced a rejected credential leaving its request alive. The public logout action is exercised
with all ordinary workers held; its queued native command is then superseded before any worker is
released. The actual Tailscale node is never logged out. The real pairing, Online and composer are
restored; no fixture has a real network delegate or microphone/synthesis path. This accepts the
controller and command-queue boundaries, not actual credential revocation or a fresh authorization
handoff. One earlier run stopped at the actual-room idle guard before entering the fixture without
component diagnostics. The guard now reports only states/counts; that earlier trigger remains
unlocated and is not retroactively diagnosed by the later pass.

The updated APK also passed **336 complete capture/camera/import/paired-try assertions**, **17
native background-upload assertions** and another **eight actual-room read-only scoped HTTP
assertions**. Observed health time in that last run was 113 ms. It preserved the actual room,
Online state and composer without sending another message or changing node authorization.

The next P0 update centralizes authenticated HTTP and speech under an Android-independent
`RoomAccess`. Each instance binds the connection, origin, token, room and local room namespace.
A live-socket redial retains this credential generation; authorization loss, explicit logout,
pairing, forgetting a room and entering/leaving Try retire it. Ordinary HTTP registers only at
worker dispatch. An outbox operation still registers before queuing and retains that one native
lifetime across retries. Retiring access cancels active registrations and prevents queued requests
from starting. Exact authenticated **401 `{"error":"unauthorized"}`** rejects the generation once
before notifying the state owner. Anonymous health/pairing, other 401 bodies, 403 and Try responses
cannot revoke the real room credential. A retired generation's late success or rejection is ignored.
An individually settled/discarded outbox request must still own its registration when its response
returns; its late unauthorized status cannot revoke an otherwise current credential generation.

History, older history, upload/download, catch-up and speech now share this boundary. Their state
callbacks also check credential ownership after returning from HTTP, so an already queued success
cannot update a newly paired room even when its host/room name is unchanged. Interrupted upload
chips retain their local files and become retryable; cached attachments and local originals remain
readable while authorization is paused. Catch-up does not dispatch during node authorization loss,
explicit logout or pairing, and its completion callback runs once even when queued work is retired.
Downloaded-file delivery rechecks ownership on the main thread too. Anonymous pairing remains
independent of the stored room credential.

Android speech now uses `StreamAtRequest` and the same native registration registry as ordinary
HTTP, with immutable origin/token and cancellation through response-body reads. The adapter checks
the read's current state when registering, cancels the registered scope on stop and removes it on
return. Cancelling before dispatch or after completion requires no stream-cancellation tombstone.
The original upstream stream API is retained for compatibility. This change does not add a stream
deadline or claim cancellation of every live-node startup path.

The expanded access probe passed **77 native assertions** with isolated preferences/SQLite and
prepared response outcomes through the shipping controller. It exercises each HTTP/speech caller's
actual unauthorized classification, ordinary/anonymous error distinctions, three occupied HTTP
workers plus retired queued work, exact-once job/file completion, same-room replacement tokens,
a success already queued on the state executor and offline local/cache access. The existing native
foreground-service and storage-abort cases still pass. **260 host assertions**, Go vet and race
tests also pass, including scoped-stream late-start prevention and in-flight cancellation. The
actual node is not logged out, no room credential is revoked at the real service and no test
message is sent. Actual logout/relogin, revocation/re-pairing and prolonged standby acceptance
remain separate gaps.

One complete regression stopped at the sustained-outage microphone-release check at 31033 ms
without component diagnostics. That check previously stopped waiting when the native lease ended,
then separately required the controller's asynchronous stopped callback to have completed. It now
waits for both states within the same 3500 ms limit and reports the native lease, controller capture,
stop flag and ambient state on failure. The unchanged production capture path passed a separate
**41-assertion** outage run. That run did not observe a pending callback between the two states;
the earlier failure's exact trigger remains unlocated. This observation change does not loosen the
retry deadlines or establish prolonged stability.

The final APK then passed **336 complete capture/camera/import/paired-try assertions** and **17
native background-upload assertions**. The sustained-outage state check passed in both complete
runs after its observation change. The final actual-room probes passed **eight scoped HTTP
assertions** (observed health 80 ms) and **17 real-room assertions**, covering session list/rows,
normal file upload/download with exact-byte verification and local cleanup, and incremental speech
through Opus/muted AudioTrack completion plus in-flight cancellation. Real-room/composer/Online
were retained without a new chat message or session notification. The static checks pass with
414 explicit UI uses and 603 catalog entries; compact keyboard and 1.35-font screenshots were
inspected. Natural acoustic recognition, audible playback, actual credential revocation and
long-term standby remain unaccepted.

## Client and channel upgrades

`UpgradeNotice` ports the pinned upstream `PocketCore/UpgradeNotice.swift` rules into an
Android-independent model. A messages response of **410 `update_required`** requires an app update;
a **404** on that same route requires a channel upgrade. Other 410/401/403/service responses do
not imply an upgrade, and errors from unrelated HTTP routes do not enter this classification.
A live `ready` without an integer API major at least 2 requires a channel upgrade. Future compatible
majors are accepted. A later compatible `ready` clears a channel notice; an app-update requirement
outranks later channel notices and remains for the current room/process. Room/Try replacement
clears that transient compatibility state.

The controller exposes compatibility separately from connection/authentication and ordinary
dismissible errors. Clearing a toast, publishing a storage error or returning to foreground cannot
bypass it. The outbox's existing old-API pause now uses this typed state and also pauses successors
after a messages 404/410. Failed items and queued successors retain their bytes, id and original
surface. Once a channel recheck succeeds, queued successors resume and failed items remain available
for explicit retry. An app-update requirement cannot be dismissed by reconnecting the same APK.
This protects pending content; it neither clears pairing nor claims an app has actually been updated.

Conversation and expanded ambient transcript expose a separate, filled 48 dp explanation control.
It opens a bounded bottom sheet identifying which side needs updating and explaining that saved
pending content is retained. **Recheck** requests a fresh live generation, rather than merely
clearing the error while keeping the old socket. Pocket E2 currently distributes an APK locally;
there is no public Android update URL or automatic installer. The app-update sheet gives manual
installation guidance instead of opening the upstream iOS destination.

The preceding APK reproduced a messages 404 showing only a generic failed bubble. The isolated
upgrade probe uses the production controller, SQLite, silent native Opus fixture and compact UI,
with all messages and version responses confined to Try. The final evidence is recorded in
`migration.md`; the explicit reproduction command is:

```sh
POCKET_E2_TEST_UPGRADE_ONLY=1 scripts/test-device.sh
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_LIVE_ONLY=1 scripts/test-device.sh
```

The final upgrade run passes **26 native assertions**: text 404 and exact same-key/body/surface
retry, unrelated 410, voice 410 with retained silent Opus, old/future-ready versions, app-update
priority, no dispatch through clear-notice, retired ready/discarded-response exclusion, real-room
restoration and fully visible 48 dp native explanation/recheck actions. **300 host assertions**
include the pinned upstream status/API vectors and priority/type boundaries. Normal-size compact
screenshots of the filled warning and app-update sheet were inspected. The probe runs separately
from the complete regression.

An added native overlap check reproduced recheck closing the socket while the room microphone
was still off: its prepared wire observations were `close` then `mute:false`, without an old-socket
restoration. Recheck now freezes `AmbientInput.releaseMicrophone()` before resetting the edge and
uses the existing bounded asynchronous close path. The final capture-enabled live run passes
**31 assertions**. It observes `mute:true` before the old socket closes, a fresh `mute:false` on
the replacement, and the identical native capture object plus active held recording throughout.
The rest of its write-pressure, slow-metadata, stale-callback and ambient-exit checks still pass.
This is actual E2 capture with prepared Try transport; it does not establish a physical outage,
actual service microphone control or natural acoustic recognition during an upgrade.
The same final APK passed **338 complete capture/camera/import/paired-try assertions**, including
both overlap checks. Actual compact keyboard and 1.35-font screenshots were inspected. The isolated
26-assertion upgrade probe is separate from that complete count.

The post-suite access run first reached all prepared rejection/cancellation checkpoints, then
failed its combined actual-room restoration assertion while Android power state was Asleep;
system logs show the existing 60-second screen timeout. That assertion lacked per-component
diagnostics, so the exact earlier failed term is not retroactively established. The access harness
now deliberately stops the actual display/Activity before restoration, reports connection/foreground
state without private identifiers, and wakes/unlocks/reopens before the original 35-second Online
wait. It observes Offline/background/asleep before that return and passes **78 access assertions**,
including preserved real pairing, composer, queue and compatibility state. Failure checks run after
restoring real settings rather than preventing cleanup. No product or system setting changed for
this harness correction. **Eight actual-room scoped GET assertions** also pass on the final APK;
that run observed health at 42 ms and sends no chat message or notification.

This does not downgrade or reject the actual service, revoke real credentials, install a replacement
APK or establish an external update handoff. Those remain separate acceptance boundaries.
