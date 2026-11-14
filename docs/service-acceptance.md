# Short checks against actual room services

English · [简体中文](service-acceptance.zh-CN.md)

The pending milestone covers natural microphone input with audible replies, actual Sight
negotiation/uplink and receipt of a session notification. The client follow-up fixes permission
handling and missing-capability feedback; it does not complete these three actual-service checks.
Use one short check per path and fix observed failures. The earlier window matrices and overnight
background task are outside this milestone.

On 2026-11-15 the user confirmed that no new room-service release is available. Actual Sight is
waiting for a released service that offers the required media capability. This is a release
dependency, not evidence that the user missed an available update. Existing speech/reply and
session-reading evidence predates Sight support; microphone/audible-reply and scoped notification
checks can proceed independently. Notification delivery itself remains unverified.

At the user's request, physical E2 checks now use a same-signature update of the original
`top.kuaner.pockete2` package. The update retained the existing pairing, `pocket-e2` tailnet node
and 23 cached rows, and reached actual Online state with `notify` scope. No extra node or new
pairing is required. This was a read-only prerequisite check, without recording, playback,
camera capture or notification delivery. The current list has no dedicated ambient/keepalive
recipient accepting notifications. The computer's local daemon is stopped; it does not provide
administrative access to the room host.

## Prerequisites

- Continue physical E2 checks on `top.kuaner.pockete2` using the [same-package build](devices.md#same-package-testing).
  Its existing login and pairing are reused. The public `dev.openduo.pocket` package is a
  separate installation and needs its own credentials only when testing that package.
- For notification acceptance, the device credential needs the `notify` scope. A `sessions`
  credential allows reading; the recipient's `notify: true` flag cannot grant device permission.
  The UI and controller share the Android-independent `SessionViewer.Access` policy.
- Sight alone requires a released room service with media support, which is currently pending.
  When available and deployed, an actual `/live` connection must confirm `media=1`; its master
  seat must receive a real camera capture offer. Package version alone does not establish the
  running process's capability. This is not a prerequisite for speech or session notifications.
- Select a dedicated test ambient room or keepalive job, separate from the paired room. The host
  deliberately hides the connection's own `ambient:<room>` from its session list. The earlier
  own-room probe's `NOT_RUN` result did not diagnose notification delivery.

The current E2 credential already has `notify` scope. For a different installation that needs
it, the host-side CLI can issue a new scoped pairing link, for example:

```sh
duoduo channel ambient device add pocket-android \
  --room room-id --scope notify --url https://room-host.example
```

Run this on the room host with its administrative access. Pairing links and actual host/session
identities belong only in private local evidence.

## Microphone and audible reply

Speak one short sentence through the device's actual microphone. Confirm recognition from the
real service and hear the reply from the intended output. Record the result and any failed step.
Injected synthetic samples, silent input and zero-volume AudioTrack progress remain separate
evidence. Framework audio is the public default; an optional E2 firmware build is a different
hardware path.

## Sight

Confirm `media=1` and the actual host's capture parameters, explicitly enable the camera, then
confirm a real first key frame and subsequent uplink frames. Keep a brief viewfinder/scene check.
When a master connection omits media support, the client reports that the room service does not
offer video support, even if it never sends a camera frame. It makes no claim that an update is
available. This explanation clears on a capable
connection and does not enable the camera. A prepared control frame only verifies that client
behavior; it cannot satisfy actual negotiation.

## Session notification

Choose the exact wire key of a dedicated recipient whose live session summary permits
notifications. The opt-in probe accepts `ambient:` or `job:` keys; it does not choose a recipient
automatically. This command sends real content to that selected test session:

```sh
ANDROID_SERIAL=YOUR_DEVICE_SERIAL \
POCKET_LEGACY_IDENTITY=1 \
POCKET_E2_TEST_NOTIFY_ROOM_ONLY=1 \
POCKET_TEST_NOTIFY_TARGET='ambient:notification-test' \
scripts/test-device.sh
```

Omit `POCKET_LEGACY_IDENTITY` when deliberately testing the public package. The script requires
a target before touching a device and Base64-encodes the key for the ADB shell
boundary. The probe requires actual Online state, `notify` credentials, idle outgoing/capture work
and an exact listed recipient. It checks both the correlated delivery result and one uniquely
marked notification row in that recipient. A generated reply is useful additional evidence but is
not required to prove receipt. The targeted readiness/notification runner reopens the paired app
after instrumentation ends its process, so the user is returned to the app.

The private `files/qa/session-notify-*.json` journal is specific to the source room and recipient.
It records `dispatching` before sending and never automatically resends an uncertain attempt;
AtomicFile backup recovery preserves that rule after an interrupted write. Preserve an uncertain
journal and inspect its actual receipt before deciding any further send. Do not clear app data or
remove journals to obtain another passing run.

Record the APK identity, service capability, selected path and actual outcome in private ignored
`.build/` evidence. Publish only a sanitized result and update [status](status.md) and the
[migration gaps](migration.md). Keep the three paths pending until each has its own actual result.
