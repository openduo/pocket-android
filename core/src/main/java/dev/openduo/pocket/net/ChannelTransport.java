// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.net;

import dev.openduo.pocket.core.Endpoint;

import org.json.JSONObject;

/** Injectable boundary used by the channel contract tests and native Tailscale adapter. */
public interface ChannelTransport {
    final class Response {
        public final int status;
        public final byte[] body;

        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }
    }

    Response request(
            Endpoint endpoint,
            String token,
            String method,
            String path,
            JSONObject headers,
            byte[] body,
            long timeoutMs)
            throws Exception;

    /** Registers a send before it enters an I/O queue, so retirement also prevents a late start. */
    default void beginRequest(long id) throws Exception {}

    default Response requestScoped(
            long id,
            Endpoint endpoint,
            String token,
            String method,
            String path,
            JSONObject headers,
            byte[] body,
            long timeoutMs)
            throws Exception {
        return request(endpoint, token, method, path, headers, body, timeoutMs);
    }

    /** Retires registration and cancels startup, headers or body reads already in progress. */
    default void endRequest(long id) {}
}
