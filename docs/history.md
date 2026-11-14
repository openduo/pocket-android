# Answer correlation, history and reconnect settlement

`Message.messageIds` preserves wire `message_ids` independently of a row's display identity.
The list is copied, immutable and written into the existing JSON cache body. Attachment,
presentation, duration, delivery and identity replacements retain it. No SQLite schema upgrade is
needed. Older bodies without this property recover ids from an existing `ans:` key where possible;
an explicitly empty property stays empty. Earlier attachment or renamed answer rows whose ids
were already lost need HTTP/live replay to restore them. Missing ids are not inferred from similar
text or a substring of an input id.

Live rows, today's HTTP history, older-day loading and periodic catch-up use the same controller
admission path. Durable rows/outbox/cursor are committed first; a correlated answer then ends the
waiting turn. Another input's answer leaves the wait active. Log/history cannot end uncorrelated
room speech; an actual matching final/idle frame still can. After `ready` restores a turn, the two
newest cached log days are checked again, including answers beyond the current 200-row UI window
and across midnight.

Provisional and logged answers are reconciled by trimmed text and, when both specify ids, an exact
shared input id. This includes provisional `ans:` rows from earlier builds. Deletion also requires
`seq < 0`: a candidate captured at the start of a batch cannot delete a durable row later admitted
under the same key. Distinct sequenced answers from one turn remain distinct even with identical
text. A late final matching a cached log answer cannot create another bubble or overwrite its
sequence/attachments. A conflicting provisional identity changes without losing its input ids.

Completed tool traces remain session-local, like upstream. Lookup follows explicit correlation,
with upstream's text fallback, so an attachment or renamed log row retains its completion line.
A turn whose final was missed can end from its log row without inventing a missing tool trace.
Restart does not persist traces.

History loading belongs to the current connection generation. Retiring the connection makes that
request's indicator inactive immediately, allowing another load. Old responses/failures cannot
clear replacement loading, show an old error or admit rows into the new view. An old empty response
also stops scanning dates. An HTTP call already in progress can finish under its existing timeout;
this does not add native-request cancellation.

Periodic local notifications use retained ids instead of parsing display identity. Live answers
and later attachment catch-up therefore share the existing local notice key. First-cursor history
remains silent. Immediate Android push is still deferred.

## Verification

```sh
scripts/test-host.sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_HISTORY_ONLY=1 scripts/test-device.sh
```

The host suite passed **226 assertions**, including comma-containing ids, attachment mapping,
immutable replacements/copying, legacy-cache compatibility, turn correlation and trace remapping.
The isolated E2 run passed **54 native assertions**:

- Actual production controller/client/SQLite admission with prepared live, day-history and catch-up
  responses. The preceding APK reproduced an attachment reply leaving its working bubble active.
- Today/older/periodic settlement and cached settlement across midnight while 205 newer heard rows
  hide the answer from the initial viewport.
- Final-before-log replacement, duplicate replay, late-final suppression, completion-trace retention
  and explicit ids after native SQLite close/reopen.
- All six admission orders of three same-turn identical-text answers, with whole-batch replay;
  another input's identical-text provisional remains separate.
- Held HTTP requests returning an empty response or failure from a retired connection. The preceding
  loading implementation reproduced the stuck indicator. A replacement starts before the old
  request returns, retains its loading/error state and alone admits its response; the old scan stops.
- The actual Activity stops, a prepared final produces one real `NotificationManager` notice, and
  attachment catch-up does not create another. First-cursor history is silent. Test notices and
  receipt changes are removed/restored.
- The actual paired room, Online state, composer and empty outbox are restored without a real send.
  Fixtures use a separate SQLite database and a transport with no delegate, upload, capture or speech
  stream. A compact-screen attachment/completion screenshot was inspected.

A broader paired-try run on this APK subsequently passed **163 device assertions**, without
capture, camera or the optional external import UI. It exercises existing database/outbox,
playback, try conversations/tools, attachment, notification, location, live-fault, language/theme
and compact-keyboard paths. Its first attempt failed the existing first-reply log assertion without
row-state diagnostics. The same APK then passed after adding diagnostic output to that assertion;
neither its condition nor its deadline changed. The first failure's cause remains unlocated. This
successful run does not establish consistently reproducible full-suite acceptance, and the full
capture/camera/import run was not repeated for this change.

These are native application/storage/UI/notification checks with prepared service outcomes and
connection-generation changes. They do not establish actual-server interruption between final/log,
physical handover during history, force-stop inside this history path, every legacy unsequenced
identity case, prolonged standby or full accessibility. Natural microphone recognition, audible
playback and real Sight/visit/session-notify acceptance remain separate in `migration.md`.
