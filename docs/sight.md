# Sight with the released room service

English · [简体中文](sight.zh-CN.md)

Sight was resumed at the user's request on 2026-11-15 after the room service update. The current
Preview 3 source and public-package APK pass actual camera negotiation and video uplink.
The release pins pocket-rs 0.1.1 for matching media-v1 audio handling.

## Wire and ownership

The reference is ambient `0.4.0-rc.2`, source
[`4b030444afda834d7286a0dd687d437dd708dbc1`](https://github.com/openduo/ambient/tree/4b030444afda834d7286a0dd687d437dd708dbc1).
Its [camera protocol](https://github.com/openduo/ambient/blob/4b030444afda834d7286a0dd687d437dd708dbc1/packages/ambient-protocol/src/camera.ts)
confirms `media=1` on `/live`. The channel-to-cerebellum wire major 4 is a separate link; the
Android client still uses edge media version 1.

Only the **uplink** is kind-framed: `0x01` precedes an Opus packet and `0x02` precedes a complete
H.264 Annex-B access unit, its sequence/timestamp and key/rate configuration. Downlink stays one
unchanged Opus packet, even on a media-capable connection. The Rust Ambient owner and granular JNI
input now share this rule; neither strips a first byte or rejects a packet based on its first byte.
The SDK's native loopback server was corrected to model the actual downlink too. Preview 3 pins
the published pocket-rs 0.1.1 commit containing this correction. The ABI and Android resource ownership do not change.

`SightSession` retains the server-owned capture ask, explicit user opt-in, master/foreground gates,
frame bound, numbering, key requests and recovery. `SightCapture` owns Camera2 and MediaCodec on
its camera thread. No still-image fallback, second microphone owner or second Rust aggregate sender
is introduced.

## Encoder orientation

The actual request is 1280×720, H.264 `avc1.42E01F`, 25 fps with a 2 fps floor, 1,200,000 bit/s
and a 2,000 ms key interval. E2's encoder accepts 1280×720 but rejects 720×1280. Its 90-degree
sensor rotation previously made the otherwise supported request fail with `no_h264`.

The generic path first selects an encoder for the upright size. If that orientation is unsupported,
it tries the swapped size allowed by the same request. `:core/YuvTransform` rotates YUV420, fits
the entire upright scene into that buffer and pads the spare area with neutral video black. It
preserves aspect ratio, colour-plane alignment, row/pixel strides and buffer positions. There is
no model-name branch, crop or stretched scene. Padding reduces the scene's occupied pixel area;
the declared decoder size still exactly matches the request or its swap. Ordinary supported
orientations keep the full buffer.

## Recorded checks

- 2,634 actual-JNI JVM assertions pass, including 12 new rotation, aspect-fit, stride/padding and
  media downlink assertions. Rust workspace tests pass 21 cases, and Clippy/rustfmt pass.
- Generic Debug and signed dual-ABI Release builds, Gradle `:core:check`, source boundaries and
  650-entry localization checks pass. Go networking is unchanged.
- The updated `dev.openduo.pocket` APK is installed in place on E2. Fourteen actual-service
  assertions pass: media confirmation, real master/capture offer, first native key frame and five
  transmitted video access units, no server refusal, cleared server `vision_off`, explicit camera
  off, microphone restoration and retained pairing/composer/Online state.
- Microphone packets were replaced with native-paced silence before transport; no actual speech,
  text message or notification was sent. Native camera resources are released after the check.

This accepts negotiation/uplink on the recorded E2 build. Scene recognition by the room, sustained
rate/thermal behavior, external-app eviction and other hardware remain unaccepted. Do not repeat
the accepted check or expand into historical matrices without a relevant change.

The explicit short runner is:

```sh
ANDROID_SERIAL=YOUR_DEVICE_SERIAL \
POCKET_E2_TEST_SIGHT_ROOM_ONLY=1 \
POCKET_E2_TEST_CAPTURE=1 \
POCKET_E2_TEST_CAMERA=1 \
scripts/test-device.sh
```

It reuses the current public package's pairing, refuses to take another capture owner's seat,
restores the composer and reopens the app after instrumentation. Private evidence stays under
`.build/device-tests/sight-room/`; no camera content or actual room identities are committed.
