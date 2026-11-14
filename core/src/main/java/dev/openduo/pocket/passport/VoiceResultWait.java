// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import org.json.JSONArray;
import org.json.JSONObject;

/** Upstream Passport RESULT reducer. A receipt admits a note; recognition settles its result. */
public final class VoiceResultWait {
    public static final class Result {
        public final int code;
        public final String text;

        Result(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    public final String messageId;
    private String rowText;
    private boolean done;

    public VoiceResultWait(String messageId) {
        this.messageId = messageId;
    }

    public boolean done() {
        return done;
    }

    public String rowText() {
        return rowText;
    }

    public Result handle(JSONObject frame) {
        if (done) return null;
        String type = frame.optString("type");
        if (type.equals("row")) {
            JSONObject row = frame.optJSONObject("row");
            if (row != null
                    && row.optString("kind").equals("typed")
                    && row.optString("utt_id").equals(messageId)) rowText = row.optString("text");
        } else if (type.equals("message") && frame.optString("id").equals(messageId)) {
            String state = frame.optString("state");
            if (state.equals("cleaned")) return finish(0, frame.optString("text"));
            if (state.equals("failed")) {
                switch (frame.optString("reason")) {
                    case "empty_transcript":
                        return finish(1, "");
                    case "asr_failed":
                        return finish(2, "");
                    case "brain_unreachable":
                        return rowText == null ? finish(3, "") : finish(0, rowText);
                    default:
                        return finish(3, "");
                }
            }
        } else if (type.equals("ready")) {
            JSONArray messages = frame.optJSONArray("messages");
            if (messages != null)
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject entry = messages.optJSONObject(i);
                    if (entry != null && entry.optString("id").equals(messageId)) return null;
                }
            return rowText == null ? finish(3, "") : finish(0, rowText);
        }
        return null;
    }

    public Result accepted() {
        return done || rowText == null ? null : finish(0, rowText);
    }

    public Result expired(boolean connected) {
        return done ? null : finish(connected ? 3 : 4, "");
    }

    public Result failed(boolean transport) {
        return done ? null : finish(transport ? 4 : 3, "");
    }

    private Result finish(int code, String text) {
        done = true;
        return new Result(code, text);
    }
}
