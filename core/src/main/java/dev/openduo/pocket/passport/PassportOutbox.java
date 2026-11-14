// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import java.util.*;

/** One fragmented message in flight; newer queued partials replace the same reply's older text. */
public final class PassportOutbox {
    private static final int MAX_QUEUED_BYTES = 512 * 1024;

    private static final class Message {
        final int type;
        final byte[] payload;
        final boolean quiet;
        final Runnable handed;

        Message(int type, byte[] payload, boolean quiet, Runnable handed) {
            this.type = type;
            this.payload = payload.clone();
            this.quiet = quiet;
            this.handed = handed;
        }

        boolean partial() {
            return type == PocketProtocol.REPLY && payload.length >= 5 && payload[4] == 0;
        }
    }

    private final List<Message> pending = new ArrayList<>();
    private Message active;
    private byte[][] fragments;
    private int index, bytes;

    public boolean offer(int type, byte[] payload, boolean quiet, Runnable handed) {
        if (payload == null || payload.length > PocketProtocol.MAX_MESSAGE) return false;
        Message next = new Message(type, payload, quiet, handed);
        int replace = -1;
        if (next.partial())
            for (int i = 0; i < pending.size(); i++) {
                Message item = pending.get(i);
                if (item.partial()
                        && PocketProtocol.u32(item.payload, 0) == PocketProtocol.u32(payload, 0)) {
                    replace = i;
                    break;
                }
            }
        int size = bytes + payload.length - (replace < 0 ? 0 : pending.get(replace).payload.length);
        if (size > MAX_QUEUED_BYTES || replace < 0 && pending.size() >= 256) return false;
        if (replace < 0) pending.add(next);
        else pending.set(replace, next);
        bytes = size;
        return true;
    }

    public byte[] next(int mtu) {
        if (active == null) {
            if (pending.isEmpty()) return null;
            active = pending.remove(0);
            fragments = PocketProtocol.fragments(active.type, active.payload, mtu, active.quiet);
            index = 0;
        }
        return fragments[index];
    }

    public void acknowledge() {
        if (active == null) return;
        if (++index < fragments.length) return;
        Message done = active;
        bytes -= done.payload.length;
        active = null;
        fragments = null;
        if (done.handed != null) done.handed.run();
    }

    public void clear() {
        pending.clear();
        active = null;
        fragments = null;
        index = bytes = 0;
    }
}
