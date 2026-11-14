// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.os.ParcelFileDescriptor;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A granted, separate-UID source: its first pipe stalls, later reads return the complete body. */
final class ProcessSource {
    static final String NAME = "DuoDuo Pocket process restart fixture.txt";
    static final AtomicInteger active = new AtomicInteger();

    static android.content.SharedPreferences state(android.content.Context context) {
        return context.getSharedPreferences("process-source", 0);
    }

    static byte[] body() {
        byte[] body = new byte[16_384];
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('A' + i % 26);
        return body;
    }

    static ParcelFileDescriptor open(android.content.Context context) throws FileNotFoundException {
        final int attempt;
        synchronized (ProcessSource.class) {
            attempt = state(context).getInt("opens", 0) + 1;
            if (!state(context).edit().putInt("opens", attempt).commit())
                throw new FileNotFoundException("Cannot save fixture open count");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            active.incrementAndGet();
            new Thread(
                            () -> {
                                try (OutputStream out =
                                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                                    byte[] bytes = body();
                                    out.write(bytes, 0, attempt == 1 ? 8192 : bytes.length);
                                    if (attempt == 1) {
                                        android.system.StructPollfd fd =
                                                new android.system.StructPollfd();
                                        fd.fd = pipe[1].getFileDescriptor();
                                        fd.events = 0;
                                        // Keep EOF withheld until the actual consumer process dies
                                        // or closes.
                                        for (int i = 0; i < 600; i++)
                                            if (android.system.Os.poll(
                                                            new android.system.StructPollfd[] {fd},
                                                            100)
                                                    > 0) break;
                                    }
                                } catch (Exception closed) {
                                } finally {
                                    active.decrementAndGet();
                                }
                            },
                            "DuoDuo Pocket process-death source")
                    .start();
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("Cannot create process-death fixture pipe");
        }
    }
}
