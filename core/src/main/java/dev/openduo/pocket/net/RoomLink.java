// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.net;

import dev.openduo.pocket.core.Endpoint;

import org.json.JSONObject;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns a live generation, bounded connector/writer queues and the final-control handoff.
 * Application policy decides when to dial; only current-generation callbacks are admitted to its
 * state owner.
 */
public final class RoomLink implements AutoCloseable {
    public interface Listener extends ChannelConnection.LiveListener {
        void opened();

        void failed(Exception failure);
    }

    private interface Write {
        void run() throws Exception;
    }

    private final Executor state;
    private final ThreadPoolExecutor connector, writer;
    private final BiConsumer<Integer, String> failure;
    private final Consumer<String> diagnostic;
    private volatile int epoch, closingEpoch = -1, closingSource = -1;
    private boolean opening;

    public RoomLink(
            Executor state, BiConsumer<Integer, String> failure, Consumer<String> diagnostic) {
        this(
                state,
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        r -> new Thread(r, "DuoDuo Pocket live connector"),
                        new ThreadPoolExecutor.DiscardOldestPolicy()),
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(128),
                        r -> new Thread(r, "DuoDuo Pocket socket writer")),
                failure,
                diagnostic);
    }

    public RoomLink(
            Executor state,
            ThreadPoolExecutor connector,
            ThreadPoolExecutor writer,
            BiConsumer<Integer, String> failure,
            Consumer<String> diagnostic) {
        this.state = state;
        this.connector = connector;
        this.writer = writer;
        this.failure = failure;
        this.diagnostic = diagnostic;
    }

    public int epoch() {
        return epoch;
    }

    public boolean opening() {
        return opening;
    }

    public boolean closing() {
        return closingEpoch == epoch;
    }

    public boolean backpressured() {
        return writer.getQueue().size() > 8;
    }

    public void ready() {
        opening = false;
    }

    /**
     * Retires callbacks and queued writes before the application changes room or closes a socket.
     */
    public int invalidate() {
        epoch++;
        opening = false;
        closingEpoch = closingSource = -1;
        connector.getQueue().clear();
        writer.getQueue().clear();
        return epoch;
    }

    public void open(
            ChannelConnection target,
            Endpoint endpoint,
            String token,
            String path,
            JSONObject headers,
            Listener listener) {
        int ticket = invalidate();
        opening = true;
        connector.execute(
                () -> {
                    if (ticket != epoch) return;
                    try {
                        target.live(
                                endpoint,
                                token,
                                path,
                                headers,
                                new ChannelConnection.LiveListener() {
                                    public void text(String text) {
                                        admit(ticket, () -> listener.text(text));
                                    }

                                    public void binary(byte[] bytes) {
                                        byte[] copy = bytes.clone();
                                        admit(ticket, () -> listener.binary(copy));
                                    }

                                    public void closed(String reason) {
                                        admit(ticket, () -> listener.closed(reason));
                                    }
                                },
                                () ->
                                        ticket == epoch
                                                || closingEpoch == epoch
                                                        && ticket == closingSource);
                        admit(
                                ticket,
                                () -> {
                                    opening = false;
                                    listener.opened();
                                });
                    } catch (Exception error) {
                        admit(
                                ticket,
                                () -> {
                                    opening = false;
                                    listener.failed(error);
                                });
                    }
                });
    }

    public void verify(ChannelConnection target, Runnable healthy, Consumer<Exception> failed) {
        int ticket = epoch;
        connector.execute(
                () -> {
                    if (ticket != epoch) return;
                    try {
                        target.ping(3000);
                        admit(ticket, healthy);
                    } catch (Exception error) {
                        admit(ticket, () -> failed.accept(error));
                    }
                });
    }

    public void send(ChannelConnection target, JSONObject frame) {
        write(() -> target.send(frame));
    }

    public void sendBinary(ChannelConnection target, byte[] bytes) {
        write(() -> target.sendBinary(bytes));
    }

    private void write(Write work) {
        int ticket = epoch;
        try {
            writer.execute(
                    () -> {
                        if (ticket != epoch) return;
                        try {
                            work.run();
                        } catch (Exception error) {
                            admit(ticket, () -> failure.accept(ticket, "实时连接中断"));
                        }
                    });
        } catch (RejectedExecutionException occupied) {
            admit(ticket, () -> failure.accept(ticket, "实时连接拥塞"));
        }
    }

    /** Keeps only the retiring native socket alive for its last unmute/control writer turn. */
    public void reconnect(ChannelConnection target, JSONObject lastControl, Runnable finished) {
        int source = epoch;
        int ticket = invalidate();
        if (lastControl == null) {
            target.closeLive();
            finished.run();
            return;
        }
        closingEpoch = ticket;
        closingSource = source;
        opening = true;
        Runnable finish =
                () -> {
                    if (ticket != epoch) return;
                    closingEpoch = closingSource = -1;
                    opening = false;
                    target.closeLive();
                    finished.run();
                };
        try {
            writer.execute(
                    () -> {
                        if (ticket != epoch) return;
                        try {
                            target.send(lastControl);
                        } catch (Exception error) {
                            diagnostic.accept("release_failed");
                        } finally {
                            state.execute(finish);
                        }
                    });
        } catch (RejectedExecutionException occupied) {
            finish.run();
        }
    }

    private void admit(int ticket, Runnable event) {
        state.execute(
                () -> {
                    if (ticket == epoch) event.run();
                });
    }

    @Override
    public void close() {
        invalidate();
        connector.shutdownNow();
        writer.shutdownNow();
    }
}
