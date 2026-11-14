// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

/** Observed input bytes, never an estimated percentage or a claim of upload completion. */
public final class ImportProgress {
    public final String id, name;
    public final int index, total;
    public final long bytes;
    public final boolean started, cancelling;

    ImportProgress(
            String id,
            String name,
            int index,
            int total,
            long bytes,
            boolean started,
            boolean cancelling) {
        this.id = id;
        this.name = name;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
        this.started = started;
        this.cancelling = cancelling;
    }
}
