// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.audio.input;

import android.content.Context;

import dev.openduo.pocket.device.DeviceRuntime;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** All adapters share one capture lease; ownership is released even when close fails. */
final class ExclusiveRecordingInput implements RecordingInput {
    private static final AtomicBoolean leased = new AtomicBoolean();
    private static volatile ExclusiveRecordingInput owner;
    private final RecordingInput input;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ExclusiveRecordingInput(RecordingInput input) {
        this.input = input;
    }

    static boolean isActive() {
        return leased.get();
    }

    static long captureMeter() {
        ExclusiveRecordingInput current = owner;
        return current == null || current.closed.get() ? 0 : current.inputMeter();
    }

    static RecordingInput open(Context context, String mode, String scene) throws IOException {
        return open(context, mode, scene, null);
    }

    static RecordingInput open(
            Context context, String mode, String scene, android.media.AudioDeviceInfo preferred)
            throws IOException {
        if (!leased.compareAndSet(false, true)) throw new IOException("麦克风正在释放或被占用，请稍后再试");
        try {
            ExclusiveRecordingInput opened =
                    new ExclusiveRecordingInput(
                            DeviceRuntime.current().openInput(context, mode, scene, preferred));
            owner = opened;
            return opened;
        } catch (IOException | RuntimeException | Error failure) {
            leased.set(false);
            throw failure;
        }
    }

    @Override
    public boolean echoCancellation() {
        return input.echoCancellation();
    }

    @Override
    public int sampleRate() {
        return input.sampleRate();
    }

    @Override
    public void start() throws IOException {
        input.start();
    }

    @Override
    public void stop() {
        input.stop();
    }

    @Override
    public int read(short[] samples) throws IOException {
        return closed.get() ? -1 : input.read(samples);
    }

    @Override
    public boolean needsSoftwareDenoising() {
        return input.needsSoftwareDenoising();
    }

    @Override
    public long inputMeter() {
        return closed.get() ? 0 : input.inputMeter();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true))
            try {
                input.close();
            } finally {
                owner = null;
                leased.set(false);
            }
    }
}
