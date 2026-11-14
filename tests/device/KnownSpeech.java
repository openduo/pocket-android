// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import dev.openduo.pocket.audio.OpusDecoder;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.PacketStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Canonical synthetic acceptance audio with the shipping capture path's 20 ms packet geometry. */
final class KnownSpeech {
    private KnownSpeech() {}

    /** Synthesis may use 60 ms packets; both native pacing and voice durations use 20 ms ticks. */
    static byte[] nativePackets(byte[] synthesis) throws IOException {
        PacketStream stream = new PacketStream();
        List<byte[]> packets = stream.push(synthesis);
        stream.finish();
        if (packets.isEmpty()) throw new IOException("Known speech synthesis is empty");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        short[] frame = new short[320];
        int used = 0;
        try (OpusDecoder decoder = new OpusDecoder();
                OpusEncoder encoder = new OpusEncoder()) {
            for (byte[] packet : packets) {
                short[] pcm = decoder.packet(packet);
                for (short sample : pcm) {
                    frame[used++] = sample;
                    if (used == frame.length) {
                        write(body, encoder.encode(frame));
                        used = 0;
                    }
                }
            }
            if (used > 0) {
                Arrays.fill(frame, used, frame.length, (short) 0);
                write(body, encoder.encode(frame));
            }
        }
        return body.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] packet) throws IOException {
        out.write(packet.length & 255);
        out.write(packet.length >>> 8);
        out.write(packet);
    }
}
