# Short checks against actual room services

English · [简体中文](service-acceptance.zh-CN.md)

The milestone covers natural microphone input with audible replies, actual Sight
negotiation/uplink and receipt of a session notification. One human voice-note/audible-reply turn
and notification receipt now have actual original-package E2 evidence; Sight remains pending.
Use one short check per path and fix observed failures. The earlier window matrices and overnight
background task are outside this milestone.

| Path | Actual result |
| --- | --- |
| 1. Microphone and audible reply | Passed one human hold-to-talk turn on the local original-package E2: actual recognition/delivery, room reply and user-confirmed audible output; recognition had a wording error |
| 2. Sight | Waiting for a released media-capable room service and its actual negotiation/uplink |
| 3. Session notification | Passed on the original-package E2: correlated server success and exactly one marked notification row in the selected dedicated recipient |

On 2026-11-15 the user confirmed that no new room-service release is available. Actual Sight is
waiting for a released service that offers the required media capability. This is a release
dependency, not evidence that the user missed an available update. Existing speech/reply and
session-reading evidence predates Sight support; microphone/audible-reply and scoped notification
checks are independent of that release. Notification delivery passed without a service upgrade.

At the user's request, physical E2 checks now use a same-signature update of the original
`top.kuaner.pockete2` package. The update retained the existing pairing, `pocket-e2` tailnet node
and 23 cached rows, and reached actual Online state with `notify` scope. No extra node or new
pairing is required. That initial check was read-only, without recording, playback, camera capture
or notification delivery.

For actual notification acceptance, the already paired room provisioned one isolated keepalive
job with no outgoing notification route. The app selected its exact live key and sent one unique
marker through the shipping controller. The actual-service probe passed ten assertions, including
the matching successful delivery result, exactly one notification row in the recipient, and
preserved composer/outbox state. The room subsequently confirmed archiving that dedicated job.
No local computer daemon, SSH access, new node or new pairing was needed. This verifies the local
original-package build; the public Preview 2 APK was not rerun or changed.

An earlier prerequisite attempt was interrupted by the device's existing screen timeout. System
logs confirmed sleep during the Online wait. The short readiness/notification probes now keep
only their activity window awake for the check; the readiness retry and actual notification pass.
The system timeout is unchanged. No microphone, camera or playback was used in these checks.

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

On 2026-11-15 the user completed one human hold-to-talk turn through the physical E2 microphone.
The actual room UI shows a four-second voice note, recognition text, delivered status and the
room's reply. The user then confirmed receiving the voice reply. The retained **Play spoken
replies** preference had been off and was enabled through the app before this turn.

The displayed recognition had a wording error; this is acceptance of the microphone-to-room and
audible-reply path, not exact transcription accuracy. It applies to the local original-package
build with the optional E2 array payload. It does not accept full hands-free ambient conversation,
other output routes or the public Preview 2 framework-audio path. The actual UI and user
confirmation are retained in private ignored evidence; no additional recording or replay was
needed. Do not repeat the accepted turn without a relevant failure or change.

Injected synthetic samples, silent input and zero-volume AudioTrack progress remain separate
evidence. Framework audio is the public default; the local E2 firmware build is a different
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

When the paired room exposes host job management, an isolated `cron: keepalive` job with no
outgoing notification route can provide the dedicated recipient. Its incoming-notification
capability is separate from its outgoing `notify` route. Confirm the actual live summary permits
receipt, then archive only that dedicated job after validation. Do not modify existing user jobs.

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
[migration gaps](migration.md). Track each path independently: one human voice-note/audible-reply
turn and notification receipt are accepted for the recorded local build; Sight awaits its actual
result after the required service release.
