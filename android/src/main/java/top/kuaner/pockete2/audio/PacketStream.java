// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental voice packet framing, bounded to one legal Opus packet between callbacks. */
public final class PacketStream {
    public static final int MAX_PACKET_BYTES = 1275;
    private final byte[] packet = new byte[MAX_PACKET_BYTES];
    private int prefixBytes, length, filled;

    public List<byte[]> push(byte[] chunk) throws IOException {
        List<byte[]> completed = new ArrayList<>();
        for (byte value : chunk) {
            if (prefixBytes < 2) {
                if (prefixBytes++ == 0) length = value & 255;
                else {
                    length |= (value & 255) << 8;
                    if (length < 1 || length > MAX_PACKET_BYTES)
                        throw new IOException("Invalid Opus packet length");
                }
            } else {
                packet[filled++] = value;
                if (filled == length) {
                    completed.add(Arrays.copyOf(packet, length));
                    filled = 0;
                    prefixBytes = 0;
                    length = 0;
                }
            }
        }
        return completed;
    }

    public int partial() {
        return prefixBytes + filled;
    }

    public void finish() throws IOException {
        if (partial() != 0) throw new IOException("Truncated Opus stream");
    }
}
