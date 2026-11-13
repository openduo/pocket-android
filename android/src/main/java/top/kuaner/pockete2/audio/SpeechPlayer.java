// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** One hardware timeline with per-speech watermarks. A new declaration never flushes old PCM. */
public final class SpeechPlayer implements AutoCloseable {
    public interface Listener {
        void played(String speechId, long ms, boolean drained);

        void active(boolean active);

        void failed(String reason);

        default void route(String name) {}
    }

    private static final int RATE = 16000, QUEUE_BLOCKS = 3000;
    private final ArrayBlockingQueue<Block> queue = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
    private final AtomicInteger generation = new AtomicInteger();
    private final Listener listener;
    private final Thread worker;
    private volatile AudioTrack track;
    private volatile boolean closed, silent, activeHardware;

    private static final class Block {
        final String id;
        final short[] pcm;
        final int generation;

        Block(String id, short[] pcm, int gen) {
            this.id = id;
            this.pcm = pcm;
            generation = gen;
        }
    }

    private static final class Span {
        final String id;
        final long start, end;

        Span(String id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public SpeechPlayer(Listener listener) {
        this.listener = listener;
        worker = new Thread(this::run, "Pocket E2 playback");
        worker.start();
    }

    public boolean enqueue(String id, short[] pcm) {
        boolean added = queue.offer(new Block(id, pcm, generation.get()));
        if (!added) {
            stop();
            listener.failed("语音缓冲已满，播放已停止");
        }
        return added;
    }

    public boolean enqueueWaiting(String id, short[] pcm, BooleanSupplier current)
            throws InterruptedException {
        Block block = new Block(id, pcm, generation.get());
        while (!closed && current.getAsBoolean() && block.generation == generation.get())
            if (queue.offer(block, 50, TimeUnit.MILLISECONDS)) return true;
        return false;
    }

    public boolean hasPending() {
        return activeHardware || !queue.isEmpty();
    }

    public void silent(boolean value) {
        silent = value;
        AudioTrack current = track;
        if (current != null)
            try {
                current.setVolume(value ? 0 : 1);
            } catch (IllegalStateException ignored) {
            }
    }

    public void stop() {
        generation.incrementAndGet();
        queue.clear();
        AudioTrack current = track;
        if (current != null)
            try {
                current.pause();
                current.flush();
            } catch (IllegalStateException ignored) {
            }
        worker.interrupt();
    }

    private AudioTrack create() {
        AudioTrack output =
                new AudioTrack.Builder()
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build())
                        .setAudioFormat(
                                new AudioFormat.Builder()
                                        .setSampleRate(RATE)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .build())
                        .setBufferSizeInBytes(6400)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
        if (output.getState() != AudioTrack.STATE_INITIALIZED) {
            output.release();
            throw new IllegalStateException("AudioTrack unavailable");
        }
        output.setVolume(silent ? 0 : 1);
        output.addOnRoutingChangedListener(
                (android.media.AudioRouting.OnRoutingChangedListener)
                        routing -> {
                            android.media.AudioDeviceInfo device = routing.getRoutedDevice();
                            if (device != null)
                                listener.route(String.valueOf(device.getProductName()));
                        },
                new android.os.Handler(android.os.Looper.getMainLooper()));
        output.play();
        return output;
    }

    private void run() {
        AudioTrack output = null;
        int activeGeneration = -1;
        long written = 0, lastSample = 0, headWrap = 0, priorHead = 0;
        ArrayDeque<Span> spans = new ArrayDeque<>();
        Map<String, Long> completed = new LinkedHashMap<>(), reported = new LinkedHashMap<>();
        boolean active = false;
        try {
            while (!closed) {
                try {
                    if (activeGeneration != generation.get()) {
                        if (output != null) output.release();
                        output = null;
                        track = null;
                        spans.clear();
                        completed.clear();
                        reported.clear();
                        written = 0;
                        headWrap = priorHead = 0;
                        activeGeneration = generation.get();
                        if (active) {
                            active = false;
                            activeHardware = false;
                            listener.active(false);
                        }
                    }
                    Block block = queue.poll(output == null ? 250 : 8, TimeUnit.MILLISECONDS);
                    if (block != null && block.generation == generation.get()) {
                        if (output == null) {
                            output = create();
                            track = output;
                        }
                        if (!active) {
                            active = true;
                            activeHardware = true;
                            listener.active(true);
                        }
                        int offset = 0;
                        while (offset < block.pcm.length
                                && block.generation == generation.get()
                                && !closed) {
                            int n =
                                    output.write(
                                            block.pcm,
                                            offset,
                                            block.pcm.length - offset,
                                            AudioTrack.WRITE_BLOCKING);
                            if (n <= 0) {
                                if (block.generation != generation.get()) break;
                                throw new IllegalStateException("Audio write failed");
                            }
                            offset += n;
                        }
                        if (offset > 0 && block.generation == generation.get()) {
                            spans.addLast(new Span(block.id, written, written + offset));
                            written += offset;
                        }
                    }
                    if (output == null || activeGeneration != generation.get()) continue;
                    long rawHead = output.getPlaybackHeadPosition() & 0xffffffffL;
                    if (rawHead < priorHead) headWrap += 1L << 32;
                    priorHead = rawHead;
                    long head = headWrap + rawHead;
                    while (!spans.isEmpty() && head >= spans.peekFirst().end) {
                        Span span = spans.removeFirst();
                        completed.put(
                                span.id,
                                completed.getOrDefault(span.id, 0L) + span.end - span.start);
                    }
                    boolean drained = spans.isEmpty() && queue.isEmpty();
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (drained || now - lastSample >= 250) {
                        lastSample = now;
                        Map<String, Long> totals = new LinkedHashMap<>(completed);
                        if (!spans.isEmpty()) {
                            Span first = spans.peekFirst();
                            totals.put(
                                    first.id,
                                    totals.getOrDefault(first.id, 0L)
                                            + Math.max(0, head - first.start));
                        }
                        for (Map.Entry<String, Long> entry : totals.entrySet()) {
                            long ms = entry.getValue() * 1000 / RATE;
                            if (ms > reported.getOrDefault(entry.getKey(), 0L)) {
                                reported.put(entry.getKey(), ms);
                                listener.played(entry.getKey(), ms, drained);
                            }
                        }
                        if (drained && active) {
                            active = false;
                            activeHardware = false;
                            listener.active(false);
                        }
                        if (drained) {
                            while (completed.size() > 128) {
                                String oldest = completed.keySet().iterator().next();
                                completed.remove(oldest);
                                reported.remove(oldest);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception failure) {
                    if (activeGeneration == generation.get()) {
                        listener.failed("播放中断，请检查音频设备");
                        generation.incrementAndGet();
                        queue.clear();
                    }
                }
            }
        } finally {
            track = null;
            if (output != null) output.release();
            listener.active(false);
        }
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
