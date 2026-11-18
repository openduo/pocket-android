# Pocket Android engineering rules

E2 recording correction (2026-11-15): firmware 04319 needs its local array build. The generic
APK had overwritten that adaptation during the public-package migration; its framework input
opens but supplies no microphone signal. The same-signature `dev.openduo.pocket` array APK is
restored without clearing pairing/data. Local calibration confirms real array input; the bounded
34-assertion input probe uses actual capture and Try-only sends, then restores the paired room
Online. No new human ASR acceptance is claimed. Never install a generic APK over this E2 again.
An explicit `-Pe2FirmwareDir=...` build now writes `dist/Pocket-E2.apk`, leaving `dist/Pocket.apk`
for generic publication. Firmware/model source files stay out of Git. By explicit user request on
2026-11-18, the generic tag workflow publishes `Pocket.apk`, and the locally built `Pocket-E2.apk`
may be attached to the same public Release with its SHA-256 using
`scripts/upload-e2-release.sh`; the E2 APK contains the 04319 vendor libraries/models. A generic build on this firmware
refuses built-in capture with a localized explanation instead of uploading invalid audio; headset
input keeps the standard route. Touch recording has a stationary button, timer, release hint and
simple recording-state animation (the user explicitly declined real-time waveforms). Separate
Send/Cancel controls remain for other input owners. Retain these narrow checks; no broad matrix.

Sight resumed and accepted (2026-11-15): the user confirmed the room service update and resumed
Sight. The public-package generic APK was tested in place on E2 before the array restoration above. Fourteen actual-service guards
pass: media=1, real 1280x720 ask/master, first key and five native video frames, cleared server
vision_off, explicit off, microphone restoration and retained pairing/composer/Online. E2's
encoder accepts landscape but not 720x1280; generic YuvTransform rotates/aspect-fits with black
padding when only the requested swapped orientation is supported. No model branch. The SDK
submodule correction preserves raw Opus downlink on media=1 (only uplink has kind bytes); retain
that source change with the Android adaptation. 2,634 JVM assertions, 21 Rust cases, Clippy,
Debug/Release and source/localization checks pass. See docs/sight.md. Scene recognition, sustained
rate/thermal and other-hardware acceptance remain open; no automatic repetition or broad matrix.
This supersedes the earlier Sight deferrals below. Push stays deferred. The user subsequently
requested pushing both repositories and publishing new versions: SDK 0.1.1 and Android Preview 3
(versionCode 3). Pin the published SDK commit and retain the same public package/signature; publish
only the generic Pocket.apk and its checksum, never the local legacy/vendor artifacts.

Rust protocol adoption (2026-11-15): the user's completion gate includes actual Android integration
and E2 acceptance. `:protocol` builds the pinned pocket-rs Java/JNI binding; the former Java endpoint,
Chat request/framing, Ambient input/output and retry reducers delegate to Rust. Core live validation
also runs there. Keep Android network/SQLite/resource/foreground-service ordering in existing owners;
never also drive Rust's aggregate Session as a second sender. The current public-identity E2 check
passes 16 Rust/ART plus 22 Try-only wiring assertions and a separate 15 screen-off assertions; no
capture or real-room test messages. Preserve pairing/node/data and do not repeat unrelated matrices.
Pinned-source builds and remote SDK/app CI pass. The final documentation update changes no
executable source; see docs/rust.md and the SDK status/release for retained migration boundaries.

Lockscreen playback fix (2026-11-15): Activity backgrounding must not stop an already-started
spoken reply or local read. RoomPlayback/AmbientOutput retain reception through chunk gaps and
matching completion duration; native drain is separate. PocketSessionService uses mediaPlayback
with a playback-only partial wake lock and a localized stop action, then releases idle work.
The old APK failed the actual API35 screen-off probe; the fixed generic APK passes its 15 bounded
assertions, plus 2,622 JVM assertions (seven new), localization/source checks and Debug/Release
builds. No microphone, room sends or broad matrix. The user subsequently reconnected E2 and
explicitly requested migration to the public package: the old app was uninstalled and the fixed
signed `dev.openduo.pocket` APK was installed and opened. The user subsequently completed fresh Tailscale login and pairing; the room is Online. Never reuse
retired credentials. E2 screen-off acceptance now passes the targeted check above; do not turn this handoff into an unrelated device/service regression.

