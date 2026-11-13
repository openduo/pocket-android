// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.diagnostics;

import android.content.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.zip.*;

/** Bounded diagnostics. Callers provide event labels/counts, never tokens or message bodies. */
public final class AppLog {
    private static AppLog instance;
    private final File directory;
    private final ThreadPoolExecutor writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256),
                    r -> new Thread(r, "Pocket E2 log"));

    private AppLog(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "logs");
        directory.mkdirs();
    }

    public static synchronized AppLog get(Context context) {
        if (instance == null) instance = new AppLog(context.getApplicationContext());
        return instance;
    }

    public void event(String name, String detail) {
        try {
            writer.execute(
                    () -> {
                        try {
                            File file = new File(directory, "app.log");
                            if (file.length() > 256 * 1024) {
                                File previous = new File(directory, "app.previous.log");
                                previous.delete();
                                file.renameTo(previous);
                            }
                            try (FileOutputStream out = new FileOutputStream(file, true)) {
                                String safe =
                                        detail.replaceAll(
                                                "(?i)(bearer|token|code|auth_url)[:= ]+[^ ,;]+",
                                                "$1=[redacted]");
                                out.write(
                                        (Instant.now()
                                                        + " "
                                                        + name
                                                        + " "
                                                        + safe.replace('\n', ' ')
                                                        + "\n")
                                                .getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public File export(Context context, String summary, File network) throws Exception {
        writer.submit(() -> {}).get(3, TimeUnit.SECONDS);
        File shared = new File(context.getCacheDir(), "shared");
        if (!shared.isDirectory() && !shared.mkdirs()) throw new IOException();
        File result = File.createTempFile("pocket-e2-diagnostics-", ".zip", shared);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(result))) {
            zip.putNextEntry(new ZipEntry("status.txt"));
            zip.write(summary.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (File file :
                    new File[] {
                        new File(directory, "app.log"),
                        new File(directory, "app.previous.log"),
                        network
                    })
                if (file != null && file.isFile()) {
                    zip.putNextEntry(new ZipEntry(file.getName()));
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        long bytes = 0;
                        while ((n = in.read(buffer)) != -1 && bytes < 2 * 1024 * 1024) {
                            zip.write(buffer, 0, n);
                            bytes += n;
                        }
                    }
                    zip.closeEntry();
                }
        }
        return result;
    }
}
