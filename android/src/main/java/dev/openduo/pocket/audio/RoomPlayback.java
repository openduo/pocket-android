// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.audio;

import dev.openduo.pocket.core.AmbientOutput;
import dev.openduo.pocket.core.Message;
import dev.openduo.pocket.net.RoomAccess;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Owns the shared output timeline, live decoder and read stream; application policy owns focus. */
public final class RoomPlayback {
    public interface Listener {
        void changed();

        void report(JSONObject frame);

        void failed(String reason);
    }

    private final Executor state;
    private final BooleanSupplier reports;
    private final Listener listener;
    private final AmbientOutput output = new AmbientOutput();
    private final SpeechReader reader;
    private SpeechPlayer player;
    private OpusDecoder decoder;
    private String route = "系统音频输出";
    private boolean active, muted;

    public RoomPlayback(
            Executor state, Executor network, BooleanSupplier reports, Listener listener) {
        this.state = state;
        this.reports = reports;
        this.listener = listener;
        reader = new SpeechReader(state, network);
    }

    public String readingId() {
        return reader.id();
    }

    public String route() {
        return route;
    }

    public boolean active() {
        return active;
    }

    public boolean pending() {
        return reader.inFlight() || player != null && player.hasPending();
    }

    public boolean acceptsStop(JSONObject frame) {
        return output.acceptsStop(frame);
    }

    public void mute(boolean value) {
        muted = value;
        if (player != null) player.silent(value);
    }

    /** Caller has acquired the appropriate audio focus before preparing output. */
    public void prepare() {
        if (player != null) return;
        player =
                new SpeechPlayer(
                        new SpeechPlayer.Listener() {
                            public void route(String name) {
                                state.execute(
                                        () -> {
                                            route = name;
                                            listener.changed();
                                        });
                            }

                            public void played(
                                    int generation, String id, long ms, boolean drained) {
                                state.execute(
                                        () -> {
                                            if (generation != player.generation()) return;
                                            report(output.played(id, ms, reports.getAsBoolean()));
                                        });
                            }

                            public void active(int generation, boolean value) {
                                state.execute(
                                        () -> {
                                            if (generation != player.generation()
                                                    || active == value) return;
                                            active = value;
                                            if (!value) drained();
                                            listener.changed();
                                        });
                            }

                            public void failed(int generation, String reason) {
                                state.execute(
                                        () -> {
                                            if (generation != player.generation()) return;
                                            stop();
                                            listener.failed(reason);
                                        });
                            }
                        });
        player.silent(muted);
    }

    public void speech(String id) throws Exception {
        if (!reader.id().isEmpty()) stop();
        if (decoder != null) decoder.close();
        decoder = null;
        output.speech(id);
        decoder = new OpusDecoder();
    }

    public void packet(byte[] packet, boolean skip) throws Exception {
        if (decoder == null || !reader.id().isEmpty()) return;
        short[] pcm = decoder.packet(packet);
        if (skip) report(output.skipped(pcm.length, reports.getAsBoolean()));
        else if (player != null) player.enqueue(output.playbackKey(), pcm);
    }

    public void read(
            Message message, RoomAccess access, String room, SpeechReader.Completion completion) {
        output.read(message.id);
        SpeechPlayer target = player;
        String speech = output.playbackKey();
        reader.start(
                message,
                access,
                room,
                () -> new ReadAloudOutput(target, speech),
                (received, failure) -> {
                    drained();
                    completion.finished(received, failure);
                });
    }

    private void drained() {
        if (!reader.inFlight() && output.reading() && (player == null || !player.hasPending()))
            reader.cancel();
    }

    public void interrupt() {
        JSONObject frame = output.interrupted(reports.getAsBoolean());
        stop();
        report(frame);
    }

    public void stop() {
        reader.cancel();
        if (player != null) player.stop();
        if (decoder != null) decoder.close();
        decoder = null;
        output.stop();
        active = false;
    }

    private void report(JSONObject frame) {
        if (frame != null) listener.report(frame);
    }
}
