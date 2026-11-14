# Pocket Android engineering rules

Current milestone: finish actual microphone-to-room speech and audible replies, real Sight
negotiation/uplink, and delivery of a notification to an explicitly selected test session.
The multi-device architecture and public Preview 2 are already delivered. The user explicitly narrowed testing:
run necessary builds and relevant contracts/short representative checks; do not expand rotation,
accessibility or overnight matrices. Earlier acceptance plans are historical evidence, not a mandate
to keep testing. Complete 1:1 parity is a future comparison against iOS
`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`, not the release gate.

Actual session-notification receipt passed on the original-package E2 on 2026-11-15: one
correlated delivery result and exactly one marked recipient row. The dedicated silent keepalive
test job was subsequently archived. Preserve the private once-only journal; do not repeat that
successful send without a relevant new failure/change. Natural speech/audible output and Sight
remain pending independently.

The user confirmed on 2026-11-15 that no new room-service release is available. Actual Sight
awaits a released media-capable service; do not assume the user missed an available update.
Speech/audible replies and scoped session notifications are independent of that Sight release.

Support general Android hardware, including future display-equipped smart speakers. E2 ships as an
adapter example. Keep device/OEM decisions in `:device-e2` or future adapter modules, window policy
in `:ui-policy` and `app/ui`, and Android-independent business/protocol in `:core`. The default
adapter uses Android framework audio. No vendor firmware/model may enter public source or APKs.
Push (including crypto/identifiers/ledger) and external Passport remain explicitly deferred.

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
- Never track actual hosts/room/device IDs, tokens, keys, private logs/screenshots, recordings or firmware.
  Exception: the user explicitly requested public README/device screenshots; only sanitized
  prepared Try content under `docs/screens/` may be committed.
  Generated/private material belongs in ignored `.build/`, `build/` or `dist/`. Licensed prepared
  Try fixtures are distinct from real recordings and pinned by checksum.
- Keep implementation, prepared responses and real-service acceptance distinct in `docs/status.md`
  and `docs/migration.md`. Do not invent a parity percentage.
- The old physical E2 app `top.kuaner.pockete2` retains its login/pairing. Do not uninstall, clear or
  replace its data while developing `dev.openduo.pocket`; package IDs cannot share AndroidKeyStore.
  The user explicitly requested testing on the original package on 2026-11-15. A signed in-place
  legacy-identity build may update it while preserving its storage names, key alias and tailnet.
  Do not require another node or pairing for these tests.
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
