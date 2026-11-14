// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import org.json.JSONObject;

import java.util.*;
import java.util.function.BooleanSupplier;

final class RoomLinkContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open();
            f.link.invalidate();
            f.time.drain();
            check(f.source.opens == 0, "retired queued dials never enter the transport");
            f.open();
            f.time.drain();
            check(
                    f.source.opens == 1 && f.opened == 1 && !f.link.opening(),
                    "one admitted dial ends opening on state");
            byte[] packet = {7};
            f.source.listener.binary(packet);
            packet[0] = 8;
            f.source.listener.text("old");
            f.link.invalidate();
            f.time.drain();
            check(
                    f.texts.isEmpty() && f.binary.isEmpty(),
                    "queued callbacks cannot cross a retired generation");
            f.open();
            f.time.drain();
            f.source.listener.binary(new byte[] {9});
            f.time.drain();
            check(f.binary.get(0)[0] == 9, "current binary callbacks own a copied frame");

            f.link.send(f.source, Json.object("type", "discard"));
            f.link.invalidate();
            f.writer.runNext();
            check(f.source.writes.isEmpty(), "retirement clears the bounded writer's queued work");
            f.open();
            f.time.drain();
            ChannelConnection.LiveListener old = f.source.listener;
            BooleanSupplier transportValid = f.source.current;
            f.link.reconnect(f.source, Json.object("type", "unmute"), () -> f.reconnected++);
            check(
                    f.link.closing() && f.link.opening() && transportValid.getAsBoolean(),
                    "final control preserves only the retiring native transport until its writer"
                            + " turn");
            old.text("obsolete");
            f.writer.runNext();
            f.time.drain();
            check(
                    f.source.writes.equals(Collections.singletonList("unmute"))
                            && f.reconnected == 1
                            && f.source.closes == 1
                            && !transportValid.getAsBoolean()
                            && f.texts.isEmpty(),
                    "final control finishes before close while old readers remain inadmissible");

            f.writer.occupied = true;
            f.link.send(f.source, Json.object("type", "blocked"));
            f.time.drain();
            check(
                    f.failures.equals(Collections.singletonList("实时连接拥塞")),
                    "a full writer reports congestion on state instead of silently dropping audio");
            f.link.reconnect(f.source, Json.object("type", "last"), () -> f.reconnected++);
            check(
                    !f.link.closing() && f.reconnected == 2,
                    "a rejected final control still closes and releases the reconnect handoff");
            f.writer.occupied = false;

            f.link.verify(f.source, () -> f.healthy++, ignored -> f.unhealthy++);
            f.link.invalidate();
            f.time.drain();
            check(f.source.pings == 0, "retired liveness checks never ping a replacement socket");
            f.link.verify(f.source, () -> f.healthy++, ignored -> f.unhealthy++);
            f.time.drain();
            check(f.healthy == 1 && f.source.pings == 1, "current ping completes on state");
        }
        return checked;
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncHarness time = new AsyncHarness();
        final AsyncHarness.Worker writer = new AsyncHarness.Worker();
        final Source source = new Source();
        final List<String> texts = new ArrayList<>(), failures = new ArrayList<>();
        final List<byte[]> binary = new ArrayList<>();
        final RoomLink link =
                new RoomLink(
                        time.state,
                        time.worker,
                        writer,
                        (epoch, reason) -> failures.add(reason),
                        ignored -> {});
        int opened, reconnected, healthy, unhealthy;

        void open() {
            link.open(
                    source,
                    new Endpoint("example.test", 443, true),
                    "fixture",
                    "/live",
                    Json.object(),
                    new RoomLink.Listener() {
                        public void text(String value) {
                            texts.add(value);
                        }

                        public void binary(byte[] value) {
                            binary.add(value);
                        }

                        public void closed(String reason) {
                            failures.add(reason);
                        }

                        public void opened() {
                            opened++;
                        }

                        public void failed(Exception failure) {
                            throw new AssertionError(failure);
                        }
                    });
        }

        public void close() {
            link.close();
            time.close();
        }
    }

    private static final class Source implements ChannelConnection {
        int opens, closes, pings;
        LiveListener listener;
        BooleanSupplier current;
        final List<String> writes = new ArrayList<>();

        public void live(
                Endpoint e, String t, String p, JSONObject h, LiveListener l, BooleanSupplier c) {
            opens++;
            listener = l;
            current = c;
        }

        public void ping(long timeout) {
            pings++;
        }

        public void send(JSONObject frame) {
            writes.add(frame.optString("type"));
        }

        public void sendBinary(byte[] bytes) {}

        public boolean isOpen() {
            return true;
        }

        public void closeLive() {
            closes++;
        }

        public void cancelStream(long id) {}

        public Response stream(
                Endpoint e,
                String t,
                long id,
                String p,
                byte[] b,
                StreamListener l,
                BooleanSupplier c) {
            throw new AssertionError();
        }

        public Response request(
                Endpoint e, String t, String m, String p, JSONObject h, byte[] b, long timeout) {
            throw new AssertionError();
        }
    }
}
