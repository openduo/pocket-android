// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.net;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Endpoint;

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
}
