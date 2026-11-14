// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.audio;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Stream-owned decoder feeding the shared playback timeline with bounded, cancellable admission.
 */
public final class ReadAloudOutput implements SpeechReader.Output {
    private final OpusDecoder decoder;
    private final SpeechPlayer player;
    private final String speech;

    public ReadAloudOutput(SpeechPlayer player, String speech) throws IOException {
        decoder = new OpusDecoder();
        this.player = player;
        this.speech = speech;
    }

    @Override
    public boolean packet(byte[] opus, BooleanSupplier current) throws Exception {
        return player.enqueueWaiting(speech, decoder.packet(opus), current);
    }

    @Override
    public void close() {
        decoder.close();
    }
}
