// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Wire helpers keep checked JSON exceptions at the boundary. */
public final class Json {
    private Json() {}

    public static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException failure) {
            throw new IllegalArgumentException(failure);
        }
        return value;
    }

    public static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null)
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) values.add((String) value);
            }
        return values;
    }

    public static long time(String text) {
        try {
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (Exception failure) {
            return System.currentTimeMillis();
        }
    }

    public static String day(long at) {
        return java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }

    public static String answerKey(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        return "ans:" + String.join(",", sorted);
    }
}
