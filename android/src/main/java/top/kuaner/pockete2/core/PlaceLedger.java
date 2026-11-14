// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Arrival names only. Acceptance of an older in-flight place never overwrites a newer one. */
public final class PlaceLedger {
    private JSONObject current, reported;

    public PlaceLedger(JSONObject persisted) {
        if (persisted != null) {
            current = persisted.optJSONObject("current");
            reported = persisted.optJSONObject("reported");
            normalizeTime(current);
            normalizeTime(reported);
        }
    }

    public boolean arrived(String name, String area, long time) {
        if (name == null || name.trim().isEmpty()) return false;
        time = Math.max(0, time / 1000 * 1000);
        if (current != null && time < current.optLong("at")) return false;
        if (current != null
                && current.optString("name").equals(name)
                && current.optString("area").equals(area)) return false;
        current = Json.object("name", name, "area", area, "at", time);
        return true;
    }

    public JSONObject json() {
        return Json.object(
                "current",
                current == null ? JSONObject.NULL : current,
                "reported",
                reported == null ? JSONObject.NULL : reported);
    }

    public String pending() {
        if (current == null || same(current, reported)) return "";
        JSONObject body =
                Json.object(
                        "name",
                        current.optString("name"),
                        "area",
                        current.optString("area"),
                        "arrived",
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxxxx", Locale.ROOT)
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(current.optLong("at"))));
        if (reported != null
                && (!reported.optString("name").equals(current.optString("name"))
                        || !reported.optString("area").equals(current.optString("area"))))
            try {
                body.put(
                        "previous",
                        Json.object(
                                "name",
                                reported.optString("name"),
                                "area",
                                reported.optString("area")));
            } catch (org.json.JSONException impossible) {
                throw new AssertionError(impossible);
            }
        return percent(body.toString());
    }

    public void settle(String value) {
        settle(value, 0);
    }

    public void settle(String value, long earliest) {
        if (value == null || value.isEmpty()) return;
        try {
            JSONObject body = new JSONObject(java.net.URLDecoder.decode(value, "UTF-8"));
            long at =
                    java.time.OffsetDateTime.parse(body.getString("arrived"))
                            .toInstant()
                            .toEpochMilli();
            if (at < earliest) return;
            if (reported == null || reported.optLong("at") <= at)
                reported =
                        Json.object(
                                "name",
                                body.optString("name"),
                                "area",
                                body.optString("area"),
                                "at",
                                at);
        } catch (Exception ignored) {
        }
    }

    public void clear() {
        current = null;
        reported = null;
    }

    private static boolean same(JSONObject a, JSONObject b) {
        return b != null
                && a.optString("name").equals(b.optString("name"))
                && a.optString("area").equals(b.optString("area"))
                && a.optLong("at") == b.optLong("at");
    }

    private static void normalizeTime(JSONObject object) {
        if (object == null) return;
        try {
            object.put("at", object.optLong("at") / 1000 * 1000);
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String area(String city, String district) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[] {city, district})
            if (s != null && !s.trim().isEmpty() && !parts.contains(s.trim())) parts.add(s.trim());
        boolean cjk = false;
        for (String s : parts)
            if (s.codePoints()
                    .anyMatch(c -> c >= 0x3400 && c <= 0x9fff || c >= 0xf900 && c <= 0xfaff))
                cjk = true;
        if (!cjk) Collections.reverse(parts);
        return String.join(cjk ? "" : ", ", parts);
    }

    private static String percent(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'A' && c <= 'Z'
                    || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9'
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~') out.append((char) c);
            else
                out.append('%')
                        .append("0123456789ABCDEF".charAt(c >>> 4))
                        .append("0123456789ABCDEF".charAt(c & 15));
        }
        return out.toString();
    }
}
