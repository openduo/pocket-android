// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONObject;

/** PocketCore.AmbientEdge's output policy. State-owner only; no decoder or hardware ownership. */
public final class AmbientOutput {
    private String id = "";
    private boolean reading;
    private long hardwareMs, skippedSamples, reportedMs, declaration;

    public String id() {
        return id;
    }

    public boolean reading() {
        return reading;
    }

    /** Hardware keys are local; a repeated wire id still starts a different Opus declaration. */
    public String playbackKey() {
        return id.isEmpty() ? "" : "output:" + declaration;
    }

    public long playedMs() {
        return hardwareMs + skippedSamples * 1000 / 16_000;
    }

    /** A new declaration resets accounting, while previously queued PCM may finish first. */
    public void speech(String speech) {
        stop();
        declaration++;
        id = speech;
    }

    public void read(String message) {
        speech("read:" + message);
        reading = true;
    }

    public void stop() {
        id = "";
        reading = false;
        hardwareMs = skippedSamples = reportedMs = 0;
    }

    /** A room stop neither cuts a local read nor fillers; a named stale stop is ignored. */
    public boolean acceptsStop(JSONObject frame) {
        return !reading
                && !id.startsWith("s")
                && (!frame.has("speech_id") || frame.optString("speech_id").equals(id));
    }

    /** Only a local press/read interrupts a room speech, including a filler. */
    public JSONObject interrupted(boolean reports) {
        return reports && !reading && !id.isEmpty()
                ? Json.object("type", "interrupted", "speech_id", id)
                : null;
    }

    public JSONObject played(String key, long ms, boolean reports) {
        if (!key.equals(playbackKey()) || id.isEmpty() || ms < 0) return null;
        hardwareMs = Math.max(hardwareMs, ms);
        return report(reports);
    }

    /** Silent new room blocks count immediately instead of holding the room's playback clock. */
    public JSONObject skipped(int samples, boolean reports) {
        if (id.isEmpty() || reading || samples <= 0) return null;
        skippedSamples += samples;
        return report(reports);
    }

    private JSONObject report(boolean reports) {
        long ms = playedMs();
        if (!reports || reading || ms <= reportedMs) return null;
        reportedMs = ms;
        return Json.object("type", "played", "speech_id", id, "ms", ms);
    }
}
