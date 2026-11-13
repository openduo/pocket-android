// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;

import top.kuaner.pockete2.app.PocketFiles;
import top.kuaner.pockete2.diagnostics.AppLog;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Durable file handoff. Room/receipt admission runs on state; every inbox operation runs on I/O.
 */
final class ImportCoordinator {
    interface Host {
        RoomRepository repository();

        boolean trying();

        long uploadLimit();

        void admit(ImportBatch batch);

        void notice(String message);
    }

    private final Context context;
    private final Executor state;
    private final Handler main;
    private final RoomRepository permanent;
    private final AttachmentStore files;
    private final Host host;
    private final AppLog log;
    private final ImportInbox realInbox, tryInbox;
    private final ThreadPoolExecutor io =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    r -> new Thread(r, "Pocket E2 import"));
    private final Map<String, List<Runnable>> waiting = new HashMap<>();

    ImportCoordinator(
            Context context,
            Executor state,
            Handler main,
            RoomRepository permanent,
            AttachmentStore files,
            Host host) {
        this.context = context.getApplicationContext();
        this.state = state;
        this.main = main;
        this.permanent = permanent;
        this.files = files;
        this.host = host;
        log = AppLog.get(this.context);
        realInbox = new ImportInbox(new File(context.getFilesDir(), "attachments/inbox"));
        tryInbox = new ImportInbox(new File(context.getCacheDir(), "try-inbox"));
    }

    void content(String delivery, String text, List<Uri> sources, Runnable finished) {
        content(delivery, text, sources, "", finished);
    }

    void photo(String delivery, File original) {
        try {
            if (!sharedRoot().equals(original.getCanonicalFile().getParentFile()))
                throw new IllegalArgumentException("Photo is outside shared cache");
            ImportBatch.requirePhotoName(original.getName());
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure);
        }
        content(
                delivery,
                "",
                Collections.singletonList(PocketFiles.uri(context, original)),
                original.getName(),
                () -> {});
    }

    private void content(
            String delivery, String text, List<Uri> sources, String ownedPhoto, Runnable finished) {
        ImportBatch.requireId(delivery);
        List<Uri> copy = new ArrayList<>(sources);
        state.execute(() -> start(delivery, text, copy, ownedPhoto, finished));
    }

    private void start(
            String delivery, String text, List<Uri> sources, String ownedPhoto, Runnable finished) {
        List<Runnable> callbacks = waiting.get(delivery);
        if (callbacks != null) {
            callbacks.add(finished);
            return;
        }
        try {
            String identity = host.repository().roomIdentity();
            String destination = permanent.importDestination(delivery);
            if (host.repository().imported(delivery)
                    || !destination.isEmpty() && !(host.trying() && destination.equals(identity))) {
                main.post(finished);
                return;
            }
            if (host.trying()) permanent.reserveTransientImport(delivery, identity);
            waiting.put(delivery, new ArrayList<>(Collections.singletonList(finished)));
            boolean demo = host.trying();
            String scope = identity.equals("unpaired") ? "" : identity;
            ImportInbox inbox = demo ? tryInbox : realInbox;
            long limit = host.uploadLimit();
            io.execute(
                    () -> {
                        try {
                            ImportBatch batch =
                                    inbox.prepare(
                                            delivery,
                                            scope,
                                            text,
                                            ownedPhoto,
                                            sources.size(),
                                            (index, file) ->
                                                    files.importFile(
                                                            sources.get(index), file, limit));
                            state.execute(
                                    () -> {
                                        try {
                                            ready(inbox, batch, demo);
                                        } finally {
                                            finish(delivery);
                                        }
                                    });
                        } catch (Exception failure) {
                            state.execute(
                                    () -> {
                                        if (demo == host.trying()
                                                && (scope.isEmpty()
                                                        || scope.equals(
                                                                host.repository().roomIdentity())))
                                            host.notice("无法导入文件，请检查文件大小与读取权限");
                                        finish(delivery);
                                    });
                        }
                    });
        } catch (RejectedExecutionException full) {
            finish(delivery);
            host.notice("导入队列已满，请稍后再试");
        } catch (Exception failure) {
            if (waiting.containsKey(delivery)) finish(delivery);
            else main.post(finished);
            host.notice("导入内容尚未保存，重新打开应用后会重试");
        }
    }

    private void finish(String delivery) {
        List<Runnable> callbacks = waiting.remove(delivery);
        if (callbacks != null) for (Runnable callback : callbacks) main.post(callback);
    }

    /** Called on state, after restoring a permanent room's composer. */
    void recover(boolean startup) {
        Set<String> receipts = permanent.importReceipts();
        try {
            io.execute(
                    () -> {
                        try {
                            if (startup) realInbox.pruneIncomplete();
                            ImportInbox.Scan scan = realInbox.pending(receipts);
                            if (scan.unreadable > 0) log.event("import", "unreadable_manifest");
                            state.execute(
                                    () -> {
                                        for (ImportBatch batch : scan.batches)
                                            ready(realInbox, batch, false);
                                    });
                        } catch (Exception failure) {
                            log.event("import", "recovery_failed");
                        }
                    });
        } catch (RejectedExecutionException full) {
            log.event("import", "recovery_queue_full");
        }
    }

    private void ready(ImportInbox inbox, ImportBatch batch, boolean demo) {
        try {
            if (!demo && permanent.imported(batch.id)) {
                settle(inbox, batch, false);
                return;
            }
            if (demo && (!host.trying() || !batch.scope.equals(host.repository().roomIdentity()))) {
                settle(inbox, batch, true);
                return;
            }
            if (demo != host.trying()
                    || !batch.scope.isEmpty()
                            && !batch.scope.equals(host.repository().roomIdentity())) return;
            host.admit(batch);
            settle(inbox, batch, false);
        } catch (Exception failure) {
            host.notice("导入内容尚未保存，重新打开应用后会重试");
        }
    }

    private void settle(ImportInbox inbox, ImportBatch batch, boolean discard) {
        try {
            io.execute(
                    () -> {
                        try {
                            deletePhoto(batch);
                            if (discard) inbox.discard(batch);
                            else inbox.acknowledge(batch);
                        } catch (Exception failure) {
                            log.event("import", "cleanup_deferred");
                        }
                    });
        } catch (RejectedExecutionException full) {
            log.event("import", "cleanup_deferred");
        }
    }

    /** Serialized behind old copies and ahead of any next try generation's copies. */
    void clearTry() {
        Set<String> receipts = permanent.importReceipts();
        try {
            io.execute(
                    () -> {
                        try {
                            for (ImportBatch batch : tryInbox.pending(receipts).batches)
                                deletePhoto(batch);
                            deleteTree(new File(context.getCacheDir(), "try-inbox"));
                        } catch (Exception failure) {
                            log.event("import", "try_cleanup_deferred");
                        }
                    });
        } catch (RejectedExecutionException full) {
            log.event("import", "try_cleanup_deferred");
        }
    }

    private File sharedRoot() throws IOException {
        return new File(context.getCacheDir(), "shared").getCanonicalFile();
    }

    private void deletePhoto(ImportBatch batch) throws IOException {
        if (batch.ownedPhoto.isEmpty()) return;
        File root = sharedRoot(), original = new File(root, batch.ownedPhoto).getCanonicalFile();
        if (!root.equals(original.getParentFile())) throw new IOException("Invalid photo source");
        if (original.exists() && !original.delete())
            throw new IOException("Cannot remove photo source");
    }

    private static void deleteTree(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children != null)
            for (File child : children) {
                if (!directory.getCanonicalFile().equals(child.getCanonicalFile().getParentFile()))
                    throw new IOException("Invalid import cleanup child");
                deleteTree(child);
            }
        if (directory.exists() && !directory.delete())
            throw new IOException("Cannot remove try import");
    }
}
