// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.passport;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.core.Json;
import top.kuaner.pockete2.text.Markdown;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

/** Runs on the controller's state executor; BLE callbacks never mutate room state themselves. */
public final class PassportCoordinator {
    public interface Listener {
        void changed();

        void press(PressAssembler.Press press);

        void expired(String message);

        void capturing(boolean active);
    }

    public static final class Snapshot {
        public final PassportLink.Snapshot link;
        public final String firmware, latestText;
        public final int major, minor, battery, charging;
        public final boolean compatible, preroll, waiting;
        public final long lastPress;

        Snapshot(PassportCoordinator c) {
            link = c.link.snapshot();
            firmware = c.firmware;
            major = c.major;
            minor = c.minor;
            battery = c.battery;
            charging = c.charging;
            compatible = c.major == 0 || c.major == 1;
            preroll = c.preroll;
            latestText = c.reply.latestText;
            waiting = c.reply.waiting;
            lastPress = c.lastPress;
        }
    }

    private final PassportLink link;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Executor state;
    private final top.kuaner.pockete2.diagnostics.AppLog log;
    private final PressAssembler press = new PressAssembler();
    private final ReplyTracker reply = new ReplyTracker();
    private final Map<String, Wait> waits = new LinkedHashMap<>();
    private boolean foreground, online, replaying;
    private boolean linkWasReady;
    private long roomEpoch;
    private String roomIdentity = "", firmware = "", streamText = "", streamSpeech = "";
    private int major, minor, battery = -1, charging = 255;
    private boolean preroll;
    private long lastPress;
    private JSONObject replayAnswer;

    private static final class Wait {
        String id, text = "";
        int press;
        long deadline;
        boolean row;

        Wait(String id, int press, long deadline) {
            this.id = id;
            this.press = press;
            this.deadline = deadline;
        }
    }

