# Application language boundaries

Pocket E2 supports System, 中文 and English. System resolves Chinese locales to Simplified Chinese
and other locales to English, matching the supported upstream language fallback. Changing the
preference recreates the Activity with a scoped Android Configuration, so native controls and
accessibility strings agree without modifying system language. Application notifications and
Passport APP_STATE use the same resolved catalog. The Passport language byte is covered by host
contracts; an external accessory has not yet verified it.

`text/TextCatalog.java` is Android-independent and immutable. Authored Chinese copy is an explicit
key in `android/src/main/assets/i18n/en.json`; `AppText` loads that catalog and resolves the
application preference/system locale. `Ui.t` and `Ui.format` translate application labels and
templates at their binding sites. `Ui.explain` handles exact authored status/error strings and a
small set of anchored numeric error formats, including failures persisted before a language
change. This does not perform substring translation.

User messages, actual room/session names, heard text, tool input/output, attachment names, links,
geocoded places and real device product names bypass application-copy translation. Session sender
labels resolve their names through the live session list, with readable wire-key fallbacks;
known source types have localized labels. Tool counts, dates and coordinate templates use the
selected locale. The English UI catalog adapts upstream `App/Localizable.xcstrings`, with Android
copy added separately; the attribution is in `THIRD_PARTY_NOTICES.md`.

A language switch while trying discards/restarts the disposable prepared room. The restart restores
then re-isolates the real repository without starting its network/service in between. A real
room's rows and composer draft remain stored unchanged. Session notification drafts live only in
that viewer generation: separate recipients have separate drafts, delivery receipts name their
original recipient, and late receipts do not clear newly edited text. A native theme/configuration
recreation retains this UI state without retaining its Activity. Room replacement and try-room
restart advance the viewer generation and discard prior drafts.

`scripts/check-localization.py` runs during builds. It checks duplicate keys, missing explicitly
localized UI copy, domain/hardware status keys, empty English translations and printf argument compatibility.
Host contracts check immutable scopes, language fallback, error formats, literal-content boundaries,
date/coordinate formatting and the Passport language byte. Device probes operate on the disposable
room, switch through the native setting, verify native resource locale, keep Chinese user input
literal, and capture English conversation/settings/session views at the actual ~259 dp viewport.
They restore the app's original presentation preferences synchronously. These checks do not replace
real-room or full accessibility acceptance.
