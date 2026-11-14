// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.net;

import dev.openduo.pocket.core.Endpoint;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One immutable credential generation, shared by HTTP, outbox retries and speech. */
public final class RoomAccess implements ChannelTransport, AutoCloseable {
    private final ChannelConnection connection;
    private final Endpoint endpoint;
    private final String token, room, namespace;
    private final Consumer<RoomAccess> onRejected;
    private final Set<Long> requests = new HashSet<>(), streams = new HashSet<>();
    private volatile boolean active = true;
    private boolean rejected;

    public RoomAccess(
            ChannelConnection connection,
            Endpoint endpoint,
            String token,
            String room,
            String namespace,
            Consumer<RoomAccess> onRejected) {
        this.connection = connection;
        this.endpoint = endpoint;
        this.token = token;
        this.room = room;
        this.namespace = namespace;
        this.onRejected = onRejected;
    }

    public boolean matches(
            ChannelConnection connection,
            Endpoint endpoint,
            String token,
            String room,
            String namespace) {
        return this.connection == connection
                && this.endpoint.identity().equals(endpoint.identity())
                && this.token.equals(token)
                && this.room.equals(room)
                && this.namespace.equals(namespace);
    }

    public boolean current() {
        return active;
    }

    public synchronized boolean rejected() {
        return rejected;
    }

    public ChannelClient client() {
        return new ChannelClient(endpoint, token, room, this);
    }

    /** Uses this generation's already registered outbox lifetime without nesting registrations. */
    public ChannelClient client(RequestScope requests) {
        return new ChannelClient(endpoint, token, room, requests);
    }

    /** Ordinary requests register only when a worker dispatches, not while it is queued. */
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
        try (RequestScope scope = new RequestScope(this)) {
            return scope.request(endpoint, token, method, path, headers, body, timeoutMs);
        }
    }

    @Override
    public synchronized void beginRequest(long id) throws Exception {
        checkCurrent();
        connection.beginRequest(id);
        requests.add(id);
    }

    @Override
    public Response requestScoped(
            long id,
            Endpoint endpoint,
            String token,
            String method,
            String path,
            JSONObject headers,
            byte[] body,
            long timeoutMs)
            throws Exception {
        synchronized (this) {
            checkCurrent();
            if (!requests.contains(id)
                    || !this.endpoint.identity().equals(endpoint.identity())
                    || !token.equals(ChannelClient.needsToken(path) ? this.token : ""))
                throw new IOException("Request does not belong to this access");
        }
        Response response =
                connection.requestScoped(
                        id, endpoint, token, method, path, headers, body, timeoutMs);
        return admit(response, path, id);
    }

    @Override
    public synchronized void endRequest(long id) {
        if (requests.remove(id)) connection.endRequest(id);
    }

    public Response stream(
            long id,
            String path,
            byte[] body,
            ChannelConnection.StreamListener listener,
            BooleanSupplier current)
            throws Exception {
        synchronized (this) {
            checkCurrent();
            if (!current.getAsBoolean()) throw new IOException("Speech retired");
            streams.add(id);
        }
        try {
            Response response =
                    connection.stream(
                            endpoint,
                            token,
                            id,
                            path,
                            body,
                            bytes -> {
                                if (current() && current.getAsBoolean()) listener.data(bytes);
                            },
                            () -> current() && current.getAsBoolean());
            if (!current.getAsBoolean()) throw new IOException("Speech retired");
            return admit(response, path, 0);
        } finally {
            synchronized (this) {
                streams.remove(id);
            }
        }
    }

    private Response admit(Response response, String path, long request) throws IOException {
        boolean notify;
        synchronized (this) {
            checkCurrent();
            if (request != 0 && !requests.contains(request))
                throw new IOException("Request retired before response admission");
            notify =
                    onRejected != null
                            && !token.isEmpty()
                            && ChannelClient.needsToken(path)
                            && response.status == 401
                            && ChannelClient.failure(response).unauthorized();
            if (notify) {
                rejected = true;
                close();
            }
        }
        if (notify) onRejected.accept(this);
        return response;
    }

    private void checkCurrent() throws IOException {
        if (!active) throw new IOException("Room access retired");
    }

    @Override
    public synchronized void close() {
        if (!active) return;
        active = false;
        for (long id : requests) connection.endRequest(id);
        requests.clear();
        for (long id : streams) connection.cancelStream(id);
        streams.clear();
    }
}
