// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Exercises actual HTTP admission/retry/cancellation with controlled owner/worker interleavings.
 */
final class OutboxSenderContracts {
    private static int checked;
    private static final byte[] voice = {3, 0, (byte) 0xf8, (byte) 0xff, (byte) 0xfe};

    private static void check(boolean value, String message) {
        checked++;
        if (!value) throw new AssertionError(message);
    }

    static int run() throws Exception {
        admissionAndCancellation();
        retriesAndDeadlines();
        failures();
        return checked;
    }

    private static void admissionAndCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            Outbox.Item first = f.text("first");
            f.prepare(first, Long.MAX_VALUE);
            check(
                    f.sender.active()
                            && f.sender.item() == first
                            && f.source.active.size() == 1
                            && f.time.worker.getQueue().isEmpty()
                            && f.source.keys.isEmpty(),
                    "preparation registers cancellation before durable admission, without queuing"
                            + " I/O");
            f.sender.cancel(); // A failed SQLite transition never admits bytes.
            f.sender.cancel();
            f.sender.dispatch();
            f.time.drain();
            check(
                    !f.sender.active()
                            && f.source.ends == 1
                            && f.source.keys.isEmpty()
                            && f.results.isEmpty(),
                    "storage rejection can cancel a prepared send once without HTTP or completion");

