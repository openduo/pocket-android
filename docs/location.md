# Location selection and arrival monitoring

Location selection is an explicit composer action. Arrival monitoring is a separate, off-by-default
setting. Neither opens GPS automatically for background monitoring, and neither sends a message by
itself. Arrival reports carry a place name, area and arrival time; they contain no coordinates.
An enabled arrival setting still requires precise location, plus background permission on Android
10 or later. Try mode does not attach real arrival reports to its requests.

## Selection

`LocationPage` owns one visible selection, system-provider callbacks and bounded geocoder work.
Fresh cached fixes must be at most five minutes old. Searches and current-location waits have
12/15-second fallbacks. Hiding the page removes provider callbacks and invalidates late results;
new searches cannot enqueue unbounded work or admit an older search result. Coordinate entry
remains available without a provider and appends editable text to the existing draft.

The real E2 coordinate/fallback run passed seven assertions. The app observed providers
`passive,gps`, with no enabled providers. This proves the available provider names and state;
it establishes neither absent GPS hardware nor a functioning GPS fix. Live geocoding, tiles,
permission combinations and offline behaviour require further acceptance.

## Arrivals

`ArrivalMonitor` subscribes only to passive/network providers. Registration is retained for
available providers while they are disabled, so enabling system location can resume their updates.
Boot, package replacement and location-provider/mode changes request a registration refresh;
foregrounding and changing the arrival setting also refresh it. Android background delivery and
energy restrictions remain platform acceptance work.

Two sufficiently accurate samples within 150 metres can establish a stay of at least three
minutes. Samples require a valid monotonic time, finite coordinates and accuracy of at most 200
metres. Missing accuracy, old/future fixes and duplicate/out-of-order samples do not advance a stay.
A gap exceeding 30 minutes or a changed boot count starts another candidate. A fix from before
the latest opt-in is ignored.

Candidate/ledger mutations share a process-wide lock across receiver/controller monitor instances.
Reverse geocoding runs outside the lock. Turning the setting off clears its candidate and ledger;
off/on changes its persisted generation. A late lookup may commit only while enabled, permitted,
within its work budget and still in the same generation/candidate. An accepted receipt from before
the latest opt-in cannot repopulate a previous place. Normal outbox retries retain their original
request metadata as described in `protocol.md`.

`ArrivalReceiver` has one worker and one pending slot. Overflow finishes its broadcast immediately.
Each asynchronous broadcast has an eight-second finish deadline, including time waiting in the
queue. Expired work cannot store a lookup result. Android's legacy synchronous geocoder cannot be
cancelled directly: an unresponsive call can still occupy the single worker until it returns or the
process ends; additional pending broadcasts remain bounded and finish on their deadlines.

Settings show missing permission, missing/disabled providers, missing geocoder or passive-only
waiting separately. Tapping the explanation opens app permission settings or system location
settings. Capability checks are cached for at most five seconds, invalidated by refresh, and passed
to views through the controller snapshot rather than queried on every UI update.

## Targeted verification

```sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_ARRIVAL_ONLY=1 scripts/test-device.sh
```

The targeted run uses disposable preferences and prepared coordinates, permission decisions and
geocoder outcomes. Its isolated monitor cannot register/remove the real monitor's location
requests. It checks native distance/time/preferences, pending/settlement through recreated monitor
instances, ordering and
accuracy rejection, reboot/opt-in boundaries and late results after off/on, permission loss or
expiry. With the actual E2 providers disabled it also checks the pause explanation under a prepared
granted-permission state, and sends real ordered broadcasts while the actual worker is deliberately
blocked to verify overflow completion and the finish deadline. It changes no system location
settings or real stored places and sends no room message.

These checks do not establish an actual physical visit, GPS/network fix, real reverse geocoder,
background process relaunch or sustained battery behaviour.
