// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.net;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Endpoint;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One outbox operation's cancellable requests, shared across its original-body retries. */
public final class RequestScope implements ChannelTransport, AutoCloseable {
    private static final AtomicLong ids = new AtomicLong();
    private final long id = ids.incrementAndGet();
    private final ChannelTransport transport;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RequestScope(ChannelTransport transport) throws Exception {
        this.transport = transport;
        transport.beginRequest(id);
    }

    @Override
    public Response request(
            Endpoint endpoint,
            String token,
            String method,
            String path,
            JSONObject headers,
            byte[] body,
            long timeoutMs)
            throws Exception {
        if (closed.get()) throw new IOException("Request retired");
        return transport.requestScoped(id, endpoint, token, method, path, headers, body, timeoutMs);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) transport.endRequest(id);
    }
}
