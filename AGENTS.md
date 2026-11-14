# Pocket E2 engineering rules

The current user milestone is to close the agreed P0/P1 implementation, strengthen maintainability
and support general Android window sizes. The user explicitly stopped requiring complete 1:1 parity
for this milestone on 2026-11-14. Keep `openduo/pocket-ios`, initially pinned to
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`, as the future parity comparison. Maintain
`docs/status.md` and `docs/migration.md` with explicit implementation and verification gaps.
Do not mark a placeholder, prepared reply, protocol test or compiled UI as end-to-end acceptance.

The user deferred external Passport Bluetooth integration on 2026-11-14. Prioritize standalone E2
use and omit Passport entry points from normal conversation/settings flows. Retain the existing
implementation for later; external Passport hardware acceptance is not a current release blocker.
Keep its remaining parity gaps for a possible later milestone.

The user also deferred immediate Android push on 2026-11-14. Push transport registration, relay,
receiver, upstream push crypto/identifiers/ledger and fixed-vector validation are outside the
current implementation scope until explicitly resumed. Keep existing local notifications and
periodic catch-up, and record the deferred push gaps alongside Passport in `docs/migration.md`.

The user resumed P0 and P1 work on 2026-11-14: connection reliability, natural voice and full
ambient turns first; then Sight, session notifications, location/arrival, compact-screen
accessibility and failure recovery. Push and Passport remain deferred. Keep real acceptance gaps
explicit when a physical action or unavailable service prevents local verification.

The next acceptance scope is in `docs/acceptance.md`: phone/tablet/landscape/split/multi-window and
font scaling (emulators are permitted), actual TalkBack/Switch Access and focus order, actual
speech/Sight/session notify/visit, and actual identity/network/audio/lifecycle scenarios. Do not
claim these complete from earlier compact-only, synthetic or fault-injection probes. Isolated
emulator display/accessibility settings may be changed and restored by the acceptance harness;
physical E2 display and E2Studio settings must remain untouched.

- Follow E2Studio's filled surfaces, system fonts, real Lucide icons, bottom controls and bounded
  full-width sheets. Use the current viewport, not the physical panel size. Preserve scroll,
  drafts and focus. Do not change device density, overscan, launcher or another app's settings.
- `PocketController` owns mutable room state on its state executor. Views observe snapshots.
  Keep protocol reducers and codecs Android-independent. Networking, capture and rendering have
  separate bounded queues. Acquire one microphone lease; release native resources on their owner.
- Persist rows, outbox transitions and cursors transactionally. Reuse the idempotency key and the
  original surface on retries/relaunch. Distinguish transport failures from HTTP failures.
- Use four-space Java formatting (`scripts/format-java.sh`), English source comments, and SPDX
  headers for project source/scripts. Keep upstream copyright and third-party licences.
- Never track hosts, actual room/device identifiers, tokens, signing keys, logs, screenshots,
  recordings or firmware binaries/models. Generated material belongs in ignored `.build/` or
  `dist/`. Do not copy unrelated local credentials or steal E2Studio's existing BLE connection.
  Licensed upstream prepared try clips/notes are source fixtures, pinned by checksum; they are
  distinct from device recordings and can be regenerated with `scripts/prepare-try-assets.py`.
- Validate behaviour changes using relevant host contracts, Go checks and isolated device probes.
  Device probes may restart **Pocket E2 only**. Microphone probes require other recording/work to
  be idle. Keep acoustic, API, simulated and UI evidence distinct.

Commands:

```sh
scripts/test-host.sh
(cd bridge/tsbridge && go vet ./... && go test ./...)
scripts/build-android.sh
POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 scripts/test-device.sh
```
