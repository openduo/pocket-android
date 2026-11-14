// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.location;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded native Web Mercator map. Only visible OSM tiles are fetched; no browser bridge. */
public final class TileMapView extends View implements AutoCloseable {
    public interface Listener {
        void center(double latitude, double longitude);
    }

    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LruCache<String, Bitmap> memory = new LruCache<>(24);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(24),
                    r -> new Thread(r, "Pocket E2 map"));
    private final File directory;
    private double x = .5, y = .5;
    private int zoom = 2;
    private float lastX, lastY;
    private boolean moved, closed;

    public TileMapView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        directory = new File(context.getCacheDir(), "map-tiles");
        directory.mkdirs();
        setBackgroundColor(0xff222321);
        setContentDescription(
                top.kuaner.pockete2.text.AppText.get(context).t("地图，拖动选择中心位置，缩放按钮在地图下方"));
        setFocusable(true);
        setClickable(true);
        top.kuaner.pockete2.ui.ViewportNavigation.attach(
                this,
                new top.kuaner.pockete2.ui.ViewportNavigation.Target() {
                    public void pan(int horizontal, int vertical) {
                        double scale = 256.0 * (1 << zoom);
                        x = (x + horizontal * getWidth() * .25 / scale + 1) % 1;
                        y = Math.max(0, Math.min(1, y + vertical * getHeight() * .25 / scale));
                        invalidate();
                        listener.center(latitude(), longitude());
                    }

                    public void zoom(int step) {
                        TileMapView.this.zoom(step);
                    }
                });
    }

    public double latitude() {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
    }

    public double longitude() {
        return x * 360 - 180;
    }

    public void center(double latitude, double longitude, int zoom) {
        this.zoom = Math.max(2, Math.min(18, zoom));
        x = (longitude + 180) / 360;
        double sin =
                Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latitude))));
        y = .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
        invalidate();
    }

    public void zoom(int delta) {
        zoom = Math.max(2, Math.min(18, zoom + delta));
        failed.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = 1 << zoom;
        double scale = 256.0 * count;
        double left = x * scale - getWidth() / 2.0, top = y * scale - getHeight() / 2.0;
        int firstX = (int) Math.floor(left / 256), firstY = (int) Math.floor(top / 256);
        for (int dy = 0; dy <= getHeight() / 256 + 1; dy++)
            for (int dx = 0; dx <= getWidth() / 256 + 1; dx++) {
                int tx = firstX + dx, ty = firstY + dy;
                if (ty < 0 || ty >= count) continue;
                int wrapped = ((tx % count) + count) % count;
                String key = zoom + "-" + wrapped + "-" + ty;
                Bitmap bitmap = memory.get(key);
                if (bitmap != null)
                    canvas.drawBitmap(
                            bitmap, (float) (tx * 256 - left), (float) (ty * 256 - top), paint);
                else load(key, zoom, wrapped, ty);
            }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        paint.setColor(0xff1fd9de);
        canvas.drawCircle(cx, cy, 9, paint);
        paint.setColor(0xff0a1414);
        canvas.drawCircle(cx, cy, 3, paint);
        paint.setColor(0xd0000000);
        canvas.drawRect(0, getHeight() - 24, getWidth(), getHeight(), paint);
        paint.setTextSize(17);
        paint.setColor(0xffdeded8);
        canvas.drawText("© OpenStreetMap contributors", 8, getHeight() - 7, paint);
    }

    private void load(String key, int z, int x, int y) {
        if (closed || pending.contains(key) || failed.contains(key)) return;
        pending.add(key);
        try {
            worker.execute(
                    () -> {
                        Bitmap bitmap = null;
                        File target = new File(directory, key + ".png");
                        try {
                            if (target.isFile())
                                bitmap = BitmapFactory.decodeFile(target.getPath());
                            if (bitmap == null) {
                                HttpURLConnection connection =
                                        (HttpURLConnection)
                                                new URL(
                                                                "https://tile.openstreetmap.org/"
                                                                        + z
                                                                        + "/"
                                                                        + x
                                                                        + "/"
                                                                        + y
                                                                        + ".png")
                                                        .openConnection();
                                connection.setConnectTimeout(5000);
                                connection.setReadTimeout(5000);
                                connection.setRequestProperty(
                                        "User-Agent", "PocketE2/0.1 (native Android map)");
                                try {
                                    if (connection.getResponseCode() != 200)
                                        throw new IOException();
                                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                                    try (InputStream in = connection.getInputStream()) {
                                        byte[] buffer = new byte[4096];
                                        int n;
                                        while ((n = in.read(buffer)) != -1) {
                                            if (out.size() + n > 512 * 1024)
                                                throw new IOException();
                                            out.write(buffer, 0, n);
                                        }
                                    }
                                    byte[] bytes = out.toByteArray();
                                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                    if (bitmap == null) throw new IOException();
                                    try (FileOutputStream file = new FileOutputStream(target)) {
                                        file.write(bytes);
                                    }
                                } finally {
                                    connection.disconnect();
                                }
                            }
                        } catch (IOException | RuntimeException ignored) {
                        }
                        final Bitmap result = bitmap;
                        post(
                                () -> {
                                    pending.remove(key);
                                    if (closed) return;
                                    if (result != null) memory.put(key, result);
                                    else failed.add(key);
                                    invalidate();
                                });
                        File[] files = directory.listFiles();
                        if (files != null && files.length > 256) {
                            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                            for (int i = 0; i < files.length - 256; i++) files[i].delete();
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            pending.remove(key);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                double scale = 256.0 * (1 << zoom);
                x = (x + (lastX - event.getX()) / scale + 1) % 1;
                y = Math.max(0, Math.min(1, y + (lastY - event.getY()) / scale));
                lastX = event.getX();
                lastY = event.getY();
                moved = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (moved) listener.center(latitude(), longitude());
                else performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        listener.center(latitude(), longitude());
        return true;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        memory.evictAll();
    }
}
