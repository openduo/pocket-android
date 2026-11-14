// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.*;

/** Explicit interleavings and virtual time for asynchronous owners; no sleeps or network. */
final class AsyncHarness implements AutoCloseable {
    long now = 100_000;
    private long order;
    final State state = new State();
    final Worker worker = new Worker();

    final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long due, sequence = ++order;

        Task(Runnable action, long delayMs) {
            super(action, null);
            due = now + delayMs;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(due - now, TimeUnit.MILLISECONDS);
        }

        public int compareTo(Delayed other) {
            return Long.compare(due, ((Task) other).due);
        }
    }

    final class State extends ScheduledThreadPoolExecutor {
        final PriorityQueue<Task> tasks =
                new PriorityQueue<>(
                        Comparator.comparingLong((Task t) -> t.due)
                                .thenComparingLong(t -> t.sequence));

        State() {
            super(1);
        }

        @Override
        public void execute(Runnable action) {
            schedule(action, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            Task task = new Task(action, unit.toMillis(delay));
            tasks.add(task);
            return task;
        }

        boolean runReady() throws Exception {
            Task task = tasks.peek();
            if (task == null || task.due > now) return false;
            tasks.remove();
            if (!task.isCancelled()) {
                task.run();
                task.get();
            }
            return true;
        }

        long delay() {
            return tasks.stream()
                    .filter(t -> !t.isDone())
                    .mapToLong(t -> t.due - now)
                    .min()
                    .orElse(-1);
        }
    }

    static final class Worker extends ThreadPoolExecutor {
        boolean occupied;

        Worker() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        }

        @Override
        public void execute(Runnable action) {
            if (occupied || !getQueue().offer(action)) throw new RejectedExecutionException();
        }

        boolean runNext() {
            Runnable action = getQueue().poll();
            if (action == null) return false;
            action.run();
            return true;
        }
    }

    void drain() throws Exception {
        while (state.runReady() || worker.runNext()) {}
    }

    void advance(long milliseconds) throws Exception {
        now += milliseconds;
        drain();
    }

    public void close() {
        worker.shutdownNow();
        state.shutdownNow();
    }
}
