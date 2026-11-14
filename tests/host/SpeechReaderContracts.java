// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.audio.SpeechReader;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Stream generation, incremental framing, resource ownership and playback-drain contracts. */
final class SpeechReaderContracts {
    private static int checked;
    private static final byte[] packet = {(byte) 0xf8, (byte) 0xff, (byte) 0xfe};
    private static final byte[] framed = {3, 0, packet[0], packet[1], packet[2]};

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        successfulReadAndDrain();
        cancellationAndReplacement();
        errors();
        return checked;
    }

    private static void successfulReadAndDrain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.chunks =
                    Arrays.asList(
                            new byte[] {3},
                            new byte[] {0, packet[0]},
                            new byte[] {packet[1], packet[2], 3, 0},
                            packet);
            f.start("read");
            check(
                    f.reader.id().equals("read") && f.reader.inFlight() && f.opens == 0,
                    "state owns read identity before the worker opens any platform resources");
            f.time.worker.runNext();
            check(
                    f.opens == 1 && f.closes == 1 && f.completed == 0 && f.reader.inFlight(),
                    "the stream worker closes its decoder/output before completion crosses to"
                            + " state");
            f.time.drain();
            check(
                    f.source.path.equals("/api/speech?room=fixture%20room")
                            && new JSONObject(f.source.body).optString("text").equals("Read 中"),
                    "speech request preserves encoded room and literal UTF-8 message text");
            check(
                    f.packets.size() == 2
                            && f.packets.stream().allMatch(p -> Arrays.equals(p, packet))
                            && f.received
                            && f.failure == null
                            && f.completed == 1,
                    "fragmented stream yields each complete Opus packet once and one state"
                            + " completion");
            check(
                    !f.reader.inFlight() && f.reader.id().equals("read"),
                    "HTTP completion retains the identity while admitted playback drains");
            f.reader.cancel();
            check(
                    f.reader.id().isEmpty() && f.source.cancelled.isEmpty(),
                    "playback drain clears identity without cancelling a completed native stream");
        }
    }

    private static void cancellationAndReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("queued");
            f.reader.cancel();
            f.time.drain();
            check(
                    f.source.requests.isEmpty() && f.opens == 0 && f.completed == 0,
                    "a queued cancelled read never opens a decoder, native stream or completion");

            f.source.beforeResponse =
                    () -> {
                        f.reader.cancel();
                        f.start("replacement");
                    };
            f.source.status = 401;
            f.start("retired");
            f.time.worker.runNext();
            f.source.status = 200;
            f.time.drain();
            check(
                    f.reader.id().equals("replacement")
                            && !f.reader.inFlight()
                            && f.completed == 1
                            && f.failure == null
                            && f.source.rejections == 0
                            && f.source.access.current(),
                    "late speech success/unauthorized cannot finish or reject a replacement"
                            + " generation");
            check(
                    f.source.requests.size() == 2
                            && !f.source.requests.get(0).equals(f.source.requests.get(1))
                            && f.source.cancelled.equals(
                                    Collections.singletonList(f.source.requests.get(0)))
                            && f.opens == 2
                            && f.closes == 2,
                    "replacement cancels only the old stream and each worker releases its own"
                            + " output");
        }
        try (Fixture f = new Fixture()) {
            f.beforePacket = f.reader::cancel;
            f.source.chunks = Arrays.asList(framed, framed);
            f.start("backpressured");
            f.time.drain();
            check(
                    f.packets.isEmpty()
                            && f.completed == 0
                            && !f.reader.inFlight()
                            && f.source.cancelled.size() == 1
                            && f.opens == f.closes,
                    "cancellation reaches output admission and suppresses later uncooperative"
                            + " stream data");
        }
        try (Fixture f = new Fixture()) {
            f.start("finished-but-not-admitted");
            f.time.worker.runNext();
            f.reader.cancel();
            f.time.drain();
            check(
                    f.completed == 0 && f.reader.id().isEmpty() && f.source.cancelled.isEmpty(),
                    "cancelling between HTTP return and state admission suppresses the stale"
                            + " completion");
        }
        try (Fixture f = new Fixture()) {
            f.source.beforeResponse = f.source.access::close;
            f.start("retired-access");
            f.time.drain();
            check(
                    f.completed == 0 && f.source.cancelled.size() == 1 && f.closes == 1,
                    "credential retirement cancels streaming and rejects late completion");
            f.reader.cancel();
            check(
                    f.source.cancelled.size() == 1,
                    "reader cancellation cannot retire a native stream twice");
        }
    }

    private static void errors() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.chunks = Collections.singletonList(new byte[] {0, 0});
            f.start("invalid");
            f.time.drain();
            check(
                    !f.received
                            && f.failure instanceof IOException
                            && f.source.cancelled.size() == 1
                            && f.closes == 1
                            && f.completed == 1
                            && !f.reader.inFlight(),
                    "invalid packet framing cancels the native stream, releases output and reports"
                            + " failure");
        }
        try (Fixture f = new Fixture()) {
            f.source.chunks = Arrays.asList(framed, new byte[] {3, 0, packet[0]});
            f.start("truncated");
            f.time.drain();
            check(
                    f.received
                            && f.packets.size() == 1
                            && f.failure instanceof IOException
                            && f.reader.id().equals("truncated")
                            && f.closes == 1,
                    "partial failure retains admitted audio and identity for caller-owned"
                            + " draining");
        }
        try (Fixture f = new Fixture()) {
            f.source.status = 503;
            f.source.chunks = Collections.emptyList();
            f.start("unavailable");
            f.time.drain();
            check(
                    !f.received
                            && f.failure instanceof ChannelClient.Failure
                            && ((ChannelClient.Failure) f.failure).status == 503
                            && f.closes == 1,
                    "an initial service error retains its HTTP classification without claiming"
                            + " audio");
        }
        try (Fixture f = new Fixture()) {
            f.openFailure = new IOException("Fixture decoder unavailable");
            f.start("no-output");
            f.time.drain();
            check(
                    f.failure == f.openFailure
                            && !f.received
                            && f.source.requests.isEmpty()
                            && f.opens == 1
                            && f.closes == 0,
                    "output initialization failure reports on state without opening a native"
                            + " stream");
        }
        try (Fixture f = new Fixture()) {
            f.packetFailure = new IOException("Fixture corrupt Opus");
            f.start("decoder-failure");
            f.time.drain();
            check(
                    f.failure == f.packetFailure
                            && f.source.cancelled.size() == 1
                            && f.closes == 1
                            && f.completed == 1,
                    "decoder failure cancels upstream and closes on its stream owner");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncHarness time = new AsyncHarness();
        final Source source = new Source();
        final SpeechReader reader = new SpeechReader(time.state, time.worker);
        final List<byte[]> packets = new ArrayList<>();
        int opens, closes, completed;
        boolean received;
        Exception failure, openFailure, packetFailure;
        Runnable beforePacket;

        void start(String id) {
            reader.start(
                    Message.answer(id, "Read 中", true, false, 1, Collections.emptyList()),
                    source.access,
                    "fixture room",
                    () -> {
                        opens++;
                        if (openFailure != null) throw openFailure;
                        return new SpeechReader.Output() {
                            public boolean packet(byte[] opus, BooleanSupplier current)
                                    throws Exception {
                                Runnable interleave = beforePacket;
                                beforePacket = null;
                                if (interleave != null) interleave.run();
                                if (!current.getAsBoolean()) return false;
                                if (packetFailure != null) throw packetFailure;
                                packets.add(opus);
                                return true;
                            }

                            public void close() {
                                closes++;
                            }
                        };
                    },
                    (received, failure) -> {
                        check(!reader.inFlight(), "only state completion ends the in-flight phase");
                        completed++;
                        this.received = received;
                        this.failure = failure;
                    });
        }

        public void close() {
            reader.cancel();
            source.access.close();
            time.close();
        }
    }

    private static final class Source implements ChannelConnection {
        final RoomAccess access =
                new RoomAccess(
                        this,
                        new Endpoint("example.test", 443, true),
                        "fixture-token",
                        "fixture room",
                        "fixture",
                        ignored -> this.rejections++);
        final List<Long> requests = new ArrayList<>(), cancelled = new ArrayList<>();
        List<byte[]> chunks = Collections.singletonList(framed);
        int status = 200, rejections;
        String path, body;
        Runnable beforeResponse;

        public Response stream(
                Endpoint e,
                String t,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current) {
            requests.add(id);
            this.path = path;
            this.body = new String(body, StandardCharsets.UTF_8);
            int responseStatus = status;
            for (byte[] chunk : chunks) listener.data(chunk);
            Runnable interleave = beforeResponse;
            beforeResponse = null;
            if (interleave != null) interleave.run();
            return new Response(
                    responseStatus,
                    Json.object("error", "unauthorized")
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
        }

        public void cancelStream(long id) {
            cancelled.add(id);
        }

        public Response request(
                Endpoint e, String t, String m, String p, JSONObject h, byte[] b, long timeout) {
            throw new AssertionError("Speech reader cannot use buffered HTTP");
        }

        public void live(
                Endpoint e, String t, String p, JSONObject h, LiveListener l, BooleanSupplier c) {}

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] packet) {}

        public boolean isOpen() {
            return true;
        }

        public void closeLive() {}
    }
}
