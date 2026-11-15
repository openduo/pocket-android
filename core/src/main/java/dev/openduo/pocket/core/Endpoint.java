// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import org.json.JSONObject;

/** Validated server identity. The bearer token is always bound to this origin. */
public final class Endpoint {
    public final String host;
    public final int port;
    public final boolean tls;

    public Endpoint(String host, int port, boolean tls) {
        if (host == null) throw new IllegalArgumentException("请填写主机地址");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应为 1–65535");
        JSONObject value;
        try {
            value =
                    RustProtocol.call(
                            Json.object("op", "endpoint", "host", host, "port", port, "tls", tls));
        } catch (RustProtocol.Failure failure) {
            throw new IllegalArgumentException("主机地址只能包含域名或 IP");
        }
        this.host = value.optString("host");
        this.port = port;
        this.tls = tls;
    }

    public String origin() {
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return (tls ? "https://" : "http://")
                + authority
                + (port == (tls ? 443 : 80) ? "" : ":" + port);
    }

    public String identity() {
        return origin().toLowerCase(java.util.Locale.ROOT);
    }

    public static String query(String text) {
        return RustProtocol.call(Json.object("op", "query", "text", text)).optString("query");
    }

    public static final class PairingLink {
        public final Endpoint endpoint;
        public final String code;

        public PairingLink(Endpoint endpoint, String code) {
            if (code == null || code.trim().isEmpty())
                throw new IllegalArgumentException("配对链接缺少配对码");
            this.endpoint = endpoint;
            this.code = code.trim();
        }

        public static PairingLink parse(String text) {
            try {
                JSONObject value =
                        RustProtocol.call(Json.object("op", "parse_pairing", "text", text));
                JSONObject endpoint = value.optJSONObject("endpoint");
                return new PairingLink(
                        new Endpoint(
                                endpoint.optString("host"),
                                endpoint.optInt("port"),
                                endpoint.optBoolean("tls")),
                        value.optString("code"));
            } catch (RustProtocol.Failure failure) {
                throw new IllegalArgumentException("配对链接不完整，请重新获取");
            }
        }
    }
}
