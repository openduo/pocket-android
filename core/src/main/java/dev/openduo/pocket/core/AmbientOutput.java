// Copyright 2026 openduo and OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import org.json.JSONObject;

import java.util.function.LongSupplier;

/** Domain facade for Rust's output clock/reception owner; hardware remains platform-owned. */
public final class AmbientOutput implements AutoCloseable {
    private final RustProtocol.Owner owner = new RustProtocol.Owner("ambient_output");
    private final LongSupplier clock;

    public AmbientOutput() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    public AmbientOutput(LongSupplier clock) {
        this.clock = clock;
    }

    public String id() {
        return owner.state().optString("id", "");
    }

    public boolean reading() {
        return owner.state().optBoolean("reading");
    }

    public boolean receiving() {
        return owner.state().optBoolean("receiving");
    }

    public String playbackKey() {
        return owner.state().optString("key", "");
    }

    public long playedMs() {
        return owner.state().optLong("played_ms");
    }

    public void received(int samples) {
        if (samples > 0) call("received", "samples", samples);
    }

    public boolean completed(JSONObject frame) {
        return call("completed", "frame", frame).optBoolean("result");
    }

    public boolean expire(boolean hardwarePending) {
        return call("expire", "hardware_pending", hardwarePending).optBoolean("result");
    }

    public void speech(String id) {
        if (id.isEmpty()) stop();
        else call("speech", "id", id);
    }

    public void read(String id) {
        call("read", "id", id);
    }

    public void stop() {
        call("stop");
    }

    public boolean acceptsStop(JSONObject frame) {
        return call("accepts_stop", "frame", frame).optBoolean("result");
    }

    public JSONObject interrupted(boolean reports) {
        return call("interrupted", "reports", reports).optJSONObject("result");
    }

    public JSONObject played(String key, long ms, boolean reports) {
        return ms < 0
                ? null
                : call("played", "key", key, "ms", ms, "reports", reports).optJSONObject("result");
    }

    public JSONObject skipped(int samples, boolean reports) {
        return samples <= 0
                ? null
                : call("skipped", "samples", samples, "reports", reports).optJSONObject("result");
    }

    private JSONObject call(String type, Object... args) {
        JSONObject command = Json.object(args);
        try {
            command.put("type", type);
            command.put("now_ms", clock.getAsLong());
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return owner.dispatch(command);
    }

    @Override
    public void close() {
        owner.close();
    }
}
