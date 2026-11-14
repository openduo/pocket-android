// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Value objects only. RoomRepository commits the row and pending body in one transaction. */
public final class Outbox {
    public static final int ACCEPTED = 4;

    private Outbox() {}

    public static final class Item {
        public final String id, text, voiceFile, source, error, cleaned, display, place;
        public final boolean spoken;
        public final boolean voice;
        public final long createdAt, durationMs, acceptedAt;
        public final List<Attachment> attachments;
        public final int state;

        public Item(
                String id,
                String text,
                boolean voice,
                String voiceFile,
                String source,
                long durationMs,
                List<Attachment> attachments,
                int state,
                String error,
                String cleaned,
                long createdAt,
                long acceptedAt) {
            this(
                    id,
                    text,
                    voice,
                    voiceFile,
                    source,
                    durationMs,
                    attachments,
                    state,
                    error,
                    cleaned,
                    createdAt,
                    acceptedAt,
                    "",
                    false,
                    "");
        }

        public Item(
                String id,
                String text,
                boolean voice,
                String voiceFile,
                String source,
                long durationMs,
                List<Attachment> attachments,
                int state,
                String error,
                String cleaned,
                long createdAt,
                long acceptedAt,
                String display,
                boolean spoken,
                String place) {
            this.display = display;
            this.spoken = spoken;
            this.place = place;
            this.id = id;
            this.text = text;
            this.voice = voice;
            this.voiceFile = voiceFile;
            this.source = source;
            this.durationMs = durationMs;
            this.state = state;
            this.error = error;
            this.cleaned = cleaned;
            this.createdAt = createdAt;
            this.acceptedAt = acceptedAt;
            this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
        }

        public Item state(int next, String why) {
            return new Item(
                    id,
                    text,
                    voice,
                    voiceFile,
                    source,
                    durationMs,
                    attachments,
                    next,
                    why,
                    cleaned,
                    createdAt,
                    next == ACCEPTED ? System.currentTimeMillis() : acceptedAt,
                    display,
                    spoken,
                    place);
        }

        public Item cleaned(String value) {
            return new Item(
                    id,
                    text,
                    voice,
                    voiceFile,
                    source,
                    durationMs,
                    attachments,
                    ACCEPTED,
                    "",
                    value,
                    createdAt,
                    acceptedAt,
                    display,
                    spoken,
                    place);
        }

        public Item surface(String display, boolean spoken, String place) {
            return new Item(
                    id,
                    text,
                    voice,
                    voiceFile,
                    source,
                    durationMs,
                    attachments,
                    state,
                    error,
                    cleaned,
                    createdAt,
                    acceptedAt,
                    display,
                    spoken,
                    place);
        }

        public JSONObject json() {
            JSONArray a = new JSONArray();
            try {
                for (Attachment attachment : attachments) a.put(attachment.json(true));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return Json.object(
                    "id",
                    id,
                    "text",
                    text,
                    "voice",
                    voice,
                    "voiceFile",
                    voiceFile,
                    "display",
                    display,
                    "spoken",
                    spoken,
                    "place",
                    place,
                    "source",
                    source,
                    "durationMs",
                    durationMs,
                    "state",
                    state,
                    "error",
                    error,
                    "cleaned",
                    cleaned,
                    "createdAt",
                    createdAt,
                    "acceptedAt",
                    acceptedAt,
                    "attachments",
                    a);
        }

        public static Item from(JSONObject o) {
            List<Attachment> attachments = new ArrayList<>();
            JSONArray a = o.optJSONArray("attachments");
            if (a != null)
                for (int i = 0; i < a.length(); i++) {
                    Attachment attachment = Attachment.from(a.optJSONObject(i));
                    if (attachment != null) attachments.add(attachment);
                }
            return new Item(
                    o.optString("id"),
                    o.optString("text"),
                    o.optBoolean("voice"),
                    o.optString("voiceFile"),
                    o.optString("source", "phone"),
                    o.optLong("durationMs"),
                    attachments,
                    o.optInt("state", Message.QUEUED),
                    o.optString("error"),
                    o.optString("cleaned"),
                    o.optLong("createdAt"),
                    o.optLong("acceptedAt"),
                    o.optString("display"),
                    o.optBoolean("spoken"),
                    o.optString("place"));
        }
    }
}
