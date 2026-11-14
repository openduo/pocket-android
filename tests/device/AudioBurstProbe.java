// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.Context;
import android.os.SystemClock;

import top.kuaner.pockete2.audio.PacketStream;
import top.kuaner.pockete2.core.PocketController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Measures native decoding of the actual bundled reply without microphone or audible output. */
final class AudioBurstProbe {
    private final Probe probe;
    private final PocketController controller;
    private final Executor state;

    AudioBurstProbe(Probe probe, PocketController controller) throws Exception {
        this.probe = probe;
        this.controller = controller;
        Field field = PocketController.class.getDeclaredField("state");
        field.setAccessible(true);
        state = (Executor) field.get(controller);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    int run(Context context) throws Exception {
        if (!controller.snapshot().trying)
            throw new AssertionError("Audio timing requires the disposable room");
        PacketStream parser = new PacketStream();
        List<byte[]> packets;
        try (InputStream input = context.getAssets().open("try/try-en-1.opus-packets");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int n;
            while ((n = input.read(block)) != -1) output.write(block, 0, n);
            packets = parser.push(output.toByteArray());
            parser.finish();
        }
        FutureTask<Void> begin =
                new FutureTask<>(
                        () -> {
                            Method mute =
                                    PocketController.class.getDeclaredMethod(
                                            "setOutputMuted", boolean.class);
                            mute.setAccessible(true);
                            mute.invoke(controller, true);
                            Method speech =
                                    PocketController.class.getDeclaredMethod(
                                            "speech", String.class);
                            speech.setAccessible(true);
                            speech.invoke(controller, "c-qa-burst");
                            return null;
                        });
        state.execute(begin);
        begin.get(5, TimeUnit.SECONDS);
        Method binary = PocketController.class.getDeclaredMethod("binary", byte[].class);
        binary.setAccessible(true);
        CountDownLatch first = new CountDownLatch(1);
        long began = SystemClock.elapsedRealtime();
        state.execute(first::countDown);
        for (byte[] packet : packets)
            state.execute(
                    () -> {
                        try {
                            binary.invoke(controller, (Object) packet);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        AtomicBoolean decoded = new AtomicBoolean();
        FutureTask<Void> stop =
                new FutureTask<>(
                        () -> {
                            Field player = PocketController.class.getDeclaredField("player");
                            player.setAccessible(true);
                            top.kuaner.pockete2.audio.SpeechPlayer output =
                                    (top.kuaner.pockete2.audio.SpeechPlayer) player.get(controller);
                            decoded.set(output != null && output.hasPending());
                            invoke("stopPlayback");
                            return null;
                        });
        state.execute(stop);
        stop.get(8, TimeUnit.SECONDS);
        long elapsed = SystemClock.elapsedRealtime() - began;
        if (first.getCount() != 0
                || !decoded.get()
                || controller.snapshot().holdRecording
                || top.kuaner.pockete2.audio.input.RecordingInput.isActive())
            throw new AssertionError("Burst must complete without capture");
        android.os.Bundle status = new android.os.Bundle();
        status.putString(
                "checkpoint",
                "native-burst packets=" + packets.size() + "; state_stop_ms=" + elapsed);
        probe.sendStatus(0, status);
        return 1;
    }
}
