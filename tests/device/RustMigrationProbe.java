// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.qa;

import android.content.Context;

import dev.openduo.pocket.audio.PacketStream;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.protocol.NativeCore;

import java.util.Arrays;

/** Bounded migrated-protocol and application-wiring check; no capture or real-room sends. */
final class RustMigrationProbe {
    private final Probe probe;
    private int assertions;

    RustMigrationProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    String run(Context context) throws Exception {
        check(NativeCore.abiVersion() == 1, "installed Rust ABI is callable on ART");
        check(
                NativeCore.call(NativeCore.PROTOCOL, 0, 0, new byte[] {123}, null).status != 0,
                "malformed JNI input returns an owned structured error");
        Endpoint.PairingLink pairing =
                Endpoint.PairingLink.parse(
                        "duoduopocket://pair?host=example.test&tls=1&code=prepared%2Bcode");
        check(
                pairing.code.equals("prepared+code") && pairing.endpoint.tls,
                "pairing uses Rust parsing");
        byte[] packet = {(byte) 0xb8, (byte) 0xff, (byte) 0xfe};
        try (AmbientInput input = new AmbientInput();
                AmbientOutput output = new AmbientOutput();
                PacketStream stream = new PacketStream()) {
            input.socketOpened();
            check(
                    input.meta(
                                    Json.object("conn", "prepared", "media", 1, "role", "peer"),
                                    "fixture")
                            .isEmpty(),
                    "hello waits for actual encoder readiness");
            check(
                    input.packet("fixture", true).size() == 2 && !input.sendsAudio(),
                    "peer is not allowed to send capture");
            input.takeover("fixture");
            check(!input.sendsAudio(), "takeover waits for server master grant");
            input.meta(Json.object("role", "master"), "fixture");
            check(
                    Arrays.equals(
                            input.uplink(packet), new byte[] {1, packet[0], packet[1], packet[2]}),
                    "Rust media uplink framing");
            check(
                    Arrays.equals(input.downlink(input.uplink(packet)), packet),
                    "Rust media downlink framing");
            input.press("phone", true);
            input.press("passport", true);
            check(
                    input.press("phone", false).isEmpty() && input.pressMuted(),
                    "overlapping press owns microphone restoration");
            check(
                    input.releaseMicrophone().optBoolean("on"),
                    "final unmute remains due before socket teardown");
            input.press("passport", false);
            check(
                    stream.push(new byte[] {3}).isEmpty() && stream.partial() == 1,
                    "native stream retains partial prefix");
            check(
                    Arrays.equals(
                            stream.push(new byte[] {0, packet[0], packet[1], packet[2]}).get(0),
                            packet),
                    "native stream reconstructs packet");
            stream.finish();
            output.speech("c-prepared");
            String oldKey = output.playbackKey();
            output.received(16000);
            output.completed(
                    Json.object("state", "done", "speech_id", "c-prepared", "played_ms", 2000));
            check(output.receiving(), "completion cannot truncate late network chunks");
            output.received(16000);
            check(!output.receiving(), "decoded completion remains independent of hardware drain");
            output.speech("c-next");
            check(
                    output.played(oldKey, 2000, true) == null && output.playedMs() == 0,
                    "stale hardware receipt cannot credit new declaration");
            check(
                    output.played(output.playbackKey(), 100, true).optLong("ms") == 100,
                    "absolute hardware position maps to Rust playback report");
        }
        String coordination = new OwnerCoordinationProbe(probe).run(context, false);
        return "PASS "
                + assertions
                + " Rust/ART migration assertions; "
                + coordination
                + "; screen-off playback is a separate short instrumentation invocation";
    }
}
