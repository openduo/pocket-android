// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import dev.openduo.pocket.text.Markdown;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

/** Android integration; all protocol reducers run on the controller's single state owner. */
public final class PassportCoordinator {
    public interface Listener {
        void changed();

        void press(PressAssembler.Press press);

        void expired(String message);

        void capturing(boolean active);

        void wake(boolean catchUp);
    }

    public static final class Snapshot {
        public final PassportLink.Snapshot link;
        public final String firmware, latestText;
        public final int major, minor, battery, charging;
        public final boolean compatible, preroll, waiting, busy;
        public final long lastPress;

        Snapshot(PassportCoordinator c) {
            link = c.link.snapshot();
            firmware = c.firmware;
            major = c.major;
            minor = c.minor;
            battery = c.battery;
            charging = c.charging;
            compatible = !c.hasInfo || c.major == PocketProtocol.MAJOR;
            preroll = c.preroll;
            latestText = Markdown.plain(c.reply.latestText);
            waiting = c.reply.waiting;
            busy = waiting || c.results.size() > 0 || c.press.active() >= 0;
            lastPress = c.lastPress;
        }
    }

    private final PassportLink link;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Executor state;
    private final Context context;
    private final dev.openduo.pocket.diagnostics.AppLog log;
    private final PressAssembler press = new PressAssembler();
    private final ReplyTracker reply = new ReplyTracker();
    private final PassportResults results = new PassportResults();
    private final List<JSONObject> replayRows = new ArrayList<>();
    private boolean foreground,
            online,
            replaying,
            replayHasCursor,
            hasInfo,
            linkWasReady,
            suspended;
    private long roomEpoch, lastPress;
    private String roomIdentity = "", firmware = "";
    private int major, minor, battery = -1, charging = 255;
    private boolean preroll;

