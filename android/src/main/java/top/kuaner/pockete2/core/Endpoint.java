// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated server identity. The bearer token is always bound to this origin. */
public final class Endpoint {
    public final String host;
    public final int port;
    public final boolean tls;

    public Endpoint(String host, int port, boolean tls) {
        if (host == null) throw new IllegalArgumentException("请填写主机地址");
        String clean = host.trim();
        if (clean.isEmpty()
                || clean.contains("/")
                || clean.contains("@")
                || clean.contains("?")
                || clean.contains("#")
                || clean.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("主机地址只能包含域名或 IP");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应为 1–65535");
        this.host = clean;
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
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
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
                int start = text.toLowerCase(java.util.Locale.ROOT).indexOf("duoduopocket://pair?");
                if (start < 0) throw new IllegalArgumentException("没有找到配对链接");
                String raw = text.substring(start).split("[\\s\"'<>“”「」]", 2)[0];
                URI uri = new URI(raw);
                Map<String, String> params = new LinkedHashMap<>();
                for (String param : uri.getRawQuery().split("&")) {
                    String[] pair = param.split("=", 2);
                    String key = URLDecoder.decode(pair[0], "UTF-8");
                    if (!params.containsKey(key))
                        params.put(key, pair.length > 1 ? URLDecoder.decode(pair[1], "UTF-8") : "");
                }
                String tlsValue =
                        params.getOrDefault("tls", "1").toLowerCase(java.util.Locale.ROOT);
                if (!tlsValue.matches("1|0|true|false"))
                    throw new IllegalArgumentException("配对链接的 TLS 设置无效");
                boolean tls = tlsValue.equals("1") || tlsValue.equals("true");
                int port = Integer.parseInt(params.getOrDefault("port", "443"));
                return new PairingLink(
                        new Endpoint(params.get("host"), port, tls), params.get("code"));
            } catch (IllegalArgumentException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalArgumentException("配对链接不完整，请重新获取");
            }
        }
    }
}
