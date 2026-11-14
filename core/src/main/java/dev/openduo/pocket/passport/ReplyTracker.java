// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import dev.openduo.pocket.core.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Port of PocketCore ReplyTracker: live/log correlation, work, quiet replies and persisted ids. */
public final class ReplyTracker {
    public static final class Output {
        public final int type;
        public final byte[] payload;
        public final boolean quiet;

        public Output(int type, byte[] payload, boolean quiet) {
            this.type = type;
            this.payload = payload;
            this.quiet = quiet;
        }
    }

    public String latestText = "", waitingMessage = "";
    public boolean waiting;
    public long serial, latestId, deliveredId;
    private String latestAt = "", stream = "", streamText = "";
    private List<String> latestMessages = Collections.emptyList();
    private final List<String> waited = new ArrayList<>();
    private final List<JSONObject> delivered = new ArrayList<>();
    private long streamId, pressReplyId;
    private boolean received;

    public void restore(JSONObject json) {
        resetRoom();
        if (json == null) return;
        serial = Math.max(serial, json.optLong("serial"));
        latestId = json.optLong("latestId");
        deliveredId = json.optLong("deliveredId");
        latestText = json.optString("text");
        latestAt = json.optString("at");
        latestMessages = Json.strings(json.optJSONArray("messages"));
        JSONArray entries = json.optJSONArray("delivered");
        if (entries != null)
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null) delivered.add(entry);
            }
        // Old Android memory used opaque SHA signatures. Preserve its counter/latest answer;
        // incompatible fingerprints cannot be used as evidence that a log row was delivered.
    }

    public void resetRoom() {
        latestText = latestAt = waitingMessage = stream = streamText = "";
        latestId = deliveredId = streamId = pressReplyId = 0;
        latestMessages = Collections.emptyList();
        newPress();
        delivered.clear();
    }

    public JSONObject json() {
        return Json.object(
                "version",
                2,
                "serial",
                serial,
                "latestId",
                latestId,
                "deliveredId",
                deliveredId,
                "text",
                latestText,
                "at",
                latestAt,
                "messages",
                new JSONArray(latestMessages),
                "delivered",
                new JSONArray(delivered));
    }

    public void expect(String message) {
        waiting = true;
        if (message != null && !message.isEmpty()) waited.add(message);
        waitingMessage = waited.isEmpty() ? "" : waited.get(0);
    }

    public void adopt() {
        if (!waiting) expect(null);
    }

    public void newPress() {
        waiting = received = false;
        waited.clear();
        waitingMessage = "";
    }

    public List<Output> stopWait() {
        if (!waiting) return Collections.emptyList();
        newPress();
        return Arrays.asList(work(0), done(0));
    }

    public List<Output> connectionFailed() {
        if (!waiting) return Collections.emptyList();
        newPress();
        return Collections.singletonList(done(0));
    }

    private boolean names(List<String> ids) {
        if (!waiting) return false;
        if (waited.isEmpty()) return true;
        for (String id : ids) if (waited.contains(id)) return true;
        return false;
    }

    public List<Output> turn(String phase, List<String> ids) {
        if (!waiting) return Collections.emptyList();
        switch (phase) {
            case "received":
                if (waited.isEmpty() || !names(ids)) return Collections.emptyList();
                received = true;
                return Collections.singletonList(work(1));
            case "thinking":
            case "tool":
                return received || waited.isEmpty()
                        ? Collections.singletonList(work(phase.equals("thinking") ? 2 : 3))
                        : Collections.emptyList();
            case "idle":
                return names(ids) ? stopWait() : Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    public List<Output> restoreTurn(JSONObject turn) {
        if (turn == null
                || !waiting
                || waited.isEmpty()
                || received
                || !names(Json.strings(turn.optJSONArray("message_ids"))))
            return Collections.emptyList();
        received = true;
        List<Output> out = new ArrayList<>();
        out.add(work(1));
        if (turn.optString("phase").equals("thinking")) out.add(work(2));
        if (turn.optString("phase").equals("tool")) out.add(work(3));
        return out;
    }

    public List<Output> messageFailed(String id) {
        if (!waiting || !waited.removeIf(id::equals)) return Collections.emptyList();
        waitingMessage = waited.isEmpty() ? "" : waited.get(0);
        if (!waited.isEmpty()) return Collections.emptyList();
        newPress();
        return Collections.singletonList(done(0));
    }

    public boolean quiet(long id, boolean foreground) {
        return id != pressReplyId && foreground;
    }

    private long next(long now) {
        long next = (serial + 1) & 0xffffffffL;
        if (next == 0) next = 1;
        serial = Math.max(next, Math.min(0xffffffffL, Math.max(0, now / 1000)));
        return serial;
    }

    public List<Output> handle(JSONObject frame, boolean foreground, long now) {
        switch (frame.optString("type")) {
            case "duoduo_said":
                if (frame.optString("kind").equals("reaction") || waiting && !waited.isEmpty())
                    return Collections.emptyList();
                String speech = frame.optString("speech_id");
                if (!speech.isEmpty() && stream.equals(speech))
                    streamText += frame.optString("text");
                else {
                    stream = speech;
                    streamText = frame.optString("text");
                    streamId = 0;
                }
                if (streamText.isEmpty()) return Collections.emptyList();
                if (streamId == 0) streamId = next(now);
                if (waiting) pressReplyId = streamId;
                return Collections.singletonList(reply(streamId, false, streamText, foreground));
            case "answer_final":
                return answer(
                        Json.strings(frame.optJSONArray("message_ids")),
                        frame.optString("text"),
                        frame.has("speech_id") ? frame.optString("speech_id") : null,
                        foreground,
                        now);
            case "turn":
                return turn(
                        frame.optString("phase"), Json.strings(frame.optJSONArray("message_ids")));
            default:
                return Collections.emptyList();
        }
    }

    public List<Output> answer(
            List<String> ids, String text, String speechId, boolean foreground, long now) {
        if (dev.openduo.pocket.text.Graphemes.trimmed(text).isEmpty())
            return Collections.emptyList();
        long id = 0;
        if (speechId != null && stream.equals(speechId)) {
            id = streamId;
            stream = streamText = "";
            streamId = 0;
        }
        if (id == 0) id = next(now);
        delivered.add(
                Json.object(
                        "fingerprint",
                        fingerprint(text),
                        "messages",
                        new JSONArray(ids),
                        "id",
                        id));
        return accept(ids, text, "", id, names(ids), foreground);
    }

    public List<Output> rows(List<JSONObject> rows, boolean foreground, long now) {
        List<JSONObject> fresh = new ArrayList<>();
        for (JSONObject row : rows) {
            String kind = row.optString("kind");
            if (kind.equals("reaction")
                    || !(kind.equals("answer") || row.optString("speaker").equals("多多"))) continue;
            String text = row.optString("full_text", row.optString("text"));
            if (dev.openduo.pocket.text.Graphemes.trimmed(text).isEmpty()) continue;
            List<String> ids = Json.strings(row.optJSONArray("message_ids"));
            long fingerprint = fingerprint(text);
            int found = -1;
            for (int i = 0; i < delivered.size(); i++) {
                JSONObject entry = delivered.get(i);
                if (entry.optLong("fingerprint") == fingerprint
                        && new HashSet<>(Json.strings(entry.optJSONArray("messages")))
                                .equals(new HashSet<>(ids))) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) delivered.remove(found);
            else fresh.add(row);
        }
        JSONObject selected = null;
        if (waiting && !waited.isEmpty())
            for (JSONObject row : fresh)
                if (names(Json.strings(row.optJSONArray("message_ids")))) {
                    selected = row;
                    break;
                }
        boolean ends = selected != null || waiting && waited.isEmpty();
        if (selected == null && !fresh.isEmpty()) selected = fresh.get(fresh.size() - 1);
        if (selected == null) return Collections.emptyList();
        return accept(
                Json.strings(selected.optJSONArray("message_ids")),
                selected.optString("full_text", selected.optString("text")),
                selected.optString("at"),
                next(now),
                ends,
                foreground);
    }

    public void forgetDelivered() {
        delivered.clear();
    }

    private List<Output> accept(
            List<String> ids, String text, String at, long id, boolean ends, boolean foreground) {
        if (id >= latestId) {
            latestId = id;
            latestText = text;
            latestAt = at;
            latestMessages = new ArrayList<>(ids);
        }
        if (ends) {
            pressReplyId = id;
            newPress();
        }
        return Arrays.asList(reply(id, true, text, foreground), done(id));
    }

    public List<Output> linkReady(boolean foreground) {
        if (latestId == 0 || latestId <= deliveredId) return Collections.emptyList();
        return Arrays.asList(reply(latestId, true, latestText, foreground), done(latestId));
    }

    public void handed(Output output) {
        if (output.type == PocketProtocol.REPLY && output.payload[4] != 0)
            deliveredId = Math.max(deliveredId, PocketProtocol.u32(output.payload, 0));
    }

    public static long replyId(String key) {
        long hash = 0x811c9dc5L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8))
            hash = ((hash ^ (b & 255)) * 0x01000193) & 0xffffffffL;
        return hash == 0 ? 1 : hash;
    }

    private static long fingerprint(String text) {
        return replyId("text:" + dev.openduo.pocket.text.Graphemes.trimmed(text));
    }

    private Output reply(long id, boolean finalText, String text, boolean foreground) {
        return new Output(
                PocketProtocol.REPLY,
                PocketProtocol.reply(id, finalText, text),
                quiet(id, foreground));
    }

    private static Output work(int phase) {
        return new Output(PocketProtocol.WORK, new byte[] {(byte) phase}, false);
    }

    private static Output done(long id) {
        return new Output(PocketProtocol.REPLY_DONE, PocketProtocol.u32(id), false);
    }
}
