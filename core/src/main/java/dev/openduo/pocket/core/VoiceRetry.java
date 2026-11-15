// Copyright 2026 openduo and OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import org.json.JSONObject;

import java.util.function.LongSupplier;

/** Rust's monotonic upload budget/backoff, with the caller's monotonic clock. */
public final class VoiceRetry {
    private final LongSupplier now;
    private final long budget;
    private JSONObject state;

    public VoiceRetry() {
        this(25_000, () -> System.nanoTime() / 1_000_000L);
    }

    public VoiceRetry(long budgetMs, LongSupplier monotonicMillis) {
        if (budgetMs <= 0) throw new IllegalArgumentException("Invalid voice upload budget");
        budget = budgetMs;
        now = monotonicMillis;
        call(false);
    }

    public long remainingMs() {
        return call(false).optLong("remaining_ms");
    }

    public long nextDelayMs() {
        JSONObject result = call(true);
        return result.isNull("delay_ms") ? -1 : result.optLong("delay_ms");
    }

    private JSONObject call(boolean next) {
        JSONObject result =
                RustProtocol.call(
                        Json.object(
                                "op",
                                "voice_retry",
                                "state",
                                state == null ? JSONObject.NULL : state,
                                "now_ms",
                                now.getAsLong(),
                                "budget_ms",
                                budget,
                                "next",
                                next));
        state = result.optJSONObject("state");
        return result;
    }
}
