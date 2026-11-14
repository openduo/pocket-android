// Copyright 2026 OpenDuo
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable thread row. Updates replace the row with the same stable id. */
public final class Message {
    public static final int MINE = 0, DUODUO = 1, HEARD = 2, WORKING = 3, NOTICE = 4;
    public static final int QUEUED = 0, SENDING = 1, DELIVERED = 2, FAILED = 3;
    public static final int SPOKEN_UNKNOWN = -1, UNSPOKEN = 0, SPOKEN = 1, PARTLY_SPOKEN = 2;

    public final String id;
    public final int kind;
    public final long at;
    public final String text;
    public final String voiceSource;
    public final long durationMs;
    public final int delivery;
    public final String error;
    public final boolean complete;
    public final boolean truncated;
    public final List<Attachment> attachments;
    public final long seq;
    public final String speaker;
    public final int spoken;
    public final List<String> messageIds;

    public Message(
            String id,
            int kind,
            long at,
            String text,
            String voiceSource,
            long durationMs,
            int delivery,
            String error,
            boolean complete,
            boolean truncated,
            List<Attachment> attachments,
            long seq) {
        this(
                id,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                delivery,
                error,
                complete,
                truncated,
                attachments,
                seq,
                "",
                SPOKEN_UNKNOWN,
                legacyMessageIds(id, kind));
    }

    private Message(
            String id,
            int kind,
            long at,
            String text,
            String voiceSource,
            long durationMs,
            int delivery,
            String error,
            boolean complete,
            boolean truncated,
            List<Attachment> attachments,
            long seq,
            String speaker,
            int spoken,
            List<String> messageIds) {
        this.id = id == null ? "" : id;
        this.kind = kind;
        this.at = at <= 0 ? System.currentTimeMillis() : at;
        this.text = text == null ? "" : text;
        this.voiceSource = voiceSource == null ? "" : voiceSource;
        this.durationMs = Math.max(0, durationMs);
        this.delivery = delivery;
        this.error = error == null ? "" : error;
        this.complete = complete;
        this.truncated = truncated;
        this.attachments =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                attachments == null
                                        ? Collections.<Attachment>emptyList()
                                        : attachments));
        this.seq = seq;
        this.speaker = speaker == null || speaker.equals("V?") ? "" : speaker;
        this.spoken = spoken;
        this.messageIds = Collections.unmodifiableList(new ArrayList<>(messageIds));
    }

    public static Message mine(
            String id,
            String text,
            String source,
            long duration,
            int delivery,
            List<Attachment> attachments) {
        return new Message(
                id,
                MINE,
                System.currentTimeMillis(),
                text,
                source,
                duration,
                delivery,
                "",
                true,
                false,
                attachments,
                -1);
    }

    public static Message answer(
            String id,
            String text,
            boolean complete,
            boolean truncated,
            long seq,
            List<Attachment> attachments) {
        return new Message(
                id,
                DUODUO,
                System.currentTimeMillis(),
                text,
                "",
                0,
                DELIVERED,
                "",
                complete,
                truncated,
                attachments,
                seq);
    }

    public Message withDelivery(int state, String why) {
        return new Message(
                id,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                state,
                why,
                complete,
                truncated,
                attachments,
                seq,
                speaker,
                spoken,
                messageIds);
    }

    public Message withText(String value, boolean done, boolean cut) {
        return new Message(
                id,
                kind,
                at,
                value,
                voiceSource,
                durationMs,
                delivery,
                error,
                done,
                cut,
                attachments,
                seq,
                speaker,
                spoken,
                messageIds);
    }

    public Message withAttachments(List<Attachment> values) {
        return new Message(
                id,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                delivery,
                error,
                complete,
                truncated,
                values,
                seq,
                speaker,
                spoken,
                messageIds);
    }

    public Message withIdentity(String value) {
        return new Message(
                value,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                delivery,
                error,
                complete,
                truncated,
                attachments,
                seq,
                speaker,
                spoken,
                messageIds);
    }

    public Message withDuration(long value) {
        return new Message(
                id,
                kind,
                at,
                text,
                voiceSource,
                value,
                delivery,
                error,
                complete,
                truncated,
                attachments,
                seq,
                speaker,
                spoken,
                messageIds);
    }

    public Message withPresentation(String label, int speech) {
        return new Message(
                id,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                delivery,
                error,
                complete,
                truncated,
                attachments,
                seq,
                label,
                speech,
                messageIds);
    }

    public Message withMessageIds(List<String> ids) {
        return new Message(
                id,
                kind,
                at,
                text,
                voiceSource,
                durationMs,
                delivery,
                error,
                complete,
                truncated,
                attachments,
                seq,
                speaker,
                spoken,
                ids);
    }

    /** Log admission can replace a provisional answer's identity, including attachment answers. */
    public boolean sameAnswer(Message other) {
        if (kind != DUODUO || other.kind != DUODUO || !text.trim().equals(other.text.trim()))
            return false;
        if (messageIds.isEmpty() || other.messageIds.isEmpty()) return true;
        for (String id : messageIds) if (other.messageIds.contains(id)) return true;
        return false;
    }

    private static List<String> legacyMessageIds(String id, int kind) {
        return kind == DUODUO && id != null && id.startsWith("ans:") && id.length() > 4
                ? java.util.Arrays.asList(id.substring(4).split(",", -1))
                : Collections.emptyList();
    }

    public JSONObject json() throws JSONException {
        JSONObject o =
                new JSONObject()
                        .put("id", id)
                        .put("kind", kind)
                        .put("at", at)
                        .put("text", text)
                        .put("voiceSource", voiceSource)
                        .put("durationMs", durationMs)
                        .put("delivery", delivery)
                        .put("error", error)
                        .put("complete", complete)
                        .put("truncated", truncated)
                        .put("seq", seq);
        o.put("speaker", speaker).put("spoken", spoken);
        o.put("message_ids", new JSONArray(messageIds));
        JSONArray a = new JSONArray();
        for (Attachment attachment : attachments) a.put(attachment.json(true));
        o.put("attachments", a);
        return o;
    }

    public static Message from(JSONObject o) {
        if (o == null) return null;
        List<Attachment> attachments = new ArrayList<>();
        JSONArray array = o.optJSONArray("attachments");
        if (array != null)
            for (int i = 0; i < array.length(); i++)
                attachments.add(Attachment.from(array.optJSONObject(i)));
        return new Message(
                o.optString("id", ""),
                o.optInt("kind", MINE),
                o.optLong("at", 0),
                o.optString("text", ""),
                o.optString("voiceSource", ""),
                o.optLong("durationMs", 0),
                o.optInt("delivery", DELIVERED),
                o.optString("error", ""),
                o.optBoolean("complete", true),
                o.optBoolean("truncated", false),
                attachments,
                o.optLong("seq", -1),
                o.optString("speaker"),
                o.optInt("spoken", SPOKEN_UNKNOWN),
                o.has("message_ids")
                        ? Json.strings(o.optJSONArray("message_ids"))
                        : legacyMessageIds(o.optString("id"), o.optInt("kind", MINE)));
    }
}
