// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.net;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Owns network authorization intent and status; room policy observes changes on its state owner.
 */
public final class TailnetSession {
    public interface Listener {
        void changed();

        void failed(String reason);
    }

    private final GoTransport transport;
    private final Executor state, network;
    private final Listener listener;
    private final Consumer<String> log;
    private String backend = "NotStarted", authUrl = "";
    private boolean logoutRequested;
    private volatile long actionEpoch;

    public TailnetSession(
            GoTransport transport,
            Executor state,
            Executor network,
            Listener listener,
            Consumer<String> log) {
        this.transport = transport;
        this.state = state;
        this.network = network;
        this.listener = listener;
        this.log = log;
    }

    public String backend() {
        return backend;
    }

    public String authUrl() {
        return authUrl;
    }

    public boolean needsLogin() {
        return logoutRequested || backend.startsWith("Needs");
    }

    public boolean running() {
        return !logoutRequested && backend.equals("Running");
    }

    public void status(JSONObject value) {
        String previous = backend;
        backend = value.optString("backend_state", "NotStarted");
        if (logoutRequested && backend.equals("Running")) backend = "NeedsLogin";
        if (!previous.equals(backend)) log.accept(backend);
        authUrl = logoutRequested ? "" : value.optString("auth_url");
        listener.changed();
    }

    public void start(boolean logging) {
        start(logging, false);
    }

    public void login(boolean logging) {
        logoutRequested = false;
        actionEpoch++;
        start(logging, true);
    }

    private void start(boolean logging, boolean interactive) {
        long ticket = actionEpoch;
        network.execute(
                () -> {
                    try {
                        if (ticket != actionEpoch) return;
                        startOnWorker(logging);
                        if (interactive)
                            synchronized (transport) {
                                if (ticket == actionEpoch) transport.login();
                            }
                    } catch (Exception error) {
                        state.execute(
                                () -> {
                                    if (ticket != actionEpoch) return;
                                    backend = "Failed";
                                    listener.failed("无法启动 Tailscale 网络节点");
                                });
                    }
                });
    }

    /** Background catch-up shares the same status observer without occupying another I/O slot. */
    public void startOnWorker(boolean logging) throws Exception {
        transport.start(logging, value -> state.execute(() -> status(value)));
    }

    public void logout() {
        logoutRequested = true;
        long ticket = ++actionEpoch;
        backend = "NeedsLogin";
        authUrl = "";
        listener.changed();
        network.execute(
                () -> {
                    try {
                        synchronized (transport) {
                            if (ticket == actionEpoch) transport.logout();
                        }
                    } catch (Exception error) {
                        state.execute(
                                () -> {
                                    if (ticket == actionEpoch) listener.failed("退出网络失败");
                                });
                    }
                });
    }

    public void refresh() {
        network.execute(transport::reportStatus);
    }

    public void logging(boolean enabled) {
        network.execute(() -> transport.logging(enabled));
    }
}
