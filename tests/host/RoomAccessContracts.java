// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.net.*;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

final class RoomAccessContracts {
    private static int checked;
    private static final Endpoint endpoint = new Endpoint("example.test", 443, true);

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    private static RoomAccess access(Source source, AtomicInteger rejected) {
        return new RoomAccess(
                source,
                endpoint,
                "qa-token",
                "qa",
                "qa-room",
                ignored -> rejected.incrementAndGet());
    }

    static int run() throws Exception {
        AtomicInteger rejected = new AtomicInteger();
        Source source = new Source();
        RoomAccess access = access(source, rejected);
        check(source.active.isEmpty(), "queued clients consume no native registrations");
        access.client().health();
        check(
                source.calls == 1 && source.active.isEmpty() && source.token.isEmpty(),
                "anonymous health uses a scoped request without a bearer token");
        for (int status : new int[] {401, 403, 500}) {
            source.response = source.json(status, "unauthorized");
            try {
                access.client().health();
            } catch (ChannelClient.Failure expected) {
            }
            check(
                    access.current() && rejected.get() == 0,
                    "an anonymous response cannot revoke the stored room token");
        }
        for (ChannelTransport.Response response :
                Arrays.asList(
                        source.json(401, "invalid_code"),
                        source.json(403, "unauthorized"),
                        new ChannelTransport.Response(401, new byte[0]))) {
            source.response = response;
            try {
                access.client().state();
            } catch (ChannelClient.Failure expected) {
            }
            check(
                    access.current() && rejected.get() == 0,
                    "only the exact upstream 401 unauthorized response rejects credentials");
        }
        source.response = source.json(401, "unauthorized");
        RequestScope send = new RequestScope(access);
        try {
            access.client(send)
                    .sendText(
                            "qa",
                            Collections.emptyList(),
                            "00000000-0000-4000-8000-000000000002",
                            "phone",
                            false,
                            "");
        } catch (ChannelClient.Failure expected) {
            check(expected.unauthorized(), "the original unauthorized status remains classified");
        }
        check(
                !access.current()
                        && access.rejected()
                        && rejected.get() == 1
                        && source.active.isEmpty(),
                "one rejection immediately retires all registered work before notifying the owner");
        int calls = source.calls;
        try {
            access.client().state();
        } catch (ChannelClient.Failure expected) {
        }
        send.close();
        access.close();
        check(
                source.calls == calls
                        && rejected.get() == 1
                        && source.ends.get() == source.begins.get(),
                "late work never starts and retirement ends each registration exactly once");

        Source retrySource = new Source();
        retrySource.response = new ChannelTransport.Response(202, new byte[0]);
        RoomAccess retry = access(retrySource, new AtomicInteger());
        try (RequestScope requests = new RequestScope(retry)) {
            ChannelClient client = retry.client(requests);
            for (int i = 0; i < 2; i++)
                client.sendText(
                        "qa",
                        Collections.emptyList(),
                        "00000000-0000-4000-8000-000000000002",
                        "phone",
                        false,
                        "");
            check(
                    retrySource.begins.get() == 1
                            && retrySource.active.size() == 1
                            && retrySource.ids.get(0).equals(retrySource.ids.get(1)),
                    "outbox retries retain their original registration without an inner scope");
        }
        check(retrySource.ends.get() == 1, "outbox settlement releases its one native lifetime");
        boolean bound = false;
        try {
            retry.request(
                    new Endpoint("other.test", 443, true),
                    "qa-token",
                    "GET",
                    "/api/state",
                    Json.object(),
                    new byte[0],
                    1000);
        } catch (IOException expected) {
            bound = true;
        }
        check(
                bound && retrySource.calls == 2,
                "a client cannot move a credential generation onto another origin");
        retry.close();

        ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            Source cancelled = new Source();
            cancelled.held = true;
            cancelled.response = cancelled.json(401, "unauthorized");
            AtomicInteger invalidatedSend = new AtomicInteger();
            RoomAccess sameCredential = access(cancelled, invalidatedSend);
            RequestScope discarded = new RequestScope(sameCredential);
            Future<Boolean> discardedResult =
                    worker.submit(
                            () -> {
                                try {
                                    sameCredential.client(discarded).state();
                                    return true;
                                } catch (ChannelClient.Failure expected) {
                                    return false;
                                }
                            });
            check(
                    cancelled.entered.await(1, TimeUnit.SECONDS),
                    "a cancellable operation dispatches");
            discarded.close();
            cancelled.release.countDown();
            check(
                    !discardedResult.get(1, TimeUnit.SECONDS)
                            && invalidatedSend.get() == 0
                            && sameCredential.current(),
                    "a retired individual request cannot reject the still-current credential"
                            + " generation");
            sameCredential.close();
            for (int status : new int[] {200, 401}) {
                Source held = new Source();
                held.held = true;
                held.response = status == 401 ? held.json(401, "unauthorized") : held.response;
                AtomicInteger invalidated = new AtomicInteger();
                RoomAccess retired = access(held, invalidated);
                Future<Boolean> finished =
                        worker.submit(
                                () -> {
                                    try {
                                        retired.client().state();
                                        return true;
                                    } catch (ChannelClient.Failure expected) {
                                        return false;
                                    }
                                });
                check(
                        held.entered.await(1, TimeUnit.SECONDS),
                        "the original generation is in flight");
                retired.close();
                RoomAccess replacement =
                        new RoomAccess(
                                held,
                                endpoint,
                                "qa-new",
                                "qa",
                                "qa-room",
                                ignored -> invalidated.incrementAndGet());
                held.release.countDown();
                check(
                        !finished.get(1, TimeUnit.SECONDS)
                                && invalidated.get() == 0
                                && replacement.current(),
                        "a retired success or unauthorized response cannot admit or revoke"
                                + " replacement access");
                check(
                        held.cancelled.get() == 1 && held.active.isEmpty(),
                        "retirement cancels ordinary HTTP while its worker is still returning");
                replacement.close();
            }
            Source streaming = new Source();
            streaming.held = true;
            RoomAccess reading = access(streaming, new AtomicInteger());
            AtomicInteger chunks = new AtomicInteger();
            Future<Boolean> finished =
                    worker.submit(
                            () -> {
                                try {
                                    reading.stream(
                                            41,
                                            "/api/speech?room=qa",
                                            new byte[0],
                                            bytes -> chunks.incrementAndGet(),
                                            () -> true);
                                    return true;
                                } catch (IOException expected) {
                                    return false;
                                }
                            });
            check(
                    streaming.entered.await(1, TimeUnit.SECONDS),
                    "speech is in flight under the same access");
            reading.close();
            streaming.release.countDown();
            check(
                    !finished.get(1, TimeUnit.SECONDS)
                            && streaming.cancelled.get() == 1
                            && chunks.get() == 0,
                    "retirement cancels speech and rejects its late bytes and result");
        } finally {
            worker.shutdownNow();
        }
        Source speech = new Source();
        speech.response = speech.json(401, "unauthorized");
        AtomicInteger speechRejected = new AtomicInteger();
        RoomAccess reading = access(speech, speechRejected);
        ChannelTransport.Response response =
                reading.stream(42, "/api/speech?room=qa", new byte[0], bytes -> {}, () -> true);
        check(
                ChannelClient.failure(response).unauthorized()
                        && !reading.current()
                        && speechRejected.get() == 1,
                "streamed speech uses the same exact unauthorized classification as ordinary HTTP");
        Source demo = new Source();
        demo.response = demo.json(401, "unauthorized");
        RoomAccess trying = new RoomAccess(demo, endpoint, "qa-token", "qa", "try", null);
        try {
            trying.client().state();
        } catch (ChannelClient.Failure expected) {
        }
        check(
                trying.current(),
                "a prepared Try rejection stays local and does not retire real access");
        trying.close();
        return checked;
    }

    private static final class Source implements ChannelConnection {
        final Set<Long> active = ConcurrentHashMap.newKeySet();
        final List<Long> ids = new ArrayList<>();
        final AtomicInteger begins = new AtomicInteger(),
                ends = new AtomicInteger(),
                cancelled = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean held;
        int calls;
        String token;
        Response response = new Response(200, "{}".getBytes(StandardCharsets.UTF_8));

        Response json(int status, String reason) {
            return new Response(
                    status,
                    Json.object("error", reason).toString().getBytes(StandardCharsets.UTF_8));
        }

        public void beginRequest(long id) {
            begins.incrementAndGet();
            active.add(id);
        }

        public void endRequest(long id) {
            if (!active.remove(id)) throw new AssertionError("Duplicate retirement");
            ends.incrementAndGet();
            if (held) cancelled.incrementAndGet();
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
            if (!active.contains(id)) throw new IOException("Retired before dispatch");
            ids.add(id);
            calls++;
            this.token = token;
            entered.countDown();
            if (held && !release.await(2, TimeUnit.SECONDS))
                throw new IOException("Fixture expired");
            return response;
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            throw new AssertionError("Unscoped HTTP");
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current)
                throws Exception {
            entered.countDown();
            if (held && !release.await(2, TimeUnit.SECONDS))
                throw new IOException("Fixture expired");
            listener.data(new byte[] {1});
            return response;
        }

        public void cancelStream(long id) {
            cancelled.incrementAndGet();
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current) {}

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] body) {}

        public boolean isOpen() {
            return false;
        }

        public void closeLive() {}
    }
}