Current milestone (2026-11-15): continue splitting by actual ownership until further splitting
would add forwarding, duplicate state or conceal necessary coordination. Composer/HistoryPager own
pure composition/pagination, RoomLink owns live generations/queues/final controls, TailnetSession
owns authorization intent, AttachmentTransfers owns file-transfer jobs, Microphone and RoomPlayback
own Android audio lifetimes, and ConversationComposer owns editor-only View state. Keep policy and
transaction ordering in PocketController. Audit remaining owners in docs/architecture.md; do not
split cohesive reducers, transactional SQLite or page renderers solely for line count.
The ownership audit is complete. The final generic Debug and local original-package E2 Release
builds pass, alongside 2,615 JVM assertions (38 new), source/localization checks (646 entries,
143 production Java sources) and the final E2's 24-assertion Try-only coordination check.
That check includes brief real capture cancelled without send, prepared attachment transfers,
silent playback, editor acknowledgement and reconnect. The original room returns Online with
pairing/composer retained. The local legacy APK is installed; published Preview 2 is unchanged.
These checks need not be repeated for documentation-only changes.

Previous ownership milestone: extract outbox HTTP/retry/cancellation into pure core ownership;
keep SQLite admission, foreground-service gating and room effects in app. Preserve cancellation
before dispatch, immutable idempotent bodies, monotonic voice/Passport budgets and live-first
settlement. Read-aloud stream identity/framing/cancellation belong to SpeechReader; its worker
alone creates/releases ReadAloudOutput's decoder, while the controller observes playback drain
and owns audio focus. Do not add empty modules or forwarding Host interfaces to reduce controller line count.
Use relevant JVM contracts/builds and, only if needed, a short Try-only coordination check.
Do not rerun accepted human speech, notifications or the historical device matrix for this refactor.
Implemented in OutboxSender, SpeechReader and ReadAloudOutput; see docs/architecture.md.
Relevant JVM/build checks and the original-package E2's 14-assertion Try-only coordination check
pass. The installed local array build restores the original room Online; no actual capture or
room send occurred. Retain this evidence without repeating it for documentation-only changes.

Foreground input milestone (2026-11-15): validate architecture flexibility through foreground input and
voice-note ownership. E2 is a replaceable example, not the objective. A new binding/input device
must not require a room-business fork; a device adapter supplies defaults, and users can remap
received keys without source changes. Touch,
physical keys and remotes share application intents; device adapters provide default bindings.
E2 is the concrete example (REC toggles voice, MARK cancels recording or stops playback).
Keep D-pad/confirm/back navigation available, with editable bindings and visible focus.
Implemented in pure `:input`, Android `app/input` and owned `:core/VoiceNotes`; see `docs/input.md`.
The final original-package E2 check passes 27 bounded assertions using injected keys, real array
capture and Try-only sends, restoring the actual room Online. Physical key presses and target
speaker/remote hardware remain unaccepted. Do not repeat that check without a relevant change.
Do not intercept system navigation keys. Its bounded input check is
historical evidence, not a requirement for subsequent unrelated controller extraction.

Passport protocol milestone (2026-11-15): protocol 1.3 and Android integration are implemented
against iOS `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. Sixty vectors generated by unmodified
upstream code pass on JVM and E2 ART, alongside Unicode conformance; Release builds. See
`docs/passport.md` for regeneration and limits. Physical pairing/press/reply/background wake
remain unaccepted; do not take over E2Studio's accessory connection. Keep protocol state in
`:core`, platform BLE in app, and UI separate. Passport is no longer deferred. Sight and push
remain deferred. Do not turn the retained hardware gaps into an unsolicited test matrix.

Current milestone handoff: actual microphone-to-room speech/audible replies and delivery to an
explicitly selected test session have bounded acceptance below. On 2026-11-15 the user explicitly
deferred Sight. Retain its implementation and remaining real negotiation/uplink check for later;
do not poll releases, start Sight checks or change the room service unless the user resumes it.
The multi-device architecture and public Preview 2 are already delivered. The user explicitly narrowed testing:
run necessary builds and relevant contracts/short representative checks; do not expand rotation,
accessibility or overnight matrices. Earlier acceptance plans are historical evidence, not a mandate
to keep testing. Complete 1:1 parity is a future comparison against iOS
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`, not the release gate.

