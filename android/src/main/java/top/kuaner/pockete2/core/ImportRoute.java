// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The delivery and room selected at launch, independent of a later Activity or room replacement.
 */
public final class ImportRoute {
    public enum Kind {
        MEDIA,
        FILES,
        CAMERA
    }

    public final String id, target;
    public final Kind kind;

    public ImportRoute(String id, String target, Kind kind) {
        ImportBatch.requireId(id);
        if (target == null || kind == null)
            throw new IllegalArgumentException("Missing import route");
        if (target.startsWith("try:")) ImportBatch.requireId(target.substring(4));
        this.id = id;
        this.target = target;
        this.kind = kind;
    }

    public boolean trying() {
        return target.startsWith("try:");
    }

    public boolean visibleIn(String room, boolean demo) {
        return trying() == demo && (target.isEmpty() || target.equals(room));
    }

    public JSONObject json() {
        try {
            return new JSONObject().put("id", id).put("target", target).put("kind", kind.name());
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static ImportRoute from(JSONObject json) {
        try {
            return new ImportRoute(
                    json.getString("id"),
                    json.getString("target"),
                    Kind.valueOf(json.getString("kind")));
        } catch (JSONException failure) {
            throw new IllegalArgumentException("Invalid saved import route", failure);
        }
    }
}
