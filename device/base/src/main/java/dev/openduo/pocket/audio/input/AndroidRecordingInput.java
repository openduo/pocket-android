// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.audio.input;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;

import java.io.IOException;

/** Framework capture shared by phones, tablets and display devices. */
public final class AndroidRecordingInput implements RecordingInput {
    private final AudioRecord input;
    private final AcousticEchoCanceler echo;

    public AndroidRecordingInput(
            Context context, boolean natural, android.media.AudioDeviceInfo preferred)
            throws IOException {
        int minimum =
                AudioRecord.getMinBufferSize(
                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("设备不支持 16kHz 录音");
        input = open(natural, Math.max(6400, minimum * 2));
        if (preferred != null && !input.setPreferredDevice(preferred)) {
            input.release();
            throw new IOException("耳机麦克风不可用，请重新连接耳机或切换为设备麦克风");
        }
        AcousticEchoCanceler effect = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                effect = AcousticEchoCanceler.create(input.getAudioSessionId());
                if (effect != null) effect.setEnabled(true);
            }
        } catch (RuntimeException unavailable) {
            if (effect != null) effect.release();
            effect = null;
        }
        echo = effect;
    }

    private static AudioRecord open(boolean natural, int bufferBytes) throws IOException {
        int[] sources =
                natural
                        ? new int[] {MediaRecorder.AudioSource.MIC}
                        : new int[] {
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            MediaRecorder.AudioSource.MIC
                        };
        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate =
                        new AudioRecord(
                                source,
                                16000,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) return candidate;
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                // Display devices may expose MIC without the communication audio source.
            }
            if (candidate != null) candidate.release();
        }
        throw new IOException("无法打开麦克风");
    }

    @Override
    public boolean echoCancellation() {
        return echo != null && echo.getEnabled();
    }

    @Override
    public int sampleRate() {
        return 16000;
    }

    @Override
    public void start() throws IOException {
        input.startRecording();
        if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new IOException("麦克风未开始录音");
    }

    @Override
    public int read(short[] samples) throws IOException {
        int count = input.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
        if (count < 0) throw new IOException("音频输入已中断");
        return count;
    }

    @Override
    public boolean needsSoftwareDenoising() {
        return false;
    }

    @Override
    public void stop() {
        if (input.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) input.stop();
    }

    @Override
    public void close() {
        try {
            stop();
        } finally {
            if (echo != null) echo.release();
            input.release();
        }
    }
}