Actual session-notification receipt passed on the original-package E2 on 2026-11-15: one
correlated delivery result and exactly one marked recipient row. The dedicated silent keepalive
test job was subsequently archived. Preserve the private once-only journal; do not repeat that
successful send without a relevant new failure/change. On the same date, one actual human
hold-to-talk turn was recognized and delivered, and the user confirmed receiving the audible
reply. The displayed recognition had a wording error. This accepts that bounded path on the local
original-package E2 array build, not exact ASR accuracy, full hands-free ambient use or the public
framework-audio APK. Do not repeat it without a relevant failure/change. Sight is deferred by the user.

The user confirmed on 2026-11-15 that no new room-service release is available. Actual Sight
awaits a released media-capable service; do not assume the user missed an available update.
Speech/audible replies and scoped session notifications are independent of that Sight release.

Support general Android hardware, including future display-equipped smart speakers. E2 ships as an
adapter example. Keep device/OEM decisions in `:device:e2` or future adapter modules, window policy
in `:ui-policy` and `app/ui`, and Android-independent business/protocol in `:core`. The default
adapter uses Android framework audio. Never commit vendor firmware/model source files. The generic
APK must remain free of them; the user explicitly authorized their inclusion in the public E2 APK.
Push (including crypto/identifiers/ledger) remains explicitly deferred.

- `PocketController` owns mutable room state on one executor; views observe snapshots. Do not read
  window geometry or select device models in business state. Hardware adapters never own room state.
- Separate bounded networking, capture and rendering queues. Acquire one shared microphone lease;
  adapters release native resources on the capture owner thread. Preserve netmon resampling on
  network recovery, idempotent outbox identity and transactional rows/outbox/cursors.
- Use current-window/inset policy, system fonts, filled E2Studio-style surfaces, genuine Lucide
  vectors, scrollable controls and bounded content/sheets. Do not change a physical device's density,
  overscan, launcher or another app's settings. Preserve drafts, focus and reading position.
- Use four-space Java (`scripts/format-java.sh`), English source comments and SPDX headers. Retain
  upstream attribution/licences. OpenDuo brand assets are excluded from the code licence. Public screenshots use English;
  the public README and current technical guides use English with Chinese companions. Keep all
  app-owned copy, native controls and device errors within the localization boundary.
  This includes English-authored labels, accessibility descriptions, notifications and QR-pairing
  failures. Follow `docs/localization.md` for copy/language additions; keep user content literal.
- Never track actual hosts/room/device IDs, tokens, keys, private logs/screenshots, recordings or firmware source files.
  Exception: the user explicitly requested public README/device screenshots; only sanitized
  prepared Try content under `docs/screens/` may be committed.
  Generated/private material belongs in ignored `.build/`, `build/` or `dist/`. Licensed prepared
  Try fixtures are distinct from real recordings and pinned by checksum.
- Keep implementation, prepared responses and real-service acceptance distinct in `docs/status.md`
  and `docs/migration.md`. Do not invent a parity percentage.
- On 2026-11-15 the user explicitly requested uninstalling the old physical E2 app
  `top.kuaner.pockete2` and migrating to `dev.openduo.pocket`. That uninstall and the new-package
  update are complete. Earlier original-package checks remain historical evidence. Use the public
  identity for subsequent device work; do not reinstall the legacy app or clear the new app's data.
  Package IDs cannot share AndroidKeyStore: use the user's fresh login and pairing, not copied
  credentials. Fresh public-package login/pairing is complete; retain that node and leave the app available.
- Do not spawn subagents without an explicit user request. No unsolicited full regression, long
  wait, or notification/message to a person. Existing opt-in probes can send real room content;
  read their scope before use and never silently run them for a build/refactor.

Relevant checks:

```sh
python3 scripts/verify-sources.py
scripts/test-host.sh
./gradlew :core:check :app:assembleDebug
(cd bridge/tsbridge && go vet ./... && go test ./...)
```
