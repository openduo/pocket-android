// Copyright 2026 openduo and OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import org.json.JSONObject;

import java.util.List;

/** Android domain facade for the Rust Ambient uplink owner; no local protocol state. */
public final class AmbientInput implements AutoCloseable {
    private final RustProtocol.Owner owner = new RustProtocol.Owner("ambient_input");

    public String role() {
        return owner.state().optString("role", "");
    }

    public String roomState() {
        return owner.state().optString("room_state", "");
    }

    public boolean helloSent() {
        return owner.state().optBoolean("hello");
    }

    public boolean media() {
        return owner.state().optBoolean("media");
    }

    public boolean metaSeen() {
        return owner.state().optBoolean("meta_seen");
    }

    public boolean pressMuted() {
        return owner.state().optBoolean("muted");
    }

    public boolean sendsAudio() {
        return owner.state().optBoolean("sends_audio");
    }

    public void socketOpened() {
        owner.dispatch(Json.object("type", "opened"));
    }

    public void captureStopped() {
        owner.dispatch(Json.object("type", "stopped"));
    }

    public List<JSONObject> packet(String room, boolean aec) {
        return frames(Json.object("type", "packet", "room", room, "aec", aec));
    }

    public List<JSONObject> meta(JSONObject frame, String room) {
        JSONObject normalized;
        try {
            normalized = new JSONObject(frame.toString());
            normalized.put("type", "meta");
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return frames(Json.object("type", "meta", "frame", normalized, "room", room));
    }

    public List<JSONObject> takeover(String room) {
        return frames(Json.object("type", "takeover", "room", room));
    }

    public List<JSONObject> press(String source, boolean active) {
        return frames(Json.object("type", "press", "source", source, "active", active));
    }

    public JSONObject micFrame() {
        return owner.dispatch(Json.object("type", "mic_frame")).optJSONObject("result");
    }

    public void validateFrame(JSONObject frame) {
        owner.dispatch(Json.object("type", "frame", "frame", frame, "room", ""));
    }

    public JSONObject releaseMicrophone() {
        return owner.dispatch(Json.object("type", "release")).optJSONObject("result");
    }

    public byte[] uplink(byte[] packet) {
        return RustProtocol.bytes(owner.binary(1, packet).optJSONArray("result"));
    }

    public byte[] downlink(byte[] frame) {
        return RustProtocol.bytes(owner.binary(2, frame).optJSONArray("result"));
    }

    private List<JSONObject> frames(JSONObject command) {
        return RustProtocol.frames(owner.dispatch(command).optJSONArray("result"));
    }

    @Override
    public void close() {
        owner.close();
    }
}
