# Pocket E2 engineering rules

The product goal is a **1:1 functional migration** of `openduo/pocket-ios`, pinned initially to
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`. Milestones do not reduce this goal. Do not mark a
placeholder, prepared reply, protocol test or compiled UI as an end-to-end feature completion.
Maintain `docs/migration.md` with explicit implementation and verification gaps.

The user deferred external Passport Bluetooth integration on 2026-11-14. Prioritize standalone E2
use and omit Passport entry points from normal conversation/settings flows. Retain the existing
implementation for later; external Passport hardware acceptance is not a current release blocker.
This exception does not reduce the remaining 1:1 migration goal.

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
