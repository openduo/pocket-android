# Adaptive UI and maintainability

English · [简体中文](ui.zh-CN.md)

The native View implementation uses the current window size, WindowInsets, native View state and scrollable layouts. Pages fill the available window; message bubbles and sheets keep bounded reading widths, while forms reflow with the page. E2, phones and tablets share the same business pages. E2's system-bar quirk is declared by `:device-e2` and consumed through a common policy.

| Layer | Owner | Responsibility |
| --- | --- | --- |
| Business/protocol | Controller, pure reducers/codecs, SQLite, transport | Room, drafts, outbox and resource coordination; no window measurements |
| Input intents | `:input`, `app/input`, device defaults | Shared touch/key/remote actions, local bindings and recording source ownership; no page/model branches in room state |
| Pure window policy | `:ui-policy` / `AdaptiveMetrics` | Current window dp, font scale, width/height classes and named design tokens |
| Window containers | `AdaptivePage`, `AdaptiveColumn`, `InsetsFrame` | Full window canvas, short windows, IME and system insets |
| Transient UI state | `PageStates`, stable resource IDs | Form, scroll, focus and selection restoration; durable drafts remain controller-owned |
| Shared controls | `Ui`, `Sheets`, `ViewportNavigation` | Typography/colors, growing controls, 48dp minimum actions, bounded sheets and gesture alternatives |
| Pages | `ui/pages` | Render snapshots and forward actions; no direct physical-panel measurement |

| Current window | Navigation | Page composition |
| --- | --- | --- |
| Under 320dp wide | Page controls | No persistent navigation. Chat opens a focusable page menu; Ambient and Settings expose a direct return control. |
| 320–839dp, except short landscape | Bottom bar | Full-width canvas; each page may use one or two panes if its content fits |
| At least 600dp wide and under 480dp tall | Icon rail | Full-width canvas; onboarding can put choices beside the introduction from 600×320dp |
| At least 840dp wide | Side rail | Full-width canvas; settings and workspaces split only when their usable panes fit after navigation and margins |
| Large text (1.3× or above) | Icon rail when applicable | Workspace panes require a larger remaining reading area |

The navigation shape depends on window width and height. Page composition then uses the width left after the rail and page margins: onboarding needs space for its introduction and choices; pairing for a link panel and manual fields; settings for two usable groups; workspaces for context and a readable main pane. Short landscape onboarding uses a smaller introduction footprint. A new device with the same available window receives the same layout, without a device rule.

Single-pane onboarding keeps introduction and choices in one centered, scrollable group, so a tall window does not stretch the introduction into a giant card or push actions to the bottom. In short or wide windows the choices sit beside the introduction, with independent overflow for the text. `BoundedColumn` fills the available form width up to a readable maximum; `ReadableColumn` still lets message bubbles wrap to their own content. Chat places a room context pane beside the transcript when it fits. Ambient shows a bounded control pane beside the transcript; Sessions shows list and detail together; Settings distributes groups across two columns. The map uses the window. Messages and sheets keep readable widths without shrinking the page.

Parent-window measurement determines height. API30+ uses systemBars/displayCutout/ime insets and reserves the navigation gesture area even when the bar is visually hidden; API26–29 uses the visible region of the current window with the E2 top-inset fallback. No physical `getRealSize()` calculation decides IME layout.

Short windows or IME visibility collapse auxiliary chrome and keep the editor reachable; restoring space restores the presentation without discarding draft/selection. Forms and composer footers can scroll. Controls grow with text. Stable IDs allow native per-page state to survive recreation.

Native D-pad/OK/Back focus navigation is shared by pages and sheets. `Ui` draws filled focus states, `FocusNavigation` retains window-local page focus, and map/image `ViewportNavigation` uses explicit pan mode so arrows can leave the viewport. [Input architecture](input.md) describes remapping and the E2 example.

The under-320dp mode is designed for portrait, narrow displays such as the E2. It preserves the message viewport by removing the always-on bottom bar and moving the optional voice/text switch into the attachment menu; settings and other destinations remain in a D-pad reachable page menu. The physical voice key continues through the shared input mapping. Onboarding uses a compact brand row; pairing puts the link, scan/read actions, manual fields and submit in a usable order. Short single-pane pairing and location keep their primary actions visible below scrollable content, then free the form area while the keyboard is open. Wide pairing keeps submit next to the fields. The rules use current window dp rather than a device identifier; they do not change microphone or room behavior. E2 itself is portrait-only; landscape layouts serve other Android windows.

When adding layout behavior, define a purposeful token in `AdaptiveMetrics` and expose it through shared controls. Full page width and readable text width are different choices. Reflow content at the window's available width and height; do not cap the entire page to make text readable. Preserve business values in their owner; restored Views must not overwrite newer controller drafts. Keep OEM matching in hardware adapters.

`scripts/check-architecture.py` enforces these boundaries. The emulator matrix saves real screenshots for onboarding, chat, ambient, settings, sessions, location, pairing, IME and sheets; its viewport assertions check that page content spans the current window and navigation changes shape. Visual inspection remains necessary: the earlier geometry suite passed while the tablet UI was still a narrow centered column. Prior accessibility and device reports belong to their identified APKs; see [historical acceptance](acceptance.md). A new physical display device still needs its own input/audio acceptance.

Current signed generic APK (`54da6d7ad2577fd9bb07179eb20ac87b7c24b3da0c03fbd3954e8fbfc7812bfc`) passed source/localization checks, 2,642 host assertions, Release build and the layout-only API35 emulator case at 600×320dp. That case checks page geometry and takes screenshots without opening editor keyboards; it shows the compact side-by-side onboarding with all three actions visible. The immediately preceding signed APK (`38d605fbf35551c94076ac053ee9cc992f30cdca905e095bc0c59650a761c0da`) passed full page/focus checks at 259×545dp Chinese/light, 360×740dp, 600×960dp, 720×900dp, 740×360dp, 840×600dp and 1280×800dp. Screenshots show the single-pane group, short landscape choices, pairing fields and two-column settings. The final executable change only widens the short-window onboarding rule to 600×320dp; those seven full cases were not repeated for the final APK. The full 600×320dp probe separately reported one session editor keyboard visibility fault, so it is not counted as a passing full case. Evidence remains under ignored `.build/acceptance/ui/`; the paired E2 was not updated and still needs its local array APK.

An earlier layout APK (`a58e337ac2db16dc6d3622df69a00d02743a615cff243589ac9a2c607ec16cbf`) was installed in place on an AIUniBOX E11. Its 1280×720 screen showed full-window onboarding, Try conversation and Ambient layouts, and injected D-pad/OK navigation reached the Try and Ambient pages. The latest layout revision above has not been installed there; see [E11 device findings](devices.md#bringing-up-a-smart-speaker).

An earlier generic APK (`e73b7bc46794b8d4cc8cb6c448d07e13ad9ec7e3bc038480eb7e9af5310811c5`) passed nine cases and 4,117 checks, plus 20 rotation and eight freeform checks. Those results do not attest to the latest source. The earlier local E2 array APK (`fd6831d5227947f1f99641bb352865d6ff69cafb53d5442c4f14182fec784de3`) predates the final layout changes and was not installed for this work.
