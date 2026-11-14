// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.RequestScope;
import dev.openduo.pocket.net.RoomAccess;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Attachment jobs and their native/file lifetimes. Room mutations and notices stay on state. */
public final class AttachmentTransfers implements AutoCloseable {
    public interface Storage {
        File shareable(File source, Attachment attachment, boolean temporary) throws IOException;

        File cached(Attachment attachment, String scope);

        File cache(Attachment attachment, byte[] bytes, String scope) throws IOException;
    }

    public interface Result {
        void ready(File file, String mime);

        default void failed() {}
    }

    public static final class Room {
        final String identity;
        final boolean temporary;
        final RoomAccess access;

        public Room(String identity, boolean temporary, RoomAccess access) {
            this.identity = identity;
            this.temporary = temporary;
            this.access = access;
        }
    }

    private final class Job {
        final long ticket = generation;
        final Room room;
        final Runnable cancelled;
        volatile boolean retired;
        RequestScope requests;

        Job(Room room, Runnable cancelled) {
            this.room = room;
            this.cancelled = cancelled;
        }

        synchronized ChannelClient client() throws Exception {
            if (retired || room.access == null)
                throw new IOException("Attachment job retired or unpaired");
            requests = new RequestScope(room.access);
            return room.access.client(requests);
        }

        synchronized void closeRequest() {
            if (requests != null) requests.close();
        }

        synchronized void retire() {
            retired = true;
            closeRequest();
        }

        boolean current() {
            return !retired && ticket == generation;
        }
    }

    private final Storage files;
    private final Executor state, main;
    private final Consumer<String> notice;
    private final ThreadPoolExecutor io;
    private final Set<Job> jobs = new HashSet<>();
    private final Map<String, Job> uploads = new HashMap<>();
    private volatile long generation;

    public AttachmentTransfers(
            Storage files, Executor state, Executor main, Consumer<String> notice) {
        this(
                files,
                state,
                main,
                notice,
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16),
                        r -> new Thread(r, "DuoDuo Pocket attachments")));
    }

    public AttachmentTransfers(
            Storage files,
            Executor state,
            Executor main,
            Consumer<String> notice,
            ThreadPoolExecutor io) {
        this.files = files;
        this.state = state;
        this.main = main;
        this.notice = notice;
        this.io = io;
    }

    public void upload(
            Attachment attachment,
            Room room,
            ChannelClient.State limits,
            BiConsumer<Attachment, ChannelClient.Failure> completed) {
        cancelUpload(attachment.localPath);
        Job job = new Job(room, () -> {});
        jobs.add(job);
        uploads.put(attachment.localPath, job);
        submit(
                job,
                () -> {
                    Attachment result = null;
                    ChannelClient.Failure failure = null;
                    try {
                        result = job.client().upload(attachment, limits);
                    } catch (Exception error) {
                        failure =
                                error instanceof ChannelClient.Failure
                                        ? (ChannelClient.Failure) error
                                        : new ChannelClient.Failure("附件上传失败，点按重试", 0, true);
                    } finally {
                        job.closeRequest();
                    }
                    Attachment uploaded = result;
                    ChannelClient.Failure problem = failure;
                    state.execute(
                            () -> {
                                if (!jobs.remove(job)) return;
                                uploads.remove(attachment.localPath, job);
                                if (job.current() && room.access.current())
                                    completed.accept(uploaded, problem);
                            });
                },
                () -> {
                    uploads.remove(attachment.localPath, job);
                    completed.accept(null, new ChannelClient.Failure("附件上传失败，点按重试", 0, true));
                });
    }

    public void cancelUpload(String path) {
        Job job = uploads.remove(path);
        if (job != null) cancel(job);
    }

    public void cancelUploads() {
        for (String path : new ArrayList<>(uploads.keySet())) cancelUpload(path);
    }

    /** Room/Try changes also invalidate callbacks already posted to Android's main thread. */
    public void reset() {
        generation++;
        for (Job job : new ArrayList<>(jobs)) cancel(job);
        uploads.clear();
        io.getQueue().clear();
    }

    private void cancel(Job job) {
        if (!jobs.remove(job)) return;
        job.retire();
        main.execute(job.cancelled);
    }

    private void submit(Job job, Runnable action, Runnable rejected) {
        try {
            io.execute(
                    () -> {
                        if (job.current()) action.run();
                    });
        } catch (RejectedExecutionException occupied) {
            jobs.remove(job);
            job.retire();
            rejected.run();
        }
    }

    public void load(Attachment attachment, Room room, boolean preview, Result result) {
        Job job = new Job(room, result::failed);
        jobs.add(job);
        submit(
                job,
                () -> {
                    File ready = null;
                    boolean downloaded = false;
                    Exception problem = null;
                    try {
                        if (!attachment.localPath.isEmpty()
                                && new File(attachment.localPath).isFile())
                            ready =
                                    preview
                                            ? new File(attachment.localPath)
                                            : files.shareable(
                                                    new File(attachment.localPath),
                                                    attachment,
                                                    room.temporary);
                        else {
                            String cache = room.temporary ? room.identity.substring(4) : "";
                            ready = files.cached(attachment, cache);
                            if (ready == null) {
                                ready =
                                        files.cache(
                                                attachment,
                                                job.client().attachment(attachment),
                                                cache);
                                downloaded = true;
                            }
                            if (room.temporary && !preview)
                                ready = files.shareable(ready, attachment, true);
                        }
                    } catch (Exception failure) {
                        problem = failure;
                    } finally {
                        job.closeRequest();
                    }
                    File file = ready;
                    boolean remote = downloaded;
                    Exception failure = problem;
                    state.execute(
                            () -> {
                                boolean admitted = jobs.remove(job) && job.current();
                                if (failure != null) {
                                    if (!admitted) return;
                                    if (!preview && (room.access == null || room.access.current()))
                                        notice.accept(
                                                room.access == null ? "请先配对房间" : "附件读取失败，请稍后重试");
                                    main.execute(result::failed);
                                } else if (!admitted) cleanupTemporary(room, file);
                                else
                                    main.execute(
                                            () -> {
                                                if (job.current()
                                                        && (!remote || room.access.current()))
                                                    result.ready(file, attachment.mime);
                                                else {
                                                    cleanupTemporary(room, file);
                                                    result.failed();
                                                }
                                            });
                            });
                },
                () -> {
                    if (!preview) notice.accept("附件读取失败，请稍后重试");
                    main.execute(result::failed);
                });
    }

    private static void cleanupTemporary(Room room, File file) {
        if (!room.temporary || file == null) return;
        file.delete();
        if (file.getParentFile() != null) file.getParentFile().delete();
    }

    @Override
    public void close() {
        reset();
        io.shutdownNow();
    }
}
