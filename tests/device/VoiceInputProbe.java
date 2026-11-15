// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.PocketController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Opt-in local input diagnosis. Never uploads the captured samples or sends room content. */
final class VoiceInputProbe {
    private final Probe probe;

    VoiceInputProbe(Probe probe) {
        this.probe = probe;
    }

    String run(Context context, boolean calibration, boolean expectUnavailable) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(context, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        PocketController controller = PocketController.get(context);
        PocketController.Snapshot initial = controller.snapshot();
        if (initial == null
                || initial.holdRecording
                || initial.ambientActive
                || initial.cameraOn
                || initial.uploadingVoice
                || !initial.readingId.isEmpty()
                || RecordingInput.isActive())
            throw new AssertionError("Input diagnosis requires idle capture/playback");
        File directory = new File(context.getExternalCacheDir(), "voice-input");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new java.io.IOException("Could not create private audio evidence");
        if (expectUnavailable) {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger packets =
                    new java.util.concurrent.atomic.AtomicInteger();
            String[] failure = {""};
            dev.openduo.pocket.audio.CaptureSession capture =
                    new dev.openduo.pocket.audio.CaptureSession(
                            context,
                            new dev.openduo.pocket.audio.CaptureSession.Listener() {
                                public void started() {}

                                public void packet(byte[] bytes) {
                                    packets.incrementAndGet();
                                }

                                public void stopped(String reason) {
                                    failure[0] = reason;
                                    done.countDown();
                                }
                            });
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                capture.stop();
                capture.awaitStopped(3000);
                throw new AssertionError("Unavailable input must stop promptly");
            }
            if (!failure[0].equals("当前版本无法使用此设备的内置麦克风，请连接耳机麦克风")
                    || packets.get() != 0
                    || RecordingInput.isActive())
                throw new AssertionError(
                        "Unavailable adapter must explain and release without packets");
            return "PASS unavailable built-in input reports localized adapter reason; zero packets;"
                    + " released";
        }
        JSONArray sources = new JSONArray();
        for (String mode : new String[] {"voice", "natural"}) {
            int count = 0, nonzero = 0, peak = 0;
            double sum = 0, squares = 0;
            android.media.AudioTrack tone = null;
            if (calibration) {
                short[] signal = new short[16000];
                for (int i = 0; i < signal.length; i++)
                    signal[i] = (short) (5000 * Math.sin(2 * Math.PI * 523.25 * i / 16000));
                tone =
                        new android.media.AudioTrack(
                                android.media.AudioManager.STREAM_MUSIC,
                                16000,
                                android.media.AudioFormat.CHANNEL_OUT_MONO,
                                android.media.AudioFormat.ENCODING_PCM_16BIT,
                                signal.length * 2,
                                android.media.AudioTrack.MODE_STATIC);
                tone.write(signal, 0, signal.length);
                tone.setVolume(.25f);
            }
            try (RecordingInput input = RecordingInput.open(context, mode, "meeting");
                    FileOutputStream output =
                            new FileOutputStream(new File(directory, mode + ".pcm"))) {
                input.start();
                int rate = input.sampleRate();
                boolean playing = false;
                long until = android.os.SystemClock.elapsedRealtime() + 5000;
                short[] block = new short[640];
                while (count < rate * 3 && android.os.SystemClock.elapsedRealtime() < until) {
                    if (tone != null && !playing && count >= rate / 2) {
                        playing = true;
                        tone.play();
                    }
                    int read = input.read(block);
                    if (read <= 0) continue;
                    for (int i = 0; i < read; i++) {
                        int sample = block[i];
                        if (sample != 0) nonzero++;
                        peak = Math.max(peak, Math.abs(sample));
                        sum += sample;
                        squares += (double) sample * sample;
                        output.write(sample & 255);
                        output.write(sample >>> 8 & 255);
                    }
                    count += read;
                }
                if (count < rate * 3)
                    throw new AssertionError("Input did not deliver three seconds within deadline");
                sources.put(
                        new JSONObject()
                                .put("mode", mode)
                                .put("rate", rate)
                                .put("samples", count)
                                .put("nonzero", nonzero)
                                .put("peak", peak)
                                .put("rms", Math.sqrt(squares / count))
                                .put("mean", sum / count)
                                .put("aec", input.echoCancellation()));
            } finally {
                if (tone != null) tone.release();
            }
        }
        File[] retained = new File(context.getFilesDir(), "voice").listFiles();
        if (RecordingInput.isActive())
            throw new AssertionError("Input diagnosis must release its microphone lease");
        int retainedFiles = 0;
        if (retained != null)
            for (File file : retained) if (file.getName().endsWith(".packets")) retainedFiles++;
        JSONObject report =
                new JSONObject()
                        .put("sources", sources)
                        .put("local_calibration", calibration)
                        .put("retained_packet_files", retainedFiles)
                        .put("microphone_released", !RecordingInput.isActive())
                        .put("network_sends", 0);
        Files.write(
                new File(directory, "result.json").toPath(),
                report.toString(2).getBytes(StandardCharsets.UTF_8));
        return "PASS local input measurement; no room sends; microphone released="
                + !RecordingInput.isActive();
    }
}
