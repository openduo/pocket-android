# E2 viewport and interaction rules

Reference: the local E2Studio design rules and Pocket's `docs/design/native-app.md`.

The reusable window-size, typography and geometry policy is documented in [`ui.md`](ui.md). This
file records the E2-specific interaction and hardware constraints; page code must use the policy's
tokens instead of copying the current viewport dimensions.

The connected device reports a physical 480×800 px panel, density override 210, and left/right
overscan of 70 px each. The **application viewport is 340 px (~259 dp)**. Do not confuse these px
with dp, assume a phone-sized canvas, or change the user's display settings to make a layout fit.

- Warm dark/light semantic tokens, system fonts, filled borderless surfaces, actual Lucide vectors.
- Shared icon controls have 48 dp minimum touch targets and expose an accessibility button role.
  The 22-case window/font matrix, actual TalkBack page traversal and legacy source Switch Access
  scans now pass under [the documented conditions](acceptance.md); remaining dynamic-content and
  system-dialog accessibility coverage is listed separately.
- 48 dp controls, page navigation in the header and composer below the thread. Internally scrolling
  sheets are capped at the adaptive sheet height (82% of a normal window and never taller than a
  short window). Measured Android-8 insets keep the
  composer above the keyboard. Bottom controls can grow with the font scale. Detached windows stop
  their inset callbacks; returning from a dialog reapplies the window's immersive flags. E2 firmware
  retains white system icons, so its status/navigation surfaces stay dark in both app themes.
- Page instances persist while navigating. Updates are keyed by message id; composer/focus and
  older reading position survive new rows. Prepending history preserves the first visible row.
- Hold-to-talk handles raw touches; leaving any edge arms cancel, returning resumes. Capture and
  encoder lifetime belong to the controller. Haptic starts only when input actually starts.
- Actual microphone RMS drives the Ambient meter. The shared voice-note panel uses a simple
  recording animation and timer.
- Ambient capture and held notes share one input. Audio focus loss keeps an interrupted note as a
  failed draft, pauses ambient capture, and drops playback. Foreground/seat metadata cannot hide an
  ongoing focus loss. New muted speech acknowledges immediately without delaying the room clock;
  ending ambient mode restores its session-scoped output mute. Camera stops on backgrounding.
- Geographical selection appends editable text on the state owner. Sight streaming starts only
  after a server ask, capture-master ownership, foreground state and explicit opt-in/permission.
  Still photos use a separate explicit native-camera action and its granted output URI.
- Location selection keeps the existing pin usable when providers are unavailable or disabled.
  Legacy GPS asks for precise permission; supported providers can use coarse fixes. Only fresh
  cached fixes are shown. Search and location waits have 12/15-second fallbacks; hidden pages stop
  location updates and invalidate late geocoder results. Searches cannot enqueue unbounded work.
  Actual E2 coordinate insertion and the disabled-provider fallback passed a separate seven-assertion
  probe; this does not establish real GPS, tile, geocoder or visit-monitor reliability.
- Arrival settings distinguish missing permission, disabled/missing sources, unavailable place
  lookup and waiting for another app's passive fixes. The status row opens the relevant native
  settings without changing system location. Checks are cached and published in the immutable
  controller snapshot. Arrivals reject stale/out-of-order fixes and late work after off/on, missing
  permission or expiry. See `location.md` for the broadcast budget and remaining visit acceptance.
- External shares enter the composer as a complete ordered batch, without sending. Focused editors
  apply acknowledged draft updates and preserve typing that overlaps an incoming append; room/send
  resets invalidate stale editor changes. Attachment chips expose horizontal overflow and use the
  adaptive name bound, thumbnail and 48 dp removal target. See `imports.md`.
- The rear camera has an actual colour viewfinder from the same Camera2 image stream, bounded by
  the adaptive viewfinder height and 5 fps. Only a visible rear viewfinder requests those pixels. Front-camera
  preview is hidden, and a one-camera E2 has no unavailable lens-switch button. Physical rotation
  restarts the encoder with a key frame and the swapped dimensions, preserving uplink numbering.
- Occupied cameras are observed before opening; the app waits without preempting another owner.
  Releasing the camera resumes from a key frame while opt-in is still on. Temporary native failures
  use a finite backoff; unsupported parameters wait for another ask/lens. Switching off, leaving
  the foreground or ending the ask cancels recovery and unregisters camera observation.
- Camera state has a separate wrapping line above the bottom camera controls, also visible while
  the full conversation is expanded. A server refusal keeps its explanation after the controls
  disappear; hearing, held-note and heat details cannot overwrite it. Unknown reasons are literal
  server content inside translated application copy. An ordinary stop or socket replacement clears
  the explanation. The compact room/transcript layouts and native refusal/re-ask recovery passed
  a separate 29-assertion targeted run using prepared camera control frames.
- Session lists show source/activity and fold each group after five rows. Notifications name their
  recipient and keep separate drafts. Drafts and expanded groups survive theme/configuration
  recreation without retaining a View or Activity; replacing the room discards that UI state.
- Application copy resolves through the selected language catalog. Room names, user messages,
  server/tool content, filenames and device product names stay literal. See `localization.md`.

Device instrumentation checks the actual narrow viewport, native capture/playback/encoding and
composer/IME visibility. Dark/light conversation screenshots and a scoped 1.35 font-scale view were
inspected at the actual viewport without changing system preferences. The current real rear-camera
preview was almost black; a recognisable-scene aiming trial and physical posture remain in the
acceptance matrix. The later API 35 matrix covers all eight pages at the listed window/font
conditions; it does not replace real service Sight or E2 camera aiming acceptance.
