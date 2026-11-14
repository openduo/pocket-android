# Compact controls and accessible recording

The current E2 viewport is approximately 259 dp wide. Primary conversation controls, import
cancellation, jump-to-latest and the shared sheet close action use actual 48 dp layout bounds.
Setting a minimum on the shared icon helper did not override earlier explicit 44 dp parent
constraints; those constraints are now removed. The hold control can grow vertically when its
label wraps. The editor retains the remaining width and its existing keyboard/draft handling.

Shared text, icon and row actions expose an Android Button role. Bottom navigation exposes the
selected page and hides its duplicate child label from accessibility. The recording control also
exposes a button role and named actions:

| Input | Behaviour |
| --- | --- |
| Ordinary touch | Hold to capture; move outside to arm cancellation; release to send or cancel |
| Keyboard / accessibility click | Activate to start; activate again to finish and send |
| Accessibility Cancel recording action / Escape key | End the current recording without sending |
| View detach / window focus loss | Cancel recording and pending permission intent |

These inputs use the same controller, input lease and durable outbox. Keyboard and switch-style
activation no longer require Android touch exploration to be enabled. A permission callback belongs
to one activation generation: an older grant cannot start a later intent, and a grant after view
detach cannot acquire the microphone. The current ordinary touch gesture stays separate from click
activation, avoiding an accidental new recording after release.

## Verification

```sh
ANDROID_SERIAL=YOUR_E2_SERIAL POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
```

The targeted run passed **27 native assertions**, including the wrapper's room-restoration checks.
It uses the actual Activity, DPAD keyboard injection, accessibility node/action APIs, E2 microphone
and JNI Opus in the disposable try room. The preceding keyboard handler reproduced activation
without recording. In the fixed run a second keyboard activation sends captured Opus into the
prepared local transport; the native accessibility cancel action releases capture without another
note. Output stays muted locally without changing system volume. The permission callbacks are
prepared by a host adapter; no actual permission dialog or global accessibility setting is changed.
The suite verifies old-grant/detach rejection after a barrier on the controller's state owner.

Actual accessibility nodes verify button/selection semantics, and measured visible rectangles
verify 48 dp primary voice/text/attachment controls and sheet close. A wrapping sheet action
activates once and dismisses. The probe is also included in the broad paired-try UI run; without
the capture option it checks semantics, geometry and sheets only. E2Studio recording/work must be
idle before capture tests. Generated screenshots remain in ignored build output.
The same APK passed the complete **336-assertion** capture/camera/import/paired-try regression and
the separate **17-assertion** native background-upload regression. Compact keyboard and 1.35-font
conversation screenshots were inspected. A subsequent test-only cleanup stops local playback
before restoring mute state; all 27 targeted assertions pass with this cleanup as well.

This is native action/geometry evidence. A full TalkBack or Switch Access session, focus traversal
across every page/bubble/attachment, all font/keyboard combinations, actual permission-dialog
lifecycle and natural acoustic recognition remain unaccepted. Escape has an implemented cancel
handler; the native capture checks specifically exercise DPAD click and accessibility cancellation.
