// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

/** Finite recovery of transient camera/encoder failures without changing the user's switch. */
public final class SightRecovery {
    private static final long[] DELAYS_MS = {500, 2_000, 8_000};
    private String failure = "";
    private int failures;
    private long retryAt;

    public String failure() {
        return failure;
    }

    public boolean transientFailure() {
        return failure.equals("camera_taken") || failure.equals("encoder_failed");
    }

    public boolean exhausted() {
        return transientFailure() && failures > DELAYS_MS.length;
    }

    public void failed(String reason, long now) {
        failure = reason;
        if (transientFailure()) {
            failures++;
            retryAt = exhausted() ? Long.MAX_VALUE : now + DELAYS_MS[failures - 1];
        }
    }

    /** -1 holds the failure; 0 permits a retry; a positive value is the remaining backoff. */
    public long delay(long now) {
        return !transientFailure() || exhausted() ? -1 : Math.max(0, retryAt - now);
    }

    /** A permitted retry keeps the failure budget until a key frame actually leaves the phone. */
    public void retry() {
        if (transientFailure()) failure = "";
    }

    /** An actual control/lifecycle input change permits transient failures again. */
    public void inputsChanged() {
        if (failure.isEmpty() || transientFailure()) reset();
    }

    public void reset() {
        failure = "";
        failures = 0;
        retryAt = 0;
    }
}
