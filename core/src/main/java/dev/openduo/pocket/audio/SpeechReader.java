// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.audio;

import dev.openduo.pocket.core.Message;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelTransport;
import dev.openduo.pocket.net.RoomAccess;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * One cancellable read-aloud stream and its playback identity. State methods/completion run on the
 * caller's state executor. A stream worker alone opens, feeds and closes its platform output. Both
 * executors are caller-owned; output backpressure is supplied by the platform adapter.
 */
public final class SpeechReader {
    public interface Output extends AutoCloseable {
        boolean packet(byte[] opus, BooleanSupplier current) throws Exception;

        @Override
        void close();
    }

    public interface OutputFactory {
        Output open() throws Exception;
    }

    public interface Completion {
        void finished(boolean received, Exception failure);
    }

    private static final class Reading {
        final long request;
        final Message message;
        final RoomAccess access;
        boolean inFlight = true;

        Reading(long request, Message message, RoomAccess access) {
            this.request = request;
            this.message = message;
            this.access = access;
        }
    }

    private final Executor state, worker;
    private long nextRequest;
    private volatile Reading reading;

    public SpeechReader(Executor state, Executor worker) {
        this.state = state;
        this.worker = worker;
    }

    public String id() {
        Reading current = reading;
        return current == null ? "" : current.message.id;
    }

    public boolean inFlight() {
        return reading != null && reading.inFlight;
    }

    /** Retains id() after HTTP completion until the caller observes playback drain and cancels. */
    public void start(
            Message message,
            RoomAccess access,
            String room,
            OutputFactory output,
            Completion completion) {
        cancel();
        Reading current = new Reading(++nextRequest, message, access);
        reading = current;
        worker.execute(() -> receive(current, room, output, completion));
    }

    public void cancel() {
        Reading retired = reading;
        reading = null;
        if (retired != null && retired.inFlight) retired.access.cancelStream(retired.request);
    }

    private boolean isCurrent(Reading current) {
        return reading == current && current.access.current();
    }

    private void receive(
            Reading current, String room, OutputFactory output, Completion completion) {
        if (!isCurrent(current)) return;
        final boolean[] received = {false};
        final Exception[] parseError = {null};
        Exception problem = null;
        try (PacketStream packets = new PacketStream();
                Output sink = output.open()) {
            ChannelTransport.Response response =
                    current.access.speech(
                            current.request,
                            current.message.text,
                            bytes -> {
                                if (!isCurrent(current) || parseError[0] != null) return;
                                try {
                                    for (byte[] packet : packets.push(bytes)) {
                                        received[0] = true;
                                        if (!sink.packet(packet, () -> isCurrent(current))) break;
                                    }
                                } catch (Exception failure) {
                                    parseError[0] = failure;
                                    current.access.cancelStream(current.request);
                                }
                            },
                            () -> isCurrent(current));
            if (parseError[0] != null) throw parseError[0];
            packets.finish();
            if (response.status != 200) throw ChannelClient.failure(response);
        } catch (Exception failure) {
            problem = failure;
        }
        final Exception failure = problem;
        state.execute(
                () -> {
                    if (!isCurrent(current)) return;
                    current.inFlight = false;
                    completion.finished(received[0], failure);
                });
    }
}
