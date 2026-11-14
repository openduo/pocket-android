// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.os.CancellationSignal;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One delivery's provider cancellation and observed byte counts; never owns an Activity. */
public final class ImportRead {
    public interface Observer {
        void copied(String name, long bytes);
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationSignal signal = new CancellationSignal();
    private final Observer observer;

    public ImportRead() {
        this((name, bytes) -> {});
    }

    public ImportRead(Observer observer) {
        this.observer = observer;
    }

    public CancellationSignal signal() {
        return signal;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** Persist the cancellation receipt first. Binder cancellation runs off the state/UI thread. */
    public void cancel(Executor abort) {
        if (!cancelled.compareAndSet(false, true)) return;
        try {
            abort.execute(signal::cancel);
        } catch (RejectedExecutionException full) {
            /* Polling reads still observe the local flag. */
        }
    }

    public void check() throws InterruptedIOException {
        if (cancelled()) throw new InterruptedIOException("Import cancelled");
    }

    public void copied(String name, long bytes) throws InterruptedIOException {
        check();
        observer.copied(name, bytes);
    }

    /** A stalled provider pipe must not pin the single import queue after cancellation. */
    public void readable(FileDescriptor descriptor) throws IOException {
        StructPollfd fd = new StructPollfd();
        fd.fd = descriptor;
        fd.events = (short) (OsConstants.POLLIN | OsConstants.POLLERR | OsConstants.POLLHUP);
        while (true) {
            check();
            try {
                if (Os.poll(new StructPollfd[] {fd}, 100) > 0) {
                    check();
                    return;
                }
            } catch (ErrnoException failure) {
                if (failure.errno != OsConstants.EINTR) throw new IOException(failure);
            }
        }
    }
}
