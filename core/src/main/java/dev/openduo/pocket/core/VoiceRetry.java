// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import java.util.function.LongSupplier;

/** The upstream voice upload's elapsed-time budget, including body preparation and backoff. */
public final class VoiceRetry {
    private final LongSupplier now;
    private final long start, budgetMs;
    private long backoffMs = 500;

    public VoiceRetry() {
        this(25_000, () -> System.nanoTime() / 1_000_000);
    }

    public VoiceRetry(long budgetMs, LongSupplier monotonicMillis) {
        if (budgetMs <= 0) throw new IllegalArgumentException("Invalid voice upload budget");
        this.budgetMs = budgetMs;
        now = monotonicMillis;
        start = now.getAsLong();
    }

    public long remainingMs() {
        return Math.max(0, budgetMs - Math.max(0, now.getAsLong() - start));
    }

    /** No next attempt after expiry; the final wait may consume the remaining budget. */
    public long nextDelayMs() {
        long remaining = remainingMs();
        if (remaining == 0) return -1;
        long delay = Math.min(backoffMs, remaining);
        backoffMs = Math.min(4000, backoffMs * 2);
        return delay;
    }
}
