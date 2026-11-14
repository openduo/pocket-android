// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import dev.openduo.pocket.core.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Correlation, de-duplication, quiet replies and monotone ids, independent of Android BLE. */
public final class ReplyTracker {
    public static final class Output {
        public final int type;
        public final byte[] payload;
        public final boolean quiet;

        Output(int type, byte[] payload, boolean quiet) {
            this.type = type;
            this.payload = payload;
            this.quiet = quiet;
        }
    }

    public String latestText = "", waitingMessage = "";
    public boolean waiting;
    public long serial, latestId, deliveredId;
    private String latestSignature = "", stream = "";
    private long streamId;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    public void restore(JSONObject json) {
        resetRoom();
        if (json == null) return;
        serial = Math.max(serial, json.optLong("serial"));
        latestId = json.optLong("latestId");
        deliveredId = json.optLong("deliveredId");
        latestText = json.optString("text");
        latestSignature = json.optString("signature");
        waiting = json.optBoolean("waiting");
        waitingMessage = json.optString("waitingMessage");
        JSONArray signatures = json.optJSONArray("seen");
        if (signatures != null)
            for (int i = Math.max(0, signatures.length() - 128); i < signatures.length(); i++) {
                JSONObject entry = signatures.optJSONObject(i);
                if (entry != null) seen.put(entry.optString("signature"), entry.optLong("id"));
            }
        if (!latestSignature.isEmpty()) seen.put(latestSignature, latestId);
    }

    /** A new room keeps the accessory's monotone counter, and no previous conversation. */
    public void resetRoom() {
        latestText = waitingMessage = latestSignature = stream = "";
        latestId = deliveredId = streamId = 0;
        waiting = false;
        seen.clear();
    }

    public JSONObject json() {
        JSONArray signatures = new JSONArray();
        for (Map.Entry<String, Long> entry : seen.entrySet())
            signatures.put(Json.object("signature", entry.getKey(), "id", entry.getValue()));
        return Json.object(
                "serial",
                serial,
                "latestId",
                latestId,
                "deliveredId",
                deliveredId,
                "text",
                latestText,
                "signature",
                latestSignature,
                "waiting",
                waiting,
                "waitingMessage",
                waitingMessage,
                "seen",
                signatures);
    }

    public void expect(String message) {
        waiting = true;
        waitingMessage = message;
    }

    public void adopt() {
        if (!waiting) {
            waiting = true;
            waitingMessage = "";
        }
    }

    public void newPress() {
        waiting = false;
        waitingMessage = "";
    }

    public List<Output> stopWait() {
        List<Output> out = new ArrayList<>();
        if (waiting) {
            waiting = false;
            waitingMessage = "";
            out.add(new Output(PocketProtocol.WORK, new byte[] {0}, false));
            out.add(new Output(PocketProtocol.REPLY_DONE, PocketProtocol.u32(0), false));
        }
        return out;
    }

    public List<Output> turn(String phase, List<String> ids) {
        List<Output> out = new ArrayList<>();
        if (!waiting || !waitingMessage.isEmpty() && !ids.contains(waitingMessage)) return out;
        if (phase.equals("idle")) return stopWait();
        int value =
                phase.equals("received")
                        ? 1
                        : phase.equals("thinking") ? 2 : phase.equals("tool") ? 3 : 0;
        if (value != 0) out.add(new Output(PocketProtocol.WORK, new byte[] {(byte) value}, false));
        return out;
    }

    private long next(long now) {
        serial = Math.max(serial + 1, now / 1000) & 0xffffffffL;
        if (serial == 0) serial = 1;
        return serial;
    }

    public List<Output> partial(String speechId, String text, boolean foreground, long now) {
        List<Output> out = new ArrayList<>();
        if (waiting || text.isEmpty()) return out;
        if (!stream.equals(speechId)) {
            stream = speechId;
            streamId = next(now);
        }
        out.add(
                new Output(
                        PocketProtocol.REPLY,
                        PocketProtocol.reply(streamId, false, text),
                        foreground));
        return out;
    }

    public List<Output> answer(
            List<String> ids, String text, String speechId, boolean foreground, long now) {
        List<Output> out = new ArrayList<>();
        if (text.trim().isEmpty()) return out;
        boolean ends = waiting && (waitingMessage.isEmpty() || ids.contains(waitingMessage));
        String signature = signature(ids, text);
        Long existing = seen.get(signature);
        long id;
        if (existing != null) id = existing;
        else {
            id = !speechId.isEmpty() && stream.equals(speechId) ? streamId : next(now);
            seen.put(signature, id);
            while (seen.size() > 128) seen.remove(seen.keySet().iterator().next());
            latestText = text;
            latestId = id;
            latestSignature = signature;
            out.add(
                    new Output(
                            PocketProtocol.REPLY,
                            PocketProtocol.reply(id, true, text),
                            foreground && !ends));
            out.add(new Output(PocketProtocol.REPLY_DONE, PocketProtocol.u32(id), false));
        }
        if (ends) {
            waiting = false;
            waitingMessage = "";
            if (out.isEmpty())
                out.add(new Output(PocketProtocol.REPLY_DONE, PocketProtocol.u32(id), false));
        }
        if (stream.equals(speechId)) {
            stream = "";
            streamId = 0;
        }
        return out;
    }

    public List<Output> linkReady(boolean foreground) {
        List<Output> out = new ArrayList<>();
        if (latestId != 0 && latestId != deliveredId) {
            out.add(
                    new Output(
                            PocketProtocol.REPLY,
                            PocketProtocol.reply(latestId, true, latestText),
                            foreground && !waiting));
            out.add(new Output(PocketProtocol.REPLY_DONE, PocketProtocol.u32(latestId), false));
        }
        return out;
    }

    public void handed(Output output) {
        if (output.type == PocketProtocol.REPLY && output.payload[4] != 0)
            deliveredId = PocketProtocol.u32(output.payload, 0);
    }

    private static String signature(List<String> ids, String text) {
        List<String> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (String.join(",", sorted) + "\n" + text.trim())
                                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest)
                out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
