// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.net;

import android.content.Context;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Endpoint;

import tsbridge.LiveHandler;
import tsbridge.Tsbridge;

import java.io.File;

/** Upstream tsnet bridge bound with gomobile. No VPN profile or inbound listening socket. */
public final class GoTransport implements ChannelConnection {
    public interface StatusListener {
        void status(JSONObject status);
    }

    private final Object targetLock = new Object();
    private final Context context;
    private volatile boolean started;
    private volatile StatusListener statusListener;

    public GoTransport(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start(boolean logging, StatusListener listener) throws Exception {
        synchronized (targetLock) {
            statusListener = listener;
            if (started) {
                reportStatus();
                return;
            }
            go.Seq.setContext(context);
            Tsbridge.setLogging(logging);
            Tsbridge.setEventHandler(
                    line -> {
                        if (line.startsWith(Tsbridge.StatusPrefix)) {
                            try {
                                StatusListener current = statusListener;
                                if (current != null)
                                    current.status(
                                            new JSONObject(
                                                    line.substring(
                                                            Tsbridge.StatusPrefix.length())));
                            } catch (org.json.JSONException ignored) {
                            }
                        }
                    });
            Tsbridge.start(
                    new File(context.getNoBackupFilesDir(), "tailnet").getPath(), "pocket-e2");
            started = true;
        }
        reportStatus();
    }

    public void reportStatus() {
        try {
            StatusListener current = statusListener;
            if (current != null) current.status(new JSONObject(Tsbridge.status()));
        } catch (org.json.JSONException ignored) {
        }
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
        if (!started) throw new java.io.IOException("网络节点尚未启动");
        Tsbridge.up(Math.min(timeoutMs, 15_000));
        tsbridge.Response response =
                Tsbridge.doAt(
                        endpoint.host,
                        endpoint.port,
                        endpoint.tls,
                        token,
                        method,
                        path,
                        headers.toString(),
                        body,
                        timeoutMs);
        if (response == null) throw new java.io.IOException("通道没有返回响应");
        return new Response((int) response.getStatus(), response.getBody());
    }

    public Response stream(
            Endpoint endpoint,
            String token,
            long id,
            String path,
            byte[] body,
            StreamListener handler,
            java.util.function.BooleanSupplier current)
            throws Exception {
        if (!current.getAsBoolean()) return new Response(499, new byte[0]);
        if (!started) throw new java.io.IOException("网络节点尚未启动");
        tsbridge.Response result =
                Tsbridge.streamAt(
                        endpoint.host,
                        endpoint.port,
                        endpoint.tls,
                        token,
                        id,
                        "POST",
                        path,
                        "{\"Content-Type\":\"application/json\"}",
                        body,
                        bytes -> {
                            if (current.getAsBoolean()) handler.data(bytes);
                        });
        return new Response((int) result.getStatus(), result.getBody());
    }

    public void cancelStream(long id) {
        Tsbridge.streamCancel(id);
    }

    public void live(
            Endpoint endpoint,
            String token,
            String path,
            JSONObject headers,
            LiveListener listener,
            java.util.function.BooleanSupplier current)
            throws Exception {
        synchronized (targetLock) {
            Tsbridge.setTarget(endpoint.host, endpoint.port, endpoint.tls, token);
            Tsbridge.up(15_000);
            if (!current.getAsBoolean()) return;
            Tsbridge.setLiveHandler(
                    new LiveHandler() {
                        @Override
                        public void onLiveText(String text) {
                            listener.text(text);
                        }

                        @Override
                        public void onLiveBinary(byte[] bytes) {
                            listener.binary(bytes);
                        }

                        @Override
                        public void onLiveClosed(String reason) {
                            listener.closed(reason);
                        }
                    });
            Tsbridge.liveOpen(path, headers.toString(), 20_000);
            if (!current.getAsBoolean()) Tsbridge.liveClose();
        }
    }

    public void send(JSONObject frame) throws Exception {
        Tsbridge.liveSendText(frame.toString());
    }

    public void sendBinary(byte[] packet) throws Exception {
        Tsbridge.liveSendBinary(packet);
    }

    public boolean isOpen() {
        return started && Tsbridge.liveIsOpen();
    }

    @Override
    public void ping(long timeoutMs) throws Exception {
        if (!started) throw new java.io.IOException("网络节点尚未启动");
        Tsbridge.livePing(timeoutMs);
    }

    public void closeLive() {
        if (started) Tsbridge.liveClose();
    }

    @Override
    public void rebind() {
        if (started) {
            Tsbridge.rebind();
            Tsbridge.closeIdle();
        }
    }

    public void logout() throws Exception {
        closeLive();
        Tsbridge.logout();
        reportStatus();
    }

    public void login() throws Exception {
        Tsbridge.login();
        reportStatus();
    }

    public void logging(boolean value) {
        Tsbridge.setLogging(value);
    }
}
