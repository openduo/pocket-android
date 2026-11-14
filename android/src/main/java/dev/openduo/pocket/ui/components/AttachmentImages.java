// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.components;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.PocketController;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Page-owned thumbnail cache. Disk, download and decoding never run on the UI thread. */
public final class AttachmentImages implements AutoCloseable {
    private final PocketController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    r -> new Thread(r, "DuoDuo Pocket thumbnails"));
    private final Map<String, List<WeakReference<ImageView>>> waiting = new HashMap<>();
    private final Map<ImageView, Request> requests = new WeakHashMap<>();
    private final Map<String, Long> failedUntil = new HashMap<>();
    private final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>(3 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };
    private boolean closed;

    private static final class Request {
        final Attachment attachment;
        final int limit;
        final String key;

        Request(Attachment attachment, int limit) {
            this.attachment = attachment;
            this.limit = limit;
            key = attachment.sha256 + "|" + attachment.localPath + "|" + limit;
        }
    }

    public AttachmentImages(PocketController controller) {
        this.controller = controller;
    }

    public void bind(ImageView target, Attachment attachment, int limit) {
        Request request = new Request(attachment, limit);
        target.setTag(request.key);
        target.setContentDescription(attachment.name);
        requests.put(target, request);
        target.post(this::refreshVisible);
    }

    public void refreshVisible() {
        if (closed) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        for (Map.Entry<ImageView, Request> entry : requests.entrySet()) {
            ImageView image = entry.getKey();
            if (!image.isShown() || !image.getGlobalVisibleRect(rect)) {
                image.setImageDrawable(null);
            } else if (image.getDrawable() == null) load(image, entry.getValue());
        }
    }

    private void load(ImageView target, Request request) {
        String key = request.key;
        Attachment attachment = request.attachment;
        int limit = request.limit;
        if (failedUntil.getOrDefault(key, 0L) > android.os.SystemClock.elapsedRealtime()) return;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            target.setImageBitmap(hit);
            return;
        }
        List<WeakReference<ImageView>> views = waiting.get(key);
        if (views != null) {
            for (WeakReference<ImageView> ref : views) if (ref.get() == target) return;
            views.add(new WeakReference<>(target));
            return;
        }
        if (waiting.size() >= 12) return;
        views = new ArrayList<>();
        views.add(new WeakReference<>(target));
        waiting.put(key, views);
        PocketController.FileResult result =
                new PocketController.FileResult() {
                    @Override
                    public void ready(File file, String mime) {
                        if (closed) return;
                        try {
                            worker.execute(
                                    () -> {
                                        Bitmap bitmap = null;
                                        try {
                                            bitmap = ImageBitmaps.decode(file, limit);
                                        } catch (Exception | OutOfMemoryError ignored) {
                                        }
                                        Bitmap decoded = bitmap;
                                        main.post(() -> finish(key, decoded));
                                    });
                        } catch (RejectedExecutionException full) {
                            finish(key, null);
                        }
                    }

                    @Override
                    public void failed() {
                        finish(key, null);
                    }
                };
        controller.previewAttachment(attachment, result);
    }

    private void finish(String key, Bitmap bitmap) {
        List<WeakReference<ImageView>> views = waiting.remove(key);
        if (closed) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        if (bitmap != null) cache.put(key, bitmap);
        else failedUntil.put(key, android.os.SystemClock.elapsedRealtime() + 30_000);
        if (views == null) return;
        for (WeakReference<ImageView> ref : views) {
            ImageView image = ref.get();
            if (image != null
                    && key.equals(image.getTag())
                    && bitmap != null
                    && image.isShown()
                    && image.getGlobalVisibleRect(new android.graphics.Rect()))
                image.setImageBitmap(bitmap);
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        waiting.clear();
        requests.clear();
        failedUntil.clear();
        cache.evictAll();
    }
}
