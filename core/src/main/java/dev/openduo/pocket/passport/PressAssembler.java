// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Serial-number arithmetic handles loss, duplicates, implicit start and link loss. */
public final class PressAssembler {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    public static final class Press {
        public final int id;
        public final List<byte[]> packets;
        public final int gaps, duplicates, declared;
        public final boolean implicit, overflow;
        public final String end;

        Press(Building b, String end) {
            id = b.id;
            packets = Collections.unmodifiableList(b.packets);
            gaps = b.gaps;
            duplicates = b.duplicates;
            declared = b.declared;
            implicit = b.implicit;
            overflow = b.overflow;
            this.end = end;
        }
    }

    private static final class Building {
        int id, last = -1, gaps, duplicates, declared = -1, bytes;
        boolean implicit, overflow;
        final List<byte[]> packets = new ArrayList<>();

        Building(int id, boolean implicit) {
            this.id = id;
            this.implicit = implicit;
        }
    }

    private Building current;
    private int completed = -1;

    public int active() {
        return current == null ? -1 : current.id;
    }

    public List<Press> handle(PocketProtocol.Message message) {
        List<Press> out = new ArrayList<>();
        if (message.type != PocketProtocol.PRESS_START
                && message.type != PocketProtocol.AUDIO
                && message.type != PocketProtocol.PRESS_END) return out;
        int id = PocketProtocol.u16(message.data, 0);
        if (message.type == PocketProtocol.PRESS_START) {
            finish("superseded", out);
            current = new Building(id, false);
        } else if (message.type == PocketProtocol.AUDIO) {
            if (current == null || current.id != id) {
                finish("superseded", out);
                current = new Building(id, true);
            }
            int seq = PocketProtocol.u16(message.data, 2);
            int step = current.last < 0 ? 1 : (seq - current.last) & 65535;
            if (current.last >= 0 && (step == 0 || step > 0x8000)) current.duplicates++;
            else {
                current.gaps += current.last < 0 ? current.implicit ? 0 : seq : step - 1;
                current.last = seq;
                int n = message.data.length - 4;
                if (current.bytes + n <= MAX_BYTES) {
                    current.packets.add(Arrays.copyOfRange(message.data, 4, message.data.length));
                    current.bytes += n;
                } else current.overflow = true;
            }
        } else {
            if (current == null || current.id != id) {
                if (completed == id) return out;
                // An orphan end reports an empty press without ending another active press.
                Building orphan = new Building(id, false);
                orphan.declared = PocketProtocol.u16(message.data, 2);
                completed = id;
                out.add(new Press(orphan, "press_end"));
                return out;
            }
            current.declared = PocketProtocol.u16(message.data, 2);
            finish("press_end", out);
        }
        return out;
    }

    public List<Press> lost() {
        List<Press> out = new ArrayList<>();
        finish("link_lost", out);
        return out;
    }

    private void finish(String end, List<Press> out) {
        if (current == null) return;
        out.add(new Press(current, end));
        completed = current.id;
        current = null;
    }
}