    public PassportCoordinator(Context context, Executor state, Listener listener) {
        this.listener = listener;
        this.state = state;
        log = top.kuaner.pockete2.diagnostics.AppLog.get(context);
        prefs = context.getSharedPreferences("passport.memory", Context.MODE_PRIVATE);
        try {
            reply.serial =
                    Math.max(
                            prefs.getLong("replySerial", 0),
                            new JSONObject(prefs.getString("reply", "{}")).optLong("serial"));
        } catch (org.json.JSONException ignored) {
        }
        link =
                new PassportLink(
                        context,
                        new PassportLink.Listener() {
                            public void state(PassportLink.Snapshot s) {
                                state.execute(
                                        () -> {
                                            log.event("passport.link", s.state + " mtu=" + s.mtu);
                                            if (s.ready && !linkWasReady) {
                                                appState();
                                                forward(reply.linkReady(foreground));
                                            }
                                            linkWasReady = s.ready;
                                            listener.changed();
                                        });
                            }

                            public void message(PocketProtocol.Message message) {
                                state.execute(() -> receive(message));
                            }

                            public void disconnected() {
                                state.execute(
                                        () -> {
                                            for (PressAssembler.Press p : press.lost())
                                                listener.press(p);
                                            listener.capturing(false);
                                            listener.changed();
                                        });
                            }
                        });
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public void room(String identity) {
        if (!roomIdentity.equals(identity)) {
            forward(reply.stopWait());
            waits.clear();
            roomEpoch++;
            reply.resetRoom();
            roomIdentity = identity;
            streamText = streamSpeech = "";
            replayAnswer = null;
            try {
                reply.restore(new JSONObject(prefs.getString("reply:" + identity, "{}")));
                JSONArray a = new JSONArray(prefs.getString("waits:" + identity, "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    Wait w = new Wait(o.optString("id"), o.optInt("press"), o.optLong("deadline"));
                    w.row = o.optBoolean("row");
                    w.text = o.optString("text");
                    waits.put(w.id, w);
                }
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    public void foreground(boolean value) {
        foreground = value;
        if (value) link.resume();
    }

    public void online(boolean value) {
        if (online != value) {
            online = value;
            appState();
            if (!value) forward(reply.stopWait());
        }
    }

    public void scan() {
        link.scan();
    }

    public void resume() {
        link.resume();
    }

    public void connect(String address) {
        link.connect(address);
    }

    public void forget() {
        link.forget();
        waits.clear();
        reply.newPress();
        persist();
    }

    private void appState() {
        link.send(
                PocketProtocol.APP_STATE, new byte[] {(byte) (major > 1 ? 2 : online ? 0 : 1), 0});
    }

    private void receive(PocketProtocol.Message message) {
        byte[] b = message.data;
        if (message.type == PocketProtocol.INFO) {
            int previousMajor = major;
            major = b[0] & 255;
            minor = b[1] & 255;
            int size = b[2] & 255;
            firmware = new String(b, 3, size, StandardCharsets.UTF_8);
            battery = b[3 + size] & 255;
            charging = b[4 + size] & 255;
            preroll = b[5 + size] != 0;
            if (major != 1 && previousMajor != major) appState();
        } else if (message.type == PocketProtocol.STATUS) {
            battery = b[0] & 255;
            charging = b[1] & 255;
        } else if (major == 1) {
            if (message.type == PocketProtocol.PRESS_START
                    || message.type == PocketProtocol.AUDIO
                            && press.active() != PocketProtocol.u16(b, 0)) {
                reply.newPress();
                lastPress = System.currentTimeMillis();
                listener.capturing(true);
            }
            for (PressAssembler.Press p : press.handle(message)) listener.press(p);
            if (press.active() < 0) listener.capturing(false);
            if (message.type == PocketProtocol.KEEPALIVE) {
                reply.adopt();
                appState();
            }
        }
        listener.changed();
    }

    public void expect(String id, int pressId) {
        log.event("passport.press", "upload_wait");
        waits.put(id, new Wait(id, pressId, System.currentTimeMillis() + 25_000));
        reply.expect(id);
        persist();
    }

    public void rejected(int pressId, int code) {
        link.send(PocketProtocol.RESULT, PocketProtocol.result(pressId, code, ""));
    }

    public void accepted(String id) {
        Wait w = waits.get(id);
        if (w != null && w.row) result(w, 0, w.text);
    }

    public void uploadFailed(String id, boolean transport) {
        Wait w = waits.get(id);
        if (w != null) result(w, transport ? 4 : 3, "");
    }

    public void tick() {
        for (Wait w : new ArrayList<>(waits.values()))
            if (System.currentTimeMillis() >= w.deadline) {
                result(w, online ? 3 : 4, "");
                listener.expired(w.id);
            }
    }

    private void result(Wait wait, int code, String text) {
        log.event("passport.result", "code=" + code + " chars=" + text.length());
        waits.remove(wait.id);
        link.send(PocketProtocol.RESULT, PocketProtocol.result(wait.press, code, text));
        if (code != 0 && reply.waitingMessage.equals(wait.id)) forward(reply.stopWait());
        persist();
        listener.changed();
    }

    public void beginReplay() {
        replaying = true;
        replayAnswer = null;
    }

    public void frame(JSONObject f) {
        String type = f.optString("type");
        String id = f.optString("id");
        Wait w = waits.get(id);
        if (type.equals("message") && w != null) {
            if (f.optString("state").equals("cleaned")) result(w, 0, f.optString("text"));
            else if (f.optString("state").equals("failed")) {
                String reason = f.optString("reason");
                result(
                        w,
                        reason.equals("empty_transcript") ? 1 : reason.equals("asr_failed") ? 2 : 3,
                        "");
            }
        }
        if (type.equals("message")
                && f.optString("state").equals("failed")
                && id.equals(reply.waitingMessage)
                && f.optString("reason").equals("brain_unreachable")) forward(reply.stopWait());
        if (type.equals("row")) {
            JSONObject row = f.optJSONObject("row");
            if (row == null) return;
            Wait item = waits.get(row.optString("utt_id"));
            if (item != null) {
                item.row = true;
                item.text = row.optString("text");
                persist();
            }
            if ((row.optString("kind").equals("answer") || row.optString("speaker").equals("多多"))
                    && row.optJSONArray("attachments") == null) {
                JSONObject answer =
                        Json.object(
                                "type",
                                "answer_final",
                                "text",
                                row.optString("full_text", row.optString("text")),
                                "message_ids",
                                row.optJSONArray("message_ids"));
                if (replaying) {
                    boolean matching =
                            reply.waiting
                                    && Json.strings(answer.optJSONArray("message_ids"))
                                            .contains(reply.waitingMessage);
                    if (replayAnswer == null
                            || matching
                            || !Json.strings(replayAnswer.optJSONArray("message_ids"))
                                    .contains(reply.waitingMessage)) replayAnswer = answer;
                } else answer(answer);
            }
        } else if (type.equals("answer_final")) answer(f);
        else if (type.equals("turn"))
            forward(reply.turn(f.optString("phase"), Json.strings(f.optJSONArray("message_ids"))));
        else if (type.equals("duoduo_said") && !f.optString("kind").equals("reaction")) {
            String speech = f.optString("speech_id");
            if (!streamSpeech.equals(speech)) {
                streamSpeech = speech;
                streamText = "";
            }
            streamText += f.optString("text");
            forward(
                    reply.partial(
                            speech,
                            Markdown.spoken(streamText),
                            foreground,
                            System.currentTimeMillis()));
        } else if (type.equals("ready")) {
            replaying = false;
            if (replayAnswer != null) answer(replayAnswer);
            replayAnswer = null;
            List<String> open = new ArrayList<>();
            JSONArray a = f.optJSONArray("messages");
            if (a != null)
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) open.add(o.optString("id"));
                }
            for (Wait pending : new ArrayList<>(waits.values()))
                if (!open.contains(pending.id))
                    result(pending, pending.row ? 0 : 3, pending.row ? pending.text : "");
        }
    }

    private void answer(JSONObject f) {
        forward(
                reply.answer(
                        Json.strings(f.optJSONArray("message_ids")),
                        Markdown.spoken(f.optString("text")),
                        f.optString("speech_id"),
                        foreground,
                        System.currentTimeMillis()));
        listener.changed();
    }

    private void forward(List<ReplyTracker.Output> messages) {
        long ticket = roomEpoch;
        for (ReplyTracker.Output output : messages) {
            if (output.type == PocketProtocol.REPLY || output.type == PocketProtocol.REPLY_DONE)
                log.event(
                        "passport.reply",
                        "type="
                                + output.type
                                + " bytes="
                                + output.payload.length
                                + " quiet="
                                + output.quiet);
            link.send(
                    output.type,
                    output.payload,
                    output.quiet,
                    () ->
                            state.execute(
                                    () -> {
                                        if (ticket != roomEpoch) return;
                                        reply.handed(output);
                                        persist();
                                    }));
        }
        persist();
    }

    private void persist() {
        JSONArray a = new JSONArray();
        for (Wait w : waits.values())
            a.put(
                    Json.object(
                            "id",
                            w.id,
                            "press",
                            w.press,
                            "deadline",
                            w.deadline,
                            "row",
                            w.row,
                            "text",
                            w.text));
        prefs.edit()
                .putLong("replySerial", reply.serial)
                .putString("reply:" + roomIdentity, reply.json().toString())
                .putString("waits:" + roomIdentity, a.toString())
                .apply();
    }
}
