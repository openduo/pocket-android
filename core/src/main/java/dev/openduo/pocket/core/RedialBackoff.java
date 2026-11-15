// Copyright 2026 openduo and OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import org.json.JSONObject;

/** Opaque Rust retry state; the platform supplies scheduling only. */
public final class RedialBackoff {
    private JSONObject state = Json.object("failures", 0);
    private boolean sustained;

    public long failedMs() {
        return call(true, false);
    }

    public boolean sustained() {
        return sustained;
    }

    public void reset() {
        call(false, true);
    }

    private long call(boolean failed, boolean reset) {
        JSONObject result =
                RustProtocol.call(
                        Json.object(
                                "op", "redial", "state", state, "failed", failed, "reset", reset));
        state = result.optJSONObject("state");
        sustained = result.optBoolean("sustained");
        return result.optLong("delay_ms");
    }
}
