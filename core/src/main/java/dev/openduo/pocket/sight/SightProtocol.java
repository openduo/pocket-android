// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import dev.openduo.pocket.core.Json;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Exact media-v1 video envelope and validated server-owned capture parameters. */
public final class SightProtocol {
    public static int quadrant(int degrees, int previous) {
        if (degrees < 0) return previous;
        int angle = degrees % 360;
        int delta = Math.abs(angle - previous);
        if (Math.min(delta, 360 - delta) < 60) return previous;
        return ((angle + 45) / 90 * 90) % 360;
    }

    public static int bufferRotation(int sensor, boolean front, int device) {
        if (sensor % 90 != 0 || device % 90 != 0)
            throw new IllegalArgumentException("Camera rotation must be a quadrant");
        return ((front ? -sensor : sensor) + device + 720) % 360;
    }

    public static final class Ask {
        public final int width, height, fps, minFps, bitrate, profile, level;
        public final double keyIntervalMs;
        public final String codec;

        public Ask(JSONObject capture) {
            if (capture == null) throw new IllegalArgumentException("Missing camera capture");
            JSONObject video = capture.optJSONObject("video");
            width = integer(capture, "width", 1, 65535);
            height = integer(capture, "height", 1, 65535);
            fps = integer(video, "fps", 1, Integer.MAX_VALUE);
            minFps = integer(video, "min_fps", 1, fps);
            bitrate = integer(video, "bitrate", 1, Integer.MAX_VALUE);
            codec = video.optString("codec");
            if (!codec.matches("avc1\\.[0-9A-Fa-f]{6}"))
                throw new IllegalArgumentException("Unsupported codec");
            profile = Integer.parseInt(codec.substring(5, 7), 16);
            level = Integer.parseInt(codec.substring(9), 16);
            Object key = video.opt("key_interval_ms");
            if (!(key instanceof Number)
                    || !Double.isFinite(((Number) key).doubleValue())
                    || ((Number) key).doubleValue() <= 0)
                throw new IllegalArgumentException("Invalid key interval");
            keyIntervalMs = ((Number) key).doubleValue();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Ask)) return false;
            Ask value = (Ask) other;
            return width == value.width
                    && height == value.height
                    && fps == value.fps
                    && minFps == value.minFps
                    && bitrate == value.bitrate
                    && codec.equals(value.codec)
                    && keyIntervalMs == value.keyIntervalMs;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    width, height, fps, minFps, bitrate, codec, keyIntervalMs);
        }
    }

    private static int integer(JSONObject o, String key, int min, int max) {
        Object value = o == null ? null : o.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max)
            throw new IllegalArgumentException("Invalid " + key);
        return (int) n;
    }

    public static final class Uplink {
        private long sequence, lastAt = -1;
        private int declaredFps, declaredBitrate;
        private boolean needsKey;

        public void expectKey() {
            needsKey = true;
        }

        public void reset() {
            sequence = 0;
            lastAt = -1;
            declaredFps = declaredBitrate = 0;
            needsKey = true;
        }

        public byte[] frame(
                boolean key,
                long at,
                byte[] payload,
                int width,
                int height,
                String view,
                int fps,
                int bitrate,
                int limit) {
            long seq = sequence++ & 0xffffffffL;
            if (needsKey && !key) return null;
            long timestamp = Math.max(Math.max(0, at), lastAt + 1);
            boolean rate = key || fps != declaredFps || bitrate != declaredBitrate;
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 128);
            out.write(2);
            out.write((key ? 1 : 0) | (rate ? 2 : 0));
            little(out, seq, 4);
            little(out, timestamp, 8);
            if (key) block(out, Json.object("width", width, "height", height, "view", view));
            if (rate) block(out, Json.object("fps", fps, "bitrate", bitrate));
            out.write(payload, 0, payload.length);
            if (out.size() > limit) {
                needsKey = true;
                return null;
            }
            needsKey = false;
            lastAt = timestamp;
            declaredFps = fps;
            declaredBitrate = bitrate;
            return out.toByteArray();
        }
    }

    private static void little(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)) & 255);
    }

    private static void block(ByteArrayOutputStream out, JSONObject object) {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        little(out, bytes.length, 2);
        out.write(bytes, 0, bytes.length);
    }

    public static byte[] annexB(byte[] bytes) {
        if (bytes.length >= 4
                && bytes[0] == 0
                && bytes[1] == 0
                && (bytes[2] == 1 || bytes[2] == 0 && bytes[3] == 1)) return bytes;
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 32);
        int at = 0;
        while (at + 4 <= bytes.length) {
            int n =
                    ((bytes[at] & 255) << 24)
                            | ((bytes[at + 1] & 255) << 16)
                            | ((bytes[at + 2] & 255) << 8)
                            | (bytes[at + 3] & 255);
            at += 4;
            if (n <= 0 || at + n > bytes.length) throw new IllegalArgumentException("Invalid AVCC");
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(bytes, at, n);
            at += n;
        }
        if (at != bytes.length) throw new IllegalArgumentException("Truncated AVCC");
        return out.toByteArray();
    }

    private SightProtocol() {}
}