            f.prepare(first, Long.MAX_VALUE);
            f.sender.dispatch();
            f.sender.dispatch();
            check(f.time.worker.getQueue().size() == 1, "dispatch is admitted only once");
            f.sender.cancel();
            f.prepare(f.text("replacement"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.equals(Collections.singletonList(requestId("replacement")))
                            && f.results.equals(Collections.singletonList(requestId("replacement")))
                            && f.errors.get(0) == null
                            && f.source.active.isEmpty(),
                    "a cancelled queued send never reaches HTTP or the replacement completion: "
                            + "keys="
                            + f.source.keys
                            + ", results="
                            + f.results
                            + ", errors="
                            + f.errors
                            + ", active="
                            + f.source.active);

            f.source.duringRequest =
                    () -> {
                        f.time.state.execute(
                                () -> {
                                    f.sender.cancel(); // Live settlement/discard before the HTTP
                                    // response.
                                    try {
                                        f.prepare(f.text("after-live"), Long.MAX_VALUE);
                                        f.sender.dispatch();
                                    } catch (Exception failure) {
                                        throw new AssertionError(failure);
                                    }
                                });
                        try {
                            f.time.state.runReady();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    };
            f.source.status = 401;
            f.prepare(f.text("discarded"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.worker.runNext(); // Body preparation.
            f.time.state.runReady(); // Queue HTTP.
            f.time.worker.runNext(); // Retire/replace while HTTP is returning.
            f.source.status = 202;
            f.time.drain();
            check(
                    !f.source.access.rejected()
                            && f.source.rejections == 0
                            && f.results.equals(
                                    Arrays.asList(
                                            requestId("replacement"), requestId("after-live"))),
                    "a retired unauthorized response cannot revoke credentials or finish the"
                            + " replacement");
            check(
                    f.source.begins == f.source.ends && f.source.active.isEmpty(),
                    "each live-first/cancelled/replaced request releases its registration exactly"
                            + " once");

            f.time.worker.occupied = true;
            f.prepare(f.text("occupied"), Long.MAX_VALUE);
            f.sender.dispatch();
            check(
                    f.time.state.delay() == 25 && f.source.active.size() == 1,
                    "an occupied bounded worker retains one current send instead of dropping it");
            f.time.advance(25);
            check(
                    f.time.state.delay() == 25 && f.source.keys.size() == 3,
                    "worker retirement waits do not grow the queue or start extra HTTP");
            f.time.worker.occupied = false;
            f.time.advance(25);
            check(
                    f.results.get(f.results.size() - 1).equals(requestId("occupied"))
                            && !f.sender.active(),
                    "the same retained operation resumes when the worker becomes available");
            f.time.worker.occupied = true;
            f.prepare(f.text("cancel-wait"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.sender.cancel();
            f.time.worker.occupied = false;
            f.time.advance(25);
            check(
                    !f.source.keys.contains(requestId("cancel-wait")) && f.time.state.delay() == -1,
                    "retirement cancels a scheduled worker-admission wait");
        }
    }

    private static void retriesAndDeadlines() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.status = 503;
            f.prepare(f.note("voice"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.size() == 1 && f.time.state.delay() == 500 && f.results.isEmpty(),
                    "voice server failure retains the active upload for bounded backoff");
            Files.delete(f.file.toPath());
            f.source.transportFailure = true;
            f.time.advance(500);
            check(
                    f.source.keys.size() == 2 && f.time.state.delay() == 1000,
                    "voice transport failure uses the same upload and increasing backoff");
            f.source.transportFailure = false;
            f.source.status = 202;
            f.time.advance(1000);
            check(
                    f.results.equals(Collections.singletonList(requestId("voice")))
                            && f.errors.get(0) == null,
                    "a successful retry finishes once without rereading a removed side file");
            check(
                    f.source.keys.equals(
                                    Arrays.asList(
                                            requestId("voice"),
                                            requestId("voice"),
                                            requestId("voice")))
                            && f.source.bodies.stream().allMatch(b -> Arrays.equals(b, voice))
                            && f.source.headers.stream()
                                    .allMatch(
                                            h ->
                                                    h.optString("X-Ambient-Display").equals("later")
                                                            && h.optString("X-Ambient-Spoken")
                                                                    .equals("1")
                                                            && h.optString("X-Ambient-Place")
                                                                    .equals("fixture-place"))
                            && f.source.ids.stream().distinct().count() == 1,
                    "body, key, surface and native request lifetime stay fixed across retries");
            check(
                    f.source.timeouts.equals(Arrays.asList(25_000L, 24_500L, 23_500L))
                            && f.source.begins == 1
                            && f.source.ends == 1,
                    "preparation, I/O and backoff share one monotonic 25-second budget");
        }
        try (Fixture f = new Fixture()) {
            f.prepare(f.note("expired-preparation"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.advance(25_000);
            check(
                    f.source.keys.isEmpty() && f.errors.size() == 1 && f.errors.get(0).transport,
                    "preparation or scheduler delay consumes the budget before any HTTP");
        }
        try (Fixture f = new Fixture()) {
            f.source.status = 503;
            f.prepare(f.note("passport"), 600);
            f.sender.dispatch();
            f.time.drain();
            f.time.advance(500);
            check(
                    f.source.timeouts.equals(Arrays.asList(600L, 100L))
                            && f.time.state.delay() == 100,
                    "Passport's remaining duration caps HTTP and backoff in the sender clock"
                            + " domain");
            f.time.advance(100);
            check(
                    f.source.keys.size() == 2
                            && f.errors.get(0).status == 503
                            && !f.sender.active(),
                    "the Passport deadline ends retry without an extra request or full backoff");
        }
        try (Fixture f = new Fixture()) {
            f.prepare(f.note("already-expired"), 0);
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.isEmpty() && f.errors.get(0).transport,
                    "an already-expired accessory deadline cannot upload a note");
        }
    }

    private static void failures() throws Exception {
        for (int status : new int[] {401, 403, 413, 422}) {
            try (Fixture f = new Fixture()) {
                f.source.status = status;
                f.prepare(f.note("voice-failure"), Long.MAX_VALUE);
                f.sender.dispatch();
                f.time.drain();
                check(
                        f.errors.size() == 1
                                && f.errors.get(0).status == status
                                && f.source.keys.size() == 1
                                && f.time.state.delay() == -1,
                        "voice client failures finish without automatic retry: " + status);
                check(
                        f.source.active.isEmpty() && f.source.ends == 1,
                        "a failed upload retires its native lifetime: " + status);
            }
        }
        try (Fixture f = new Fixture()) {
            f.source.status = 503;
            f.prepare(f.text("text-failure"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.size() == 1
                            && f.errors.get(0).status == 503
                            && f.time.state.delay() == -1,
                    "text preserves its existing no-automatic-retry policy");
        }
        try (Fixture f = new Fixture()) {
            Files.write(f.file.toPath(), new byte[] {0, 0});
            f.prepare(f.note("invalid-body"), Long.MAX_VALUE);
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.isEmpty()
                            && !f.errors.get(0).transport
                            && f.source.active.isEmpty(),
                    "invalid retained voice framing fails locally and closes the scope without"
                            + " HTTP");
        }
        try (Fixture f = new Fixture()) {
            f.prepare(f.text("retired-room"), Long.MAX_VALUE);
            f.source.access.close();
            f.sender.dispatch();
            f.time.drain();
            check(
                    f.source.keys.isEmpty() && f.errors.get(0).transport && f.source.ends == 1,
                    "queued work cannot dispatch through a retired room credential generation");
            boolean refused = false;
            try {
                f.prepare(f.text("closed-access"), Long.MAX_VALUE);
            } catch (IOException expected) {
                refused = true;
            }
            check(
                    refused && !f.sender.active(),
                    "failed scope registration leaves the sender idle");
            f.sender.close();
            refused = false;
            try {
                f.prepare(f.text("closed-owner"), Long.MAX_VALUE);
            } catch (IllegalStateException expected) {
                refused = true;
            }
            check(refused, "a closed owner cannot restart its worker");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncHarness time = new AsyncHarness();
        final Source source = new Source();
        final OutboxSender sender = new OutboxSender(time.state, time.worker, () -> time.now);
        final List<String> results = new ArrayList<>();
        final List<ChannelClient.Failure> errors = new ArrayList<>();
        final File file = File.createTempFile("pocket-sender-", ".packets");

        Fixture() throws Exception {
            Files.write(file.toPath(), voice);
        }

        Outbox.Item text(String label) {
            return new Outbox.Item(
                            requestId(label),
                            "fixture",
                            false,
                            "",
                            "",
                            0,
                            Collections.emptyList(),
                            Message.QUEUED,
                            "",
                            "",
                            0,
                            0)
                    .surface("later", true, "fixture-place");
        }

        Outbox.Item note(String label) {
            return new Outbox.Item(
                            requestId(label),
                            "",
                            true,
                            file.getPath(),
                            "phone",
                            400,
                            Collections.emptyList(),
                            Message.QUEUED,
                            "",
                            "",
                            0,
                            0)
                    .surface("later", true, "fixture-place");
        }

        void prepare(Outbox.Item item, long allowance) throws Exception {
            sender.prepare(
                    item,
                    source.access,
                    null,
                    allowance,
                    (sent, error) -> {
                        check(
                                !sender.active() && source.active.isEmpty(),
                                "completion runs after the operation and native scope are retired");
                        results.add(sent.id);
                        errors.add(error);
                    });
        }

        public void close() {
            sender.close();
            source.access.close();
            time.close();
            file.delete();
        }
    }

    private static String requestId(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static final class Source implements ChannelConnection {
        final RoomAccess access =
                new RoomAccess(
                        this,
                        new Endpoint("example.test", 443, true),
                        "fixture-token",
                        "fixture-room",
                        "fixture",
                        ignored -> this.rejections++);
        final Set<Long> active = new HashSet<>();
        final List<String> keys = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final List<JSONObject> headers = new ArrayList<>();
        final List<Long> ids = new ArrayList<>(), timeouts = new ArrayList<>();
        int status = 202, begins, ends, rejections;
        boolean transportFailure;
        Runnable duringRequest;

        public void beginRequest(long id) {
            begins++;
            active.add(id);
        }

        public void endRequest(long id) {
            if (!active.remove(id)) throw new AssertionError("Duplicate scope retirement");
            ends++;
        }

        public Response requestScoped(
                long id,
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            if (!active.contains(id)) throw new IOException("Retired fixture scope");
            keys.add(headers.optString("Idempotency-Key"));
            bodies.add(body.clone());
            this.headers.add(headers);
            ids.add(id);
            timeouts.add(timeout);
            int responseStatus = status;
            Runnable interleave = duringRequest;
            duringRequest = null;
            if (interleave != null) interleave.run();
            if (transportFailure) throw new IOException("Fixture transport failure");
            return new Response(
                    responseStatus,
                    Json.object("error", responseStatus == 401 ? "unauthorized" : "fixture")
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
        }

        public Response request(
                Endpoint e, String t, String m, String p, JSONObject h, byte[] b, long timeout) {
            throw new AssertionError("Sender must use its registered scope");
        }

        public Response stream(
                Endpoint e,
                String t,
                long id,
                String p,
                byte[] b,
                StreamListener l,
                BooleanSupplier c) {
            throw new AssertionError("No speech in sender contract");
        }

        public void cancelStream(long id) {}

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
