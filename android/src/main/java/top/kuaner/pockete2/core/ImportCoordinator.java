// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;

import top.kuaner.pockete2.app.InboundContent;
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

        void changed();
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
    private final ThreadPoolExecutor abort =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    r -> new Thread(r, "Pocket E2 import cancel"));
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    private final class Job {
        final ImportRoute route;
        final int total;
        final ImportRead read;
        final List<Runnable> callbacks = new ArrayList<>();
        String name = "";
        int index;
        long bytes;
        boolean started;
        int sourceIndex; // Import I/O owner only.
        volatile ImportProgress observed;
        final java.util.concurrent.atomic.AtomicBoolean updatePending =
                new java.util.concurrent.atomic.AtomicBoolean();

        Job(ImportRoute route, int total, Runnable finished) {
            this.route = route;
            this.total = total;
            callbacks.add(finished);
            read = new ImportRead((name, bytes) -> observed(this, name, bytes));
        }
    }

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
        List<Uri> copy = new ArrayList<>(sources);
        state.execute(
                () ->
                        start(
                                new ImportRoute(delivery, currentTarget(), ImportRoute.Kind.MEDIA),
                                text,
                                copy,
                                "",
                                finished));
    }

    void photo(String delivery, File original) {
        state.execute(
                () ->
                        photo(
                                new ImportRoute(delivery, currentTarget(), ImportRoute.Kind.CAMERA),
                                original));
    }

    void photo(ImportRoute route, File original) {
        try {
            if (!sharedRoot().equals(original.getCanonicalFile().getParentFile()))
                throw new IllegalArgumentException("Photo is outside shared cache");
            ImportBatch.requirePhotoName(original.getName());
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure);
        }
        content(
                route,
                "",
                Collections.singletonList(
                        PocketFiles.uri(
                                context,
                                original,
                                top.kuaner.pockete2.text.AppText.get(context)
                                        .format(
                                                "拍照-%s.jpg",
                                                new java.text.SimpleDateFormat(
                                                                "yyyyMMdd-HHmmss",
                                                                java.util.Locale.ROOT)
                                                        .format(
                                                                new java.util.Date(
                                                                        original.lastModified()))),
                                "image/jpeg")),
                original.getName(),
                () -> {});
    }

    void content(ImportRoute route, String text, List<Uri> sources, Runnable finished) {
        content(route, text, sources, "", finished);
    }

    private void content(
            ImportRoute route,
            String text,
            List<Uri> sources,
            String ownedPhoto,
            Runnable finished) {
        List<Uri> copy = new ArrayList<>(sources);
        state.execute(() -> start(route, text, copy, ownedPhoto, finished));
    }

    private String currentTarget() {
        String identity = host.repository().roomIdentity();
        return identity.equals("unpaired") ? "" : identity;
    }

    private void start(
            ImportRoute incomingRoute,
            String incomingText,
            List<Uri> incomingSources,
            String incomingPhoto,
            Runnable finished) {
        String delivery = incomingRoute.id;
        Job running = jobs.get(delivery);
        if (running != null) {
            running.callbacks.add(finished);
            return;
        }
        try {
            ImportRequest remembered = permanent.importRequest(delivery);
            final ImportRoute route = remembered == null ? incomingRoute : remembered.route;
            final String text = remembered == null ? incomingText : remembered.text;
            final List<Uri> sources =
                    remembered == null ? incomingSources : uris(remembered.sources);
            final String ownedPhoto = remembered == null ? incomingPhoto : remembered.ownedPhoto;
            String identity = host.repository().roomIdentity();
            if (route.trying() && !route.visibleIn(identity, host.trying())) {
                permanent.cancelImport(delivery);
                if (!ownedPhoto.isEmpty())
                    settle(
                            tryInbox,
                            new ImportBatch(
                                    delivery,
                                    route.target,
                                    "",
                                    0,
                                    Collections.emptyList(),
                                    ownedPhoto),
                            true);
                released(delivery, Collections.singletonList(finished));
                return;
            }
            String destination = permanent.importDestination(delivery);
            if (host.repository().imported(delivery)
                    || !destination.isEmpty() && !(host.trying() && destination.equals(identity))) {
                if (!ownedPhoto.isEmpty())
                    settle(
                            route.trying() ? tryInbox : realInbox,
                            new ImportBatch(
                                    delivery,
                                    route.target,
                                    "",
                                    0,
                                    Collections.emptyList(),
                                    ownedPhoto),
                            destination.equals(RoomRepository.IMPORT_CANCELLED));
                released(delivery, Collections.singletonList(finished));
                return;
            }
            if (route.trying()) permanent.reserveTransientImport(delivery, route.target);
            else {
                List<String> values = new ArrayList<>();
                for (Uri source : sources) values.add(source.toString());
                permanent.rememberImport(new ImportRequest(route, text, values, ownedPhoto));
            }
            Job job = new Job(route, sources.size(), finished);
            jobs.put(delivery, job);
            host.changed();
            boolean demo = route.trying();
            String scope = route.target;
            ImportInbox inbox = demo ? tryInbox : realInbox;
            long limit = route.visibleIn(identity, host.trying()) ? host.uploadLimit() : 0;
            io.execute(
                    () -> {
                        try {
                            job.read.check();
                            ImportBatch batch =
                                    inbox.prepare(
                                            delivery,
                                            scope,
                                            text,
                                            ownedPhoto,
                                            sources.size(),
                                            (index, file) -> {
                                                job.sourceIndex = index;
                                                observed(job, "", 0);
                                                return files.importFile(
                                                        sources.get(index),
                                                        file,
                                                        limit,
                                                        route.kind,
                                                        job.read);
                                            },
                                            job.read::check);
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
                                        if (job.read.cancelled())
                                            settle(
                                                    inbox,
                                                    new ImportBatch(
                                                            delivery,
                                                            scope,
                                                            "",
                                                            0,
                                                            Collections.emptyList(),
                                                            ownedPhoto),
                                                    true);
                                        else if (route.visibleIn(
                                                host.repository().roomIdentity(), host.trying()))
                                            host.notice("无法导入文件，请检查文件大小与读取权限");
                                        finish(delivery);
                                    });
                        }
                    });
        } catch (RejectedExecutionException full) {
            finish(delivery);
            host.notice("导入队列已满，请稍后再试");
        } catch (Exception failure) {
            if (jobs.containsKey(delivery)) finish(delivery);
            else main.post(finished);
            host.notice("导入内容尚未保存，重新打开应用后会重试");
        }
    }

    private void finish(String delivery) {
        Job job = jobs.remove(delivery);
        if (job != null) {
            released(delivery, new ArrayList<>(job.callbacks));
            host.changed();
        }
    }

    private void released(String delivery, List<Runnable> callbacks) {
        main.post(
                () -> {
                    try {
                        for (Runnable callback : callbacks) callback.run();
                    } finally {
                        state.execute(
                                () -> {
                                    if (!jobs.containsKey(delivery))
                                        permanent.forgetImport(delivery);
                                });
                    }
                });
    }

    private static List<Uri> uris(List<String> sources) {
        List<Uri> values = new ArrayList<>();
        for (String source : sources) values.add(Uri.parse(source));
        return values;
    }

    private void observed(Job job, String name, long bytes) {
        job.observed =
                new ImportProgress(
                        job.route.id, name, job.sourceIndex, job.total, bytes, true, false);
        if (!job.updatePending.compareAndSet(false, true)) return;
        main.postDelayed(
                () ->
                        state.execute(
                                () -> {
                                    job.updatePending.set(false);
                                    if (jobs.get(job.route.id) != job) return;
                                    ImportProgress observed = job.observed;
                                    job.started = true;
                                    job.index = observed.index;
                                    job.name = observed.name;
                                    job.bytes = observed.bytes;
                                    host.changed();
                                }),
                150);
    }

    List<ImportProgress> progress() {
        List<ImportProgress> visible = new ArrayList<>();
        for (Job job : jobs.values())
            if (job.route.visibleIn(host.repository().roomIdentity(), host.trying()))
                visible.add(
                        new ImportProgress(
                                job.route.id,
                                job.name,
                                job.index,
                                job.total,
                                job.bytes,
                                job.started,
                                job.read.cancelled()));
        return Collections.unmodifiableList(visible);
    }

    boolean pending() {
        return !progress().isEmpty();
    }

    void cancel(String delivery) {
        Job job = jobs.get(delivery);
        if (job == null || !job.route.visibleIn(host.repository().roomIdentity(), host.trying()))
            return;
        try {
            cancel(job);
        } catch (Exception failure) {
            host.notice("无法保存取消操作，请稍后重试");
        }
    }

    private void cancel(Job job) {
        if (!permanent.cancelImport(job.route.id)) return;
        job.read.cancel(abort);
        host.changed();
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
                                        if (startup) {
                                            for (ImportRequest request :
                                                    permanent.importRequests()) {
                                                List<Uri> sources = uris(request.sources);
                                                start(
                                                        request.route,
                                                        request.text,
                                                        sources,
                                                        request.ownedPhoto,
                                                        InboundContent.retainReads(
                                                                context, sources, 0));
                                            }
                                        }
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
            if (permanent.importDestination(batch.id).equals(RoomRepository.IMPORT_CANCELLED)) {
                settle(inbox, batch, true);
                return;
            }
            if (!demo && permanent.imported(batch.id)) {
                settle(
                        inbox,
                        batch,
                        permanent
                                .importDestination(batch.id)
                                .equals(RoomRepository.IMPORT_CANCELLED));
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
        for (Job job : jobs.values())
            if (job.route.trying()) {
                try {
                    cancel(job);
                } catch (Exception failure) {
                    // The reserved try receipt already prevents admission into a real room.
                    job.read.cancel(abort);
                    log.event("import", "try_cancel_deferred");
                }
            }
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
