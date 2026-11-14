// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.*;

/** A durable request to copy external content; it never admits a partial file into the draft. */
public final class ImportRequest {
    public final ImportRoute route;
    public final String text, ownedPhoto;
    public final List<String> sources;

    public ImportRequest(ImportRoute route, String text, List<String> sources, String ownedPhoto) {
        if (route.trying())
            throw new IllegalArgumentException(
                    "Transient imports cannot be recovered into a real room");
        ImportBatch.requirePhotoName(ownedPhoto);
        for (String source : sources) {
            URI uri = URI.create(source);
            if (!"content".equals(uri.getScheme())
                    || uri.getRawAuthority() == null
                    || uri.getRawAuthority().isEmpty())
                throw new IllegalArgumentException("Import recovery requires a content URI");
        }
        this.route = route;
        this.text = text == null ? "" : text;
        this.ownedPhoto = ownedPhoto;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public JSONObject json() {
        return Json.object(
                "version",
                1,
                "route",
                route.json(),
                "text",
                text,
                "photo",
                ownedPhoto,
                "sources",
                new JSONArray(sources));
    }

    public static ImportRequest from(JSONObject value) {
        try {
            if (value.getInt("version") != 1)
                throw new IllegalArgumentException("Unsupported import request");
            JSONArray items = value.getJSONArray("sources");
            List<String> sources = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) sources.add(items.getString(i));
            return new ImportRequest(
                    ImportRoute.from(value.getJSONObject("route")),
                    value.getString("text"),
                    sources,
                    value.getString("photo"));
        } catch (org.json.JSONException failure) {
            throw new IllegalArgumentException("Invalid pending import request", failure);
        }
    }
}
