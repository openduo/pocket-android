# E2 viewport and interaction rules

Reference: the local E2Studio design rules and Pocket's `docs/design/native-app.md`.

The connected device reports a physical 480×800 px panel, density override 210, and left/right
overscan of 70 px each. The **application viewport is 340 px (~259 dp)**. Do not confuse these px
with dp, assume a phone-sized canvas, or change the user's display settings to make a layout fit.

- Warm dark/light semantic tokens, system fonts, filled borderless surfaces, actual Lucide vectors.
- 44–48 dp controls, bottom navigation and composer, full-width internally scrolling sheets capped
  at 82% of screen height. Hide bottom navigation for the IME. Measured Android-8 insets keep the
  composer above the keyboard.
- Page instances persist while navigating. Updates are keyed by message id; composer/focus and
  older reading position survive new rows. Prepending history preserves the first visible row.
- Hold-to-talk handles raw touches; leaving any edge arms cancel, returning resumes. Capture and
  encoder lifetime belong to the controller. Haptic starts only when input actually starts.
- Actual microphone RMS drives the meter. A static placeholder is never presented as live audio.
- Ambient capture and held notes share one input. Audio focus loss keeps an interrupted note as a
  failed draft, pauses ambient capture, and drops playback. Camera stops on backgrounding.
- Geographical selection only appends editable text to the draft. Camera starts only after a
  server ask, capture-master ownership, foreground state and explicit camera opt-in/permission.
- The rear camera has an actual colour viewfinder from the same Camera2 image stream, bounded to
  a 240 px edge and 5 fps. Only a visible rear viewfinder requests those pixels. Front-camera
  preview is hidden, and a one-camera E2 has no unavailable lens-switch button. Physical rotation
  restarts the encoder with a key frame and the swapped dimensions, preserving uplink numbering.

Device instrumentation checks the actual narrow viewport, native capture/playback/encoding and
composer/IME visibility. Dark/light conversation screenshots and a scoped 1.35 font-scale view were
inspected at the actual viewport without changing system preferences. The current real rear-camera
preview was almost black; a recognisable-scene aiming trial, physical posture, more pages and larger
accessibility font settings remain in the acceptance matrix.
