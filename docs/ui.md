# Adaptive UI and maintainability

English · [简体中文](ui.zh-CN.md)

The native View implementation uses Android window-size classes, WindowInsets, bounded content columns, native View state and scrollable layouts. E2, phones and tablets share business pages. E2's system-bar quirk is declared by `:device-e2` and consumed through a common policy.

| Layer | Owner | Responsibility |
| --- | --- | --- |
| Business/protocol | Controller, pure reducers/codecs, SQLite, transport | Room, drafts, outbox and resource coordination; no window measurements |
| Input intents | `:input`, `app/input`, device defaults | Shared touch/key/remote actions, local bindings and recording source ownership; no page/model branches in room state |
| Pure window policy | `:ui-policy` / `AdaptiveMetrics` | Current window dp, font scale, width/height classes and named design tokens |
| Window containers | `AdaptivePage`, `AdaptiveColumn`, `InsetsFrame` | Column bounds, short windows, IME and system insets |
| Transient UI state | `PageStates`, stable resource IDs | Form, scroll, focus and selection restoration; durable drafts remain controller-owned |
| Shared controls | `Ui`, `Sheets`, `ViewportNavigation` | Typography/colors, growing controls, 48dp minimum actions, bounded sheets and gesture alternatives |
| Pages | `ui/pages` | Render snapshots and forward actions; no direct physical-panel measurement |

| Current window width | Class | Reading column |
| --- | --- | --- |
| <600dp | Compact | Available width, including the E2's narrow viewport |
| 600–839dp | Medium | Up to 640dp, centered |
| >=840dp | Expanded | Up to 720dp, centered |

Navigation follows the same content bound. Sheets fill narrow windows and cap width in wider windows. Parent-window measurement determines height. API30+ uses systemBars/displayCutout/ime insets; API26–29 uses the visible region of the current window with the E2 top-inset fallback. No physical `getRealSize()` calculation decides IME layout.

Short windows or IME visibility collapse auxiliary chrome and keep the editor reachable; restoring space restores the presentation without discarding draft/selection. Forms and composer footers can scroll. Controls grow with text. Stable IDs allow native per-page state to survive recreation.

Native D-pad/OK/Back focus navigation is shared by pages and sheets. `Ui` draws filled focus states, `FocusNavigation` retains window-local page focus, and map/image `ViewportNavigation` uses explicit pan mode so arrows can leave the viewport. [Input architecture](input.md) describes remapping and the E2 example.

When adding layout behavior, define a purposeful token in `AdaptiveMetrics` and expose it through shared controls. Compose existing containers before adding page-specific measurements. Preserve business values in their owner; restored Views must not overwrite newer controller drafts. Keep OEM matching in hardware adapters.

`scripts/check-architecture.py` enforces these boundaries. The current package passed three representative API35 cases: 320×640dp, 1280×800dp and 740×360dp. Earlier broader matrices/accessibility checks belong to separately identified APKs; see [historical acceptance](acceptance.md). OEM/foldable/external-display and every external-operation lifecycle combination remain outside the current verified scope.
