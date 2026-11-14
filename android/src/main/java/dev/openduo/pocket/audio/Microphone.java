// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.audio;

import android.content.Context;

import java.util.concurrent.Executor;

/** State-owned capture lifecycle; the CaptureSession thread retains native resource ownership. */
public final class Microphone {
    public interface Listener {
        void ready();

        void packet(byte[] bytes);

        void stopped(String reason, boolean intentional);
    }

    private final Context context;
    private final Executor state;
    private final Listener listener;
    private volatile CaptureSession capture;
    private boolean stopping;
    private int epoch;

    public Microphone(Context context, Executor state, Listener listener) {
        this.context = context.getApplicationContext();
        this.state = state;
        this.listener = listener;
    }

    public boolean active() {
        return capture != null;
    }

    public boolean stopping() {
        return stopping;
    }

    public boolean started() {
        return capture != null && capture.isStarted() && !stopping;
    }

    public boolean echoCancellation() {
        return capture != null && capture.echoCancellation();
    }

    public String route() {
        return capture == null ? "" : capture.route();
    }

    public float level() {
        CaptureSession current = capture;
        return current == null ? 0 : current.level();
    }

    public void start(boolean headset) {
        if (active() || stopping) return;
        int ticket = ++epoch;
        capture =
                new CaptureSession(
                        context,
                        headset,
                        new CaptureSession.Listener() {
                            public void started() {
                                state.execute(
                                        () -> {
                                            if (ticket == epoch && !stopping) listener.ready();
                                        });
                            }

                            public void packet(byte[] bytes) {
                                state.execute(
                                        () -> {
                                            if (ticket == epoch && !stopping)
                                                listener.packet(bytes);
                                        });
                            }

                            public void stopped(String reason) {
                                state.execute(
                                        () -> {
                                            if (ticket != epoch) return;
                                            capture = null;
                                            boolean intentional = stopping;
                                            stopping = false;
                                            listener.stopped(reason, intentional);
                                        });
                            }
                        });
    }

    public void stop() {
        if (capture == null) return;
        stopping = true;
        capture.stop();
    }
}
