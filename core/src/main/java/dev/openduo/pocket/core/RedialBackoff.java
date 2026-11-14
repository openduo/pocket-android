// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

/** PocketCore.RedialBackoff: short outages keep capture; the longest retry pause releases it. */
public final class RedialBackoff {
    private int failures;

    public long failedMs() {
        failures = Math.min(failures + 1, 6);
        return Math.min(1000L << (failures - 1), 30_000L);
    }

    public boolean sustained() {
        return failures >= 6;
    }

    public void reset() {
        failures = 0;
    }
}
