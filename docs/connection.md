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
