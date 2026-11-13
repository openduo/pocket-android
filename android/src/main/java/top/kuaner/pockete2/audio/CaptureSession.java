// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import android.content.Context;

import top.kuaner.pockete2.audio.input.RecordingInput;

import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the only microphone lease; the capture thread also owns the encoder and cleanup. */
public final class CaptureSession {
    public interface Listener {
        void started();

        void packet(byte[] bytes);

        void stopped(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final boolean headset;
    private volatile String route = "设备麦克风";
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private volatile float level;
    private volatile boolean echo;
    private volatile boolean started;

    public boolean isStarted() {
        return started;
    }

    public CaptureSession(Context context, Listener listener) {
        this(context, false, listener);
    }

    public CaptureSession(Context context, boolean headset, Listener listener) {
        this.context = context.getApplicationContext();
        this.headset = headset;
        this.listener = listener;
        thread = new Thread(this::run, "Pocket E2 capture");
        thread.start();
    }

    public boolean echoCancellation() {
        return echo;
    }

    public float level() {
        return level;
    }

    public String route() {
        return route;
    }

    public void stop() {
        running.set(false);
    }

    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        thread.join(timeoutMs);
        return !thread.isAlive();
    }

    private void run() {
        String reason = "";
        try (top.kuaner.pockete2.audio.input.AudioRouteLease routing =
                        new top.kuaner.pockete2.audio.input.AudioRouteLease(context, headset);
                RecordingInput input = routing.input();
                OpusEncoder opus = new OpusEncoder()) {
            if (!running.get()) return;
            input.start();
            echo = input.echoCancellation();
            route = routing.name();
            started = true;
            listener.started();
            short[] block = new short[640], frame = new short[320];
            int filled = 0;
            while (running.get()) {
                int count = input.read(block);
                if (count < 0) throw new java.io.IOException("Capture ended unexpectedly");
                if (count == 0) continue;
                double sum = 0;
                for (int i = 0; i < count; i++) {
                    sum += (double) block[i] * block[i];
                    frame[filled++] = block[i];
                    if (filled == frame.length) {
                        byte[] bytes = opus.encode(frame);
                        filled = 0;
                        if (running.get()) listener.packet(bytes);
                    }
                }
                long meter = input.inputMeter();
                double rms = meter == 0 ? Math.sqrt(sum / count) : meter & 65535;
                double db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
                level = (float) Math.pow(Math.max(0, Math.min(1, (db + 65) / 45)), .7);
            }
        } catch (Exception | LinkageError failure) {
            reason = "麦克风打开或读取失败，请先结束其他录音";
        } finally {
            running.set(false);
            started = false;
            level = 0;
            listener.stopped(reason);
        }
    }
}
