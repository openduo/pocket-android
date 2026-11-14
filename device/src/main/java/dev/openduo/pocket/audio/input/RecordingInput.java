// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.audio.input;

import android.content.Context;

import java.io.IOException;

public interface RecordingInput extends AutoCloseable {
    static boolean isActive() {
        return ExclusiveRecordingInput.isActive();
    }

    /** Latest input level before array processing, owned by the existing capture lease. */
    static long captureMeter() {
        return ExclusiveRecordingInput.captureMeter();
    }

    static RecordingInput open(Context context, String mode, String scene) throws IOException {
        return ExclusiveRecordingInput.open(context, mode, scene);
    }

    default boolean echoCancellation() {
        return false;
    }

    int sampleRate();

    void start() throws IOException;

    void stop();

    int read(short[] samples) throws IOException;

    boolean needsSoftwareDenoising();

    default long inputMeter() {
        return 0;
    }

    @Override
    void close();
}
