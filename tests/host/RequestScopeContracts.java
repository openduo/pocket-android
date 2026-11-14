// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.net.ChannelTransport;
import dev.openduo.pocket.net.RequestScope;

import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

final class RequestScopeContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        Endpoint endpoint = new Endpoint("example.test", 443, true);
        Fixture fixture = new Fixture();
        RequestScope scope = new RequestScope(fixture);
        long id = fixture.began.get(0);
        JSONObject headers = Json.object("Idempotency-Key", "qa-fixed");
        byte[] bytes = {1, 2, 3};
        for (int i = 0; i < 2; i++)
            scope.request(endpoint, "qa-token", "POST", "/api/messages", headers, bytes, 1200 - i);
        check(
                fixture.calls.equals(Arrays.asList(id, id)) && fixture.active.contains(id),
                "retries reuse their registered request scope until settlement");
        check(
                fixture.endpoint == endpoint
                        && fixture.headers == headers
                        && fixture.body == bytes
                        && fixture.timeout == 1199
                        && fixture.token.equals("qa-token"),
                "scoping preserves the exact origin, token, headers, body and remaining deadline");
        scope.close();
        scope.close();
        check(
                fixture.ended.equals(Collections.singletonList(id)) && fixture.active.isEmpty(),
                "settlement retires a request once without keeping cancellation tombstones");
        boolean prevented = false;
        try {
            scope.request(endpoint, "", "POST", "/", headers, bytes, 1000);
        } catch (IOException expected) {
            prevented = true;
        }
        check(
                prevented && fixture.calls.size() == 2,
                "queued work cannot start after its scope was closed");
        RequestScope replacement = new RequestScope(fixture);
        check(fixture.began.get(1) != id, "the next send receives another request lifetime");
        scope.close();
        replacement.request(endpoint, "", "POST", "/", headers, bytes, 1000);
        check(
                fixture.active.size() == 1,
                "late retirement of the prior send cannot cancel its replacement");
        replacement.close();

        Fixture racing = new Fixture();
        racing.held = true;
        RequestScope retired = new RequestScope(racing);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> sent =
                    worker.submit(
                            () -> {
                                try {
                                    retired.request(
                                            endpoint, "", "POST", "/", headers, bytes, 1000);
                                    return true;
                                } catch (IOException expected) {
                                    return false;
                                }
                            });
            check(
                    racing.entered.await(1, TimeUnit.SECONDS),
                    "dispatch reaches the registration boundary before the race");
            retired.close();
            racing.release.countDown();
            check(
                    !sent.get(1, TimeUnit.SECONDS) && racing.calls.isEmpty(),
                    "retirement between Java's open check and native dispatch prevents the actual"
                            + " request");
        } finally {
            retired.close();
            racing.release.countDown();
            worker.shutdownNow();
        }
        return checked;
    }

    private static final class Fixture implements ChannelTransport {
        final List<Long> began = new ArrayList<>(),
                ended = new ArrayList<>(),
                calls = new ArrayList<>();
        final Set<Long> active = ConcurrentHashMap.newKeySet();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean held;
        Endpoint endpoint;
        JSONObject headers;
        byte[] body;
        long timeout;
        String token;

        public void beginRequest(long id) {
            began.add(id);
            active.add(id);
        }

        public void endRequest(long id) {
            ended.add(id);
            active.remove(id);
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
            if (held) {
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Fixture expired");
            }
            if (!active.contains(id)) throw new IOException("Request retired");
            calls.add(id);
            this.endpoint = endpoint;
            this.headers = headers;
            this.body = body;
            this.timeout = timeout;
            this.token = token;
            return new Response(202, new byte[0]);
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            throw new AssertionError("Unscoped send");
        }
    }
}
