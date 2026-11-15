// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import dev.openduo.pocket.protocol.NativeCore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** JSON domain mapping only. Wire rules and component state have one owner in pocket-rs. */
public final class RustProtocol {
    private RustProtocol() {}

    public static final class Failure extends IllegalArgumentException {
        public final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    public static JSONObject value(NativeCore.Result result) {
        try {
            JSONObject value = new JSONObject(new String(result.payload, StandardCharsets.UTF_8));
            if (result.status != 0)
                throw new Failure(value.getJSONObject("error").getString("code"));
            return value;
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Invalid Rust ABI result", error);
        }
    }

    public static JSONObject call(JSONObject command) {
        return value(NativeCore.call(NativeCore.PROTOCOL, 0, 0, encode(command), null));
    }

    public static byte[] encode(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] bytes(JSONArray data) {
        if (data == null) return null;
        byte[] value = new byte[data.length()];
        for (int i = 0; i < value.length; i++) value[i] = (byte) data.optInt(i);
        return value;
    }

    public static List<JSONObject> frames(JSONArray data) {
        List<JSONObject> value = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) value.add(data.optJSONObject(i));
        return value;
    }

    public static final class Request {
        public final String method, path;
        public final JSONObject headers;
        public final byte[] body;

        Request(JSONObject value, byte[] input) {
            method = value.optString("method");
            path = value.optString("path");
            headers = value.optJSONObject("headers");
            body = value.optBoolean("input_body") ? input : bytes(value.optJSONArray("body"));
        }
    }

    public static Request request(
            Endpoint endpoint, String token, String room, JSONObject request, byte[] body) {
        JSONObject config =
                Json.object(
                        "endpoint",
                        Json.object(
                                "host", endpoint.host, "port", endpoint.port, "tls", endpoint.tls),
                        "room",
                        room,
                        "token",
                        token);
        return new Request(
                value(
                        NativeCore.call(
                                NativeCore.REQUEST,
                                0,
                                0,
                                encode(Json.object("config", config, "request", request)),
                                body)),
                body);
    }

    /** Long-lived app owners close on disposal; a stream worker closes its parser in finally. */
    public static final class Owner implements AutoCloseable {
        private long handle;
        private JSONObject state;

        public Owner(String type) {
            NativeCore.Result created =
                    NativeCore.call(
                            NativeCore.COMPONENT_CREATE,
                            0,
                            0,
                            encode(Json.object("type", type)),
                            null);
            state = value(created).optJSONObject("state");
            handle = created.handle;
        }

        public JSONObject state() {
            return state;
        }

        public JSONObject dispatch(JSONObject command) {
            JSONObject result =
                    value(
                            NativeCore.call(
                                    NativeCore.COMPONENT_DISPATCH,
                                    handle,
                                    0,
                                    encode(command),
                                    null));
            state = result.optJSONObject("state");
            return result;
        }

        public JSONObject binary(int kind, byte[] data) {
            JSONObject result =
                    value(NativeCore.call(NativeCore.COMPONENT_BINARY, handle, kind, data, null));
            state = result.optJSONObject("state");
            return result;
        }

        @Override
        public void close() {
            if (handle == 0) return;
            value(NativeCore.call(NativeCore.COMPONENT_CLOSE, handle, 0, null, null));
            handle = 0;
        }
    }
}
