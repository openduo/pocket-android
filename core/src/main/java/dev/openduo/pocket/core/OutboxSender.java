// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.RequestScope;
import dev.openduo.pocket.net.RoomAccess;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Owns one outbox HTTP lifetime, its immutable upload body and a bounded sender worker. Public
 * methods and completion belong to the application's state executor; workers never mutate room or
 * repository state. The caller owns the state executor, and this component owns its worker.
 */
public final class OutboxSender implements AutoCloseable {
    public interface Completion {
        void finished(Outbox.Item item, ChannelClient.Failure failure);
    }

    private static final class Operation {
        final Outbox.Item item;
        final ChannelClient client;
        final ChannelClient.State room;
        final RequestScope requests;
        final Completion completion;
        final VoiceRetry retry;
        final LongSupplier clock;
        final long started, allowanceMs;
        byte[] body;
        ChannelClient.Failure error;
        ScheduledFuture<?> wake;
        boolean dispatched;

        Operation(
                Outbox.Item item,
                RoomAccess access,
                ChannelClient.State room,
                long allowanceMs,
                Completion completion,
                LongSupplier clock)
                throws Exception {
            requests = new RequestScope(access);
            this.item = item;
            client = access.client(requests);
            this.room = room;
            this.allowanceMs = Math.max(0, allowanceMs);
            this.completion = completion;
            this.clock = clock;
            started = clock.getAsLong();
            retry = new VoiceRetry(25_000, clock);
        }

        long remainingMs() {
            long elapsed = Math.max(0, clock.getAsLong() - started);
            return Math.min(retry.remainingMs(), Math.max(0, allowanceMs - elapsed));
        }
    }

    private final ScheduledExecutorService state;
    private final ThreadPoolExecutor worker;
    private final LongSupplier clock;
    private volatile Operation operation;
    private boolean closed;

    public OutboxSender(ScheduledExecutorService state) {
        this(
                state,
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        r -> new Thread(r, "DuoDuo Pocket send")),
                () -> System.nanoTime() / 1_000_000);
    }

    public OutboxSender(
            ScheduledExecutorService state, ThreadPoolExecutor worker, LongSupplier clock) {
        this.state = state;
        this.worker = worker;
        this.clock = clock;
    }

    public boolean active() {
        return operation != null;
    }

    public Outbox.Item item() {
        Operation current = operation;
        return current == null ? null : current.item;
    }

    /**
     * Registers cancellation before durable admission. No I/O is queued until dispatch(), so the
     * caller can persist SENDING or cancel on storage failure without sending any bytes.
     * remainingVoiceBudgetMs is a duration, independent of the platform's monotonic clock origin.
     */
    public void prepare(
            Outbox.Item item,
            RoomAccess access,
            ChannelClient.State room,
            long remainingVoiceBudgetMs,
            Completion completion)
            throws Exception {
        if (closed || active()) throw new IllegalStateException("Sender unavailable");
        operation = new Operation(item, access, room, remainingVoiceBudgetMs, completion, clock);
    }

    public void dispatch() {
        Operation current = operation;
        if (current == null || current.dispatched) return;
        current.dispatched = true;
        work(
                current,
                () -> {
                    byte[] body = null;
                    ChannelClient.Failure error = null;
                    try {
                        if (current.item.voice)
                            body =
                                    ChannelClient.voiceBody(
                                            new File(current.item.voiceFile), current.room);
                    } catch (ChannelClient.Failure failure) {
                        error = failure;
                    }
                    final byte[] prepared = body;
                    final ChannelClient.Failure failure = error;
                    state.execute(
                            () -> {
                                if (operation != current) return;
                                current.body = prepared;
                                if (failure == null) attempt(current);
                                else finish(current, failure);
                            });
                });
    }

    /** Cancels queued, retrying or in-flight work without completing an obsolete delivery. */
    public void cancel() {
        Operation retired = operation;
        operation = null;
        worker.getQueue().clear();
        if (retired != null) {
            if (retired.wake != null) retired.wake.cancel(false);
            retired.requests.close();
        }
    }

    private void work(Operation current, Runnable task) {
        if (operation != current) return;
        try {
            worker.execute(
                    () -> {
                        if (operation == current) task.run();
                    });
        } catch (RejectedExecutionException occupied) {
            // A cancelled HTTP request may still be returning. Keep only this operation's next job.
            later(current, () -> work(current, task), 25);
        }
    }

    private void later(Operation current, Runnable task, long delayMs) {
        current.wake =
                state.schedule(
                        () -> {
                            if (operation != current) return;
                            current.wake = null;
                            task.run();
                        },
                        delayMs,
                        TimeUnit.MILLISECONDS);
    }

    private void attempt(Operation current) {
        if (operation != current) return;
        if (current.item.voice && current.remainingMs() == 0) {
            finish(current, expired(current.error));
            return;
        }
        byte[] body = current.body;
        ChannelClient.Failure previous = current.error;
        work(
                current,
                () -> {
                    ChannelClient.Failure problem = null;
                    Outbox.Item item = current.item;
                    try {
                        if (item.voice) {
                            long remaining = current.remainingMs();
                            if (remaining == 0) throw expired(previous);
                            current.client.sendVoice(
                                    body,
                                    item.source,
                                    item.id,
                                    item.display,
                                    item.spoken,
                                    item.place,
                                    remaining);
                        } else {
                            current.client.sendText(
                                    item.text,
                                    item.attachments,
                                    item.id,
                                    item.display,
                                    item.spoken,
                                    item.place);
                        }
                    } catch (ChannelClient.Failure failure) {
                        problem = failure;
                    }
                    final ChannelClient.Failure error = problem;
                    state.execute(
                            () -> {
                                if (operation != current) return;
                                current.error = error;
                                if (error != null
                                        && current.item.voice
                                        && (error.transport || error.status >= 500)) {
                                    long delay =
                                            Math.min(
                                                    current.retry.nextDelayMs(),
                                                    current.remainingMs());
                                    if (delay > 0) {
                                        later(current, () -> attempt(current), delay);
                                        return;
                                    }
                                }
                                finish(current, error);
                            });
                });
    }

    private static ChannelClient.Failure expired(ChannelClient.Failure previous) {
        return previous == null ? new ChannelClient.Failure("暂时无法连接房间", 0, true) : previous;
    }

    private void finish(Operation current, ChannelClient.Failure error) {
        if (operation != current) return;
        cancel();
        current.completion.finished(current.item, error);
    }

    @Override
    public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