    public PassportCoordinator(Context context, Executor state, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.state = state;
        log = dev.openduo.pocket.diagnostics.AppLog.get(context);
        prefs = context.getSharedPreferences("passport.memory", Context.MODE_PRIVATE);
        reply.serial = prefs.getLong("replySerial", 0);
        link =
                new PassportLink(
                        context,
                        new PassportLink.Listener() {
                            public void state(PassportLink.Snapshot snapshot) {
                                state.execute(
                                        () -> {
                                            log.event(
                                                    "passport.link",
                                                    snapshot.state + " mtu=" + snapshot.mtu);
                                            if (snapshot.ready && !linkWasReady && !suspended) {
                                                hasInfo = false;
                                                appState();
                                                forward(reply.linkReady(foreground));
                                                listener.wake(true);
                                            }
                                            linkWasReady = snapshot.ready;
                                            listener.changed();
                                        });
                            }

                            public void message(PocketProtocol.Message message) {
                                state.execute(
                                        () -> {
                                            if (!suspended) receive(message);
                                        });
                            }

                            public void disconnected() {
                                state.execute(
                                        () -> {
                                            if (suspended) return;
                                            for (PressAssembler.Press completed : press.lost())
                                                listener.press(completed);
                                            listener.capturing(false);
                                            listener.changed();
                                        });
                            }
                        });
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public boolean backgroundEnabled() {
        return !suspended && link.snapshot().remembered && link.permitted();
    }

    public void room(String identity) {
        if (roomIdentity.equals(identity)) return;
        forward(reply.connectionFailed());
        results.clear();
        roomEpoch++;
        press.lost();
        listener.capturing(false);
        reply.resetRoom();
        roomIdentity = identity;
        replayRows.clear();
        replaying = false;
        try {
            reply.restore(new JSONObject(prefs.getString("reply:" + identity, "{}")));
        } catch (org.json.JSONException ignored) {
        }
    }

    public void suspended(boolean value) {
        suspended = value;
        if (value) {
            forward(reply.connectionFailed());
            results.clear();
            press.lost();
            listener.capturing(false);
            link.pause();
        } else if (foreground) link.resume();
    }

    public void foreground(boolean value) {
        foreground = value;
        if (value && !suspended) link.resume();
    }

    public void online(boolean value) {
        if (online != value) {
            online = value;
            appState();
        }
    }

    public void connectionFailed() {
        online(false);
        forward(reply.connectionFailed());
        listener.changed();
    }

    public void scan() {
        if (!suspended) link.scan();
    }

    public void resume() {
        if (!suspended) link.resume();
    }

    public void connect(String address) {
        if (!suspended) link.connect(address);
    }

    public void forget() {
        link.forget();
        results.clear();
        reply.newPress();
        press.lost();
        listener.capturing(false);
        persist();
        listener.changed();
    }

    private void appState() {
        link.send(
                PocketProtocol.APP_STATE,
                PocketProtocol.appState(
                        hasInfo && major != PocketProtocol.MAJOR ? 2 : online ? 0 : 1,
                        dev.openduo.pocket.text.AppText.get(context).en));
    }

    public void languageChanged() {
        if (link.snapshot().ready) appState();
    }

    private static int charging(int wire) {
        return wire == 0 || wire == 1 ? wire : 255;
    }

    private void receive(PocketProtocol.Message message) {
        byte[] data = message.data;
        if (message.type == PocketProtocol.INFO) {
            boolean wasRefused = hasInfo && major != PocketProtocol.MAJOR;
            int previous = major;
            major = data[0] & 255;
            minor = data[1] & 255;
            hasInfo = true;
            int size = data[2] & 255;
            firmware = new String(data, 3, size, StandardCharsets.UTF_8);
            battery = data[3 + size] & 255;
            charging = charging(data[4 + size] & 255);
            preroll = data[5 + size] != 0;
            if (major != PocketProtocol.MAJOR && (!wasRefused || previous != major)) appState();
            else if (wasRefused && major == PocketProtocol.MAJOR) appState();
        } else if (message.type == PocketProtocol.STATUS) {
            battery = data[0] & 255;
            charging = charging(data[1] & 255);
        } else if (!hasInfo || major == PocketProtocol.MAJOR) {
            boolean started =
                    message.type == PocketProtocol.PRESS_START
                            || message.type == PocketProtocol.AUDIO
                                    && press.active() != PocketProtocol.u16(data, 0);
            // The assembler emits completion before start on a superseding press.
            List<PressAssembler.Press> completedPresses = press.handle(message);
            for (PressAssembler.Press completed : completedPresses) listener.press(completed);
            if (started) {
                reply.newPress();
                lastPress = System.currentTimeMillis();
                listener.capturing(true);
                listener.wake(false);
            }
            if (message.type == PocketProtocol.AUDIO && !started && completedPresses.isEmpty())
                return;
            if (press.active() < 0) listener.capturing(false);
            if (message.type == PocketProtocol.KEEPALIVE) {
                reply.adopt();
                listener.wake(false);
            }
        }
        persist();
        listener.changed();
    }

    public void expect(String id, int pressId) {
        results.expect(id, pressId, SystemClock.elapsedRealtime());
        persist();
        listener.changed();
    }

    public long remainingMs(String id) {
        return results.remainingMs(id, SystemClock.elapsedRealtime());
    }

    public void rejected(int pressId, int code) {
        link.send(PocketProtocol.RESULT, PocketProtocol.result(pressId, code, ""));
    }

    public void accepted(String id) {
        settle(results.accepted(id));
    }

    public void uploadFailed(String id, boolean transport) {
        settle(results.failed(id, transport));
    }

    public void tick() {
        List<PassportResults.Settlement> expired =
                results.expire(SystemClock.elapsedRealtime(), online);
        settle(expired);
        for (PassportResults.Settlement item : expired) listener.expired(item.messageId);
    }

    private void settle(List<PassportResults.Settlement> settlements) {
        for (PassportResults.Settlement item : settlements) {
            log.event(
                    "passport.result",
                    "code=" + item.result.code + " chars=" + item.result.text.length());
            link.send(
                    PocketProtocol.RESULT,
                    PocketProtocol.result(item.pressId, item.result.code, item.result.text));
            if (item.result.code == 0) {
                reply.expect(item.messageId);
                listener.wake(false);
            }
        }
        if (!settlements.isEmpty()) {
            persist();
            listener.changed();
        }
    }

    public void beginReplay(boolean hasCursor) {
        results.beginConnection();
        replaying = true;
        replayHasCursor = hasCursor;
        replayRows.clear();
    }

    public void frame(JSONObject frame) {
        if (suspended) return;
        String type = frame.optString("type");
        settle(results.frame(frame));
        if (type.equals("row")) {
            JSONObject row = frame.optJSONObject("row");
            if (row != null) {
                if (replaying) replayRows.add(row);
                else
                    forward(
                            reply.rows(
                                    Collections.singletonList(row),
                                    foreground,
                                    System.currentTimeMillis()));
            }
        } else if (type.equals("ready")) {
            forward(reply.restoreTurn(frame.optJSONObject("turn")));
            if (!replayHasCursor) reply.forgetDelivered();
            forward(reply.rows(replayRows, foreground, System.currentTimeMillis()));
            replaying = false;
            replayRows.clear();
        } else {
            if (type.equals("message")
                    && frame.optString("state").equals("failed")
                    && frame.optString("reason").equals("brain_unreachable"))
                forward(reply.messageFailed(frame.optString("id")));
            forward(reply.handle(frame, foreground, System.currentTimeMillis()));
        }
        persist();
        listener.changed();
    }

    public void caughtUp(List<JSONObject> rows) {
        if (suspended) return;
        for (JSONObject row : rows)
            settle(results.frame(dev.openduo.pocket.core.Json.object("type", "row", "row", row)));
        forward(reply.rows(rows, foreground, System.currentTimeMillis()));
        listener.changed();
    }

    private void forward(List<ReplyTracker.Output> messages) {
        long ticket = roomEpoch;
        for (ReplyTracker.Output output : messages) {
            boolean quiet =
                    output.type == PocketProtocol.REPLY
                            && reply.quiet(PocketProtocol.u32(output.payload, 0), foreground);
            boolean queued = link.send(output.type, output.payload, quiet);
            // Match iOS's handoff boundary: write-without-response has no device acknowledgement.
            if (queued && ticket == roomEpoch) reply.handed(output);
        }
        persist();
    }

    private void persist() {
        prefs.edit()
                .putLong("replySerial", reply.serial)
                .putString("reply:" + roomIdentity, reply.json().toString())
                .apply();
    }
}
