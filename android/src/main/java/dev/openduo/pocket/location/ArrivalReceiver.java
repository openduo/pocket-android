// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.location;

import android.content.*;
import android.location.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** PendingIntent location delivery survives process death without an always-on GPS service. */
public final class ArrivalReceiver extends BroadcastReceiver {
    private static final ExecutorService WORK =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> new Thread(r, "DuoDuo Pocket arrivals"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        Runnable finish =
                () -> {
                    if (finished.compareAndSet(false, true)) pending.finish();
                };
        MAIN.postDelayed(finish, 8_000);
        try {
            WORK.execute(
                    () -> {
                        try {
                            if (finished.get() || SystemClock.elapsedRealtime() >= deadline) return;
                            ArrivalMonitor monitor = new ArrivalMonitor(context);
                            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                                    || LocationManager.PROVIDERS_CHANGED_ACTION.equals(
                                            intent.getAction())
                                    || LocationManager.MODE_CHANGED_ACTION.equals(
                                            intent.getAction())) monitor.refresh();
                            else {
                                Location location =
                                        intent.getParcelableExtra(
                                                LocationManager.KEY_LOCATION_CHANGED);
                                monitor.location(
                                        location,
                                        () ->
                                                !finished.get()
                                                        && SystemClock.elapsedRealtime()
                                                                < deadline);
                            }
                        } finally {
                            MAIN.removeCallbacks(finish);
                            finish.run();
                        }
                    });
        } catch (RejectedExecutionException full) {
            MAIN.removeCallbacks(finish);
            finish.run();
        }
    }
}
