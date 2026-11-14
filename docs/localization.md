# Application language boundaries

English · [简体中文](localization.zh-CN.md)

Pocket Android supports System, 中文 and English. System resolves Chinese locales to Simplified Chinese
and other locales to English, matching the supported upstream language fallback. Changing the
preference recreates the Activity with a scoped Android Configuration, so native controls and
accessibility strings agree without modifying system language. Application notifications and
Passport APP_STATE use the same resolved catalog. The Passport language byte is covered by host
contracts; an external accessory has not yet verified it.

`:core`’s `text/TextCatalog.java` is Android-independent and immutable. Authored Chinese copy is an explicit
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

`scripts/check-localization.py` runs during builds and CI. It checks duplicate keys, missing explicitly
localized UI copy, status keys across every production module (including QR pairing and hardware adapters),
empty English translations, printf argument compatibility and native-resource parity. Directly authored
English labels also need a localization call; brand/protocol labels have a small explicit allowlist.
Host contracts check immutable scopes, language fallback, error formats, literal-content boundaries,
date/coordinate formatting and the Passport language byte. Device probes operate on the disposable
room, switch through the native setting, verify native resource locale, keep Chinese user input
literal, and capture English conversation/settings/session views at the actual ~259 dp viewport.
They restore the app's original presentation preferences synchronously. These checks do not replace
real-room or full accessibility acceptance.

The public README, current status and main architecture/device/UI guides have English entry points and Chinese companions. Public documentation screenshots use English. New hardware statuses must enter the same catalog checks; user content and protocol identities remain literal. To add another locale, extend the catalog/resource boundary and the explicit selector rather than translating live room data. Current shipped languages are English and Simplified Chinese, not an unverified promise of all locales.

## Adding or changing application copy

1. Add the authored key and English value to `android/src/main/assets/i18n/en.json`. Keep complete
   sentences/templates together; use `ui.format`/`TextCatalog.format` for values instead of concatenating
   translated fragments. Preserve formatter argument types and counts.
2. Bind labels, hints, action names and accessibility descriptions with `ui.t` or `ui.format`.
   Services and local notifications use `AppText.get(context)`; status callbacks and persisted app
   errors are translated with `ui.explain` when displayed. For a new dynamic legacy error, add an
   anchored `TextCatalog` format and a focused example to the existing host contracts.
3. Keep hardware adapters independent of Activity locale. They emit stable application statuses;
   the presentation boundary translates them. Product names, server output and user content remain
   literal. Internal logs, protocol fields and programmer diagnostics use English; provide a catalog
   message where an operational error is presented to the user.
4. Native resources belong in `res/values` (English fallback), `values-en` and `values-zh`. Keep
   their resource names aligned. Use the scoped `AppText.context` for native controls rather than
   changing global system locale.
5. Run `python3 scripts/verify-sources.py`. If language/format logic changed, run
   `scripts/test-host.sh` as well. A copy-only change does not require a device regression matrix.

For a new shipped language, extend `TextCatalog`'s resolution/catalog and plural rules, the setting's
allowed values and selector, `AppText`'s native configuration, Android resources and prepared Try
text/audio/files together. Add fallback/format contracts for that language and document its support.
The current two-language implementation is not a general ICU plural/RTL implementation; additional
languages need their own grammatical and layout review before being advertised.

Source identifiers, comments, development tooling, release notes and public documentation use English
as the collaboration language. The README and principal guides also provide Chinese companions.
Historical Chinese acceptance logs are explicitly marked as archives. Translation checks apply to
application-owned copy and leave actual conversation data unchanged.
