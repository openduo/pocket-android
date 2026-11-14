// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONObject;

import java.util.*;

/** The uplink rules of PocketCore.AmbientEdge, without capture or socket ownership. */
public final class AmbientInput {
    private String conn = "", role = "", roomState = "";
    private boolean capturing, hello, media, echo, metaSeen, micMayBeOff;
    private final Set<String> presses = new HashSet<>();

    public String role() {
        return role;
    }

    public String roomState() {
        return roomState;
    }

    public boolean helloSent() {
        return hello;
    }

    public boolean media() {
        return media;
    }

    public boolean metaSeen() {
        return metaSeen;
    }

    public boolean pressMuted() {
        return !presses.isEmpty();
    }

    public boolean sendsAudio() {
        return capturing && hello && !role.equals("peer");
    }

    public void socketOpened() {
        conn = role = roomState = "";
        hello = media = metaSeen = micMayBeOff = false;
    }

    public void captureStopped() {
        capturing = hello = false;
    }

    public List<JSONObject> packet(String room, boolean aec) {
        capturing = true;
        echo = aec;
        return hello(room);
    }

    public List<JSONObject> meta(JSONObject frame, String room) {
        metaSeen = true;
        if (frame.has("media")) {
            if (frame.optInt("media", -1) != 1)
                throw new IllegalArgumentException("Unsupported media");
            media = true;
        }
        if (frame.has("conn")) {
            conn = frame.optString("conn");
            hello = false;
        }
        role = frame.optString("role", role);
        roomState = frame.optString("state", roomState);
        if (roomState.equals("unowned")) role = "";
        return hello(room);
    }

    public List<JSONObject> takeover(String room) {
        hello = false;
        role = "";
        roomState = "";
        return hello(room);
    }

    public List<JSONObject> press(String source, boolean active) {
        boolean before = pressMuted();
        if (active) presses.add(source);
        else presses.remove(source);
        return before != pressMuted() && hello
                ? Collections.singletonList(micFrame())
                : Collections.emptyList();
    }

    public JSONObject micFrame() {
        // A queued unmute can be discarded by socket teardown. Retain this obligation until
        // the socket ends, even after the last local press has already been released.
        if (pressMuted()) micMayBeOff = true;
        return Json.object("type", "mute", "on", !pressMuted());
    }

    /** Best-effort room-control restoration, to be written before releasing this socket. */
    public JSONObject releaseMicrophone() {
        return hello && micMayBeOff ? Json.object("type", "mute", "on", true) : null;
    }

    private List<JSONObject> hello(String room) {
        if (!capturing || conn.isEmpty() || hello) return Collections.emptyList();
        hello = true;
        return Arrays.asList(
                Json.object(
                        "type", "hello", "room", room, "conn", conn, "edge", "client", "aec", echo),
                micFrame());
    }
}
