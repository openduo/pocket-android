// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import top.kuaner.pockete2.core.RedialBackoff;

/** Ported from PocketCore.RedialBackoffTests, including the sustained-outage boundary. */
final class RedialContracts {
    static int run() {
        RedialBackoff backoff = new RedialBackoff();
        if (backoff.sustained()) throw new AssertionError("A fresh connection is not sustained");
        long[] expected = {1000, 2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000};
        for (int i = 0; i < expected.length; i++) {
            if (backoff.failedMs() != expected[i])
                throw new AssertionError("Upstream retry sequence differs at " + i);
            if (backoff.sustained() != (i >= 5))
                throw new AssertionError("Capture pauses only at the 30-second retry cap");
        }
        backoff.reset();
        if (backoff.sustained() || backoff.failedMs() != 1000)
            throw new AssertionError("Success and explicit retry reset to the first pause");
        for (int i = 0; i < 100_000; i++) backoff.failedMs();
        if (backoff.failedMs() != 30_000 || !backoff.sustained())
            throw new AssertionError("Prolonged failure must not overflow the retry cap");
        return 19;
    }
}
