// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.location;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Native preferences/distance/clock with prepared fixes, permission and geocoder outcomes. */
public final class ArrivalMonitorProbe {
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private Location fix(long ageMs, double latitude) {
        Location location = new Location("qa-prepared");
        location.setLatitude(latitude);
        location.setLongitude(20);
        location.setAccuracy(25);
        location.setTime(System.currentTimeMillis() - ageMs);
        location.setElapsedRealtimeNanos((SystemClock.elapsedRealtime() - ageMs) * 1_000_000);
        return location;
    }

    private void begin(SharedPreferences prefs) {
        prefs.edit()
                .clear()
                .putBoolean("enabled", true)
                .putLong("enabled_at", System.currentTimeMillis() - 60 * 60_000)
                .commit();
    }

    public static int run(Context context) throws Exception {
        return new ArrivalMonitorProbe().verify(context);
    }

    private int verify(Context context) throws Exception {
        SharedPreferences prefs =
                context.getSharedPreferences("place.arrivals.qa", Context.MODE_PRIVATE);
        // Toggling this isolated monitor must never remove the real monitor's PendingIntent.
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public Object getSystemService(String name) {
                        return Context.LOCATION_SERVICE.equals(name)
                                ? null
                                : super.getSystemService(name);
                    }
                };
        AtomicBoolean permitted = new AtomicBoolean(true);
        AtomicInteger queries = new AtomicInteger();
        Address place = new Address(Locale.ENGLISH);
        place.setFeatureName("Prepared place");
        place.setLocality("Prepared city");
        place.setSubLocality("Prepared district");
        ArrivalMonitor.Lookup lookup =
                (latitude, longitude) -> {
                    queries.incrementAndGet();
                    return place;
                };
        ArrivalMonitor monitor = new ArrivalMonitor(isolated, prefs, permitted::get, lookup);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            check(
                    isolated.getSystemService(LocationManager.class) == null,
                    "isolated arrival checks cannot register or remove actual location requests");
            begin(prefs);
            Location first = fix(5 * 60_000, 10);
            monitor.location(first);
            String candidate = prefs.getString("candidate", "");
            monitor.location(first);
            monitor.location(fix(6 * 60_000, 11));
            check(
                    prefs.getString("candidate", "").equals(candidate)
                            && queries.get() == 0
                            && monitor.pending().isEmpty(),
                    "duplicate and out-of-order fixes cannot restart a stay or report a place");
            monitor.location(fix(60_000, 10.0001));
            String header = monitor.pending();
            JSONObject body = new JSONObject(java.net.URLDecoder.decode(header, "UTF-8"));
            check(
                    queries.get() == 1
                            && body.getString("name").equals("Prepared place")
                            && !body.has("lat")
                            && !body.has("lon"),
                    "a sufficiently long prepared stay records a named arrival without"
                            + " coordinates");
            ArrivalMonitor reopened = new ArrivalMonitor(isolated, prefs, permitted::get, lookup);
            check(reopened.pending().equals(header), "pending arrival survives monitor recreation");
            reopened.settle(header);
            check(
                    monitor.pending().isEmpty(),
                    "accepted arrival settles through another monitor instance");
            monitor.location(fix(30_000, 11));
            check(
                    queries.get() == 1 && monitor.pending().isEmpty(),
                    "movement starts a new stay without an immediate report");
            candidate = prefs.getString("candidate", "");
            Location invalid = fix(0, 12);
            invalid.removeAccuracy();
            monitor.location(invalid);
            invalid = fix(0, 12);
            invalid.setAccuracy(Float.NaN);
            monitor.location(invalid);
            monitor.location(fix(31 * 60_000, 12));
            monitor.location(fix(-60_000, 12));
            check(
                    prefs.getString("candidate", "").equals(candidate) && queries.get() == 1,
                    "missing accuracy, invalid accuracy, stale and future fixes cannot alter the"
                            + " stay");

            begin(prefs);
            monitor.location(fix(5 * 60_000, 10));
            JSONObject previousBoot = new JSONObject(prefs.getString("candidate", "{}"));
            previousBoot.put("boot", -777777);
            prefs.edit().putString("candidate", previousBoot.toString()).commit();
            monitor.location(fix(60_000, 10));
            check(
                    queries.get() == 1 && monitor.pending().isEmpty(),
                    "a candidate from a previous boot cannot complete a false stay");
            begin(prefs);
            prefs.edit().putLong("enabled_at", System.currentTimeMillis()).commit();
            monitor.location(fix(60_000, 10));
            check(!prefs.contains("candidate"), "a cached fix from before opting in is ignored");

            for (String change : new String[] {"off-on", "permission", "expired"}) {
                begin(prefs);
                permitted.set(true);
                AtomicBoolean current = new AtomicBoolean(true);
                CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
                ArrivalMonitor waiting =
                        new ArrivalMonitor(
                                isolated,
                                prefs,
                                permitted::get,
                                (latitude, longitude) -> {
                                    started.countDown();
                                    try {
                                        if (!release.await(3, TimeUnit.SECONDS))
                                            throw new java.io.IOException(
                                                    "Prepared lookup timeout");
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new java.io.IOException(interrupted);
                                    }
                                    return place;
                                });
                waiting.location(fix(5 * 60_000, 10));
                Future<?> inflight =
                        worker.submit(() -> waiting.location(fix(60_000, 10), current::get));
                try {
                    check(
                            started.await(2, TimeUnit.SECONDS),
                            "prepared reverse lookup starts before " + change);
                    if (change.equals("off-on")) {
                        reopened.enabled(false);
                        reopened.enabled(true);
                    } else if (change.equals("permission")) permitted.set(false);
                    else current.set(false);
                } finally {
                    release.countDown();
                }
                inflight.get(3, TimeUnit.SECONDS);
                check(
                        !prefs.contains("ledger"),
                        "a late lookup cannot store an arrival after " + change);
            }
            permitted.set(true);
            begin(prefs);
            ArrivalMonitor sourceStatus = new ArrivalMonitor(context, prefs, () -> true, lookup);
            LocationManager sources = context.getSystemService(LocationManager.class);
            if (sources != null && sources.getProviders(true).isEmpty())
                check(
                        sourceStatus.status().equals("系统定位未开启，到达检测已暂停；点按打开定位设置"),
                        "actual disabled providers produce a paused explanation under prepared"
                                + " permission");
            if (sources != null && sources.getProviders(true).isEmpty()) receiverDeadline(context);
            prefs.edit().clear().commit();
            monitor.settle(header);
            check(
                    !prefs.contains("ledger"),
                    "a late acknowledgement cannot restore a disabled ledger");
            return assertions;
        } finally {
            worker.shutdownNow();
            prefs.edit().clear().commit();
        }
    }

    private void receiverDeadline(Context context) throws Exception {
        java.lang.reflect.Field field = ArrivalReceiver.class.getDeclaredField("WORK");
        field.setAccessible(true);
        ThreadPoolExecutor work = (ThreadPoolExecutor) field.get(null);
        check(
                work.getActiveCount() == 0 && work.getQueue().isEmpty(),
                "arrival receiver is idle before the bounded-work check");
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> blocker =
                work.submit(
                        () -> {
                            blocked.countDown();
                            try {
                                release.await(15, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        try {
            check(
                    blocked.await(2, TimeUnit.SECONDS),
                    "prepared stalled work occupies the actual arrival executor");
            CountDownLatch expired = new CountDownLatch(1), rejected = new CountDownLatch(1);
            Handler main = new Handler(Looper.getMainLooper());
            Future<?> queued = work.submit(() -> {});
            check(
                    work.getQueue().size() == 1,
                    "prepared pending work fills the bounded arrival slot");
            context.sendOrderedBroadcast(
                    new Intent(context, ArrivalReceiver.class).setAction("arrival.location"),
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            rejected.countDown();
                        }
                    },
                    main,
                    0,
                    null,
                    null);
            check(
                    rejected.await(2, TimeUnit.SECONDS),
                    "an overflow broadcast finishes promptly instead of adding unbounded work");
            work.remove((Runnable) queued);
            queued.cancel(false);
            long began = SystemClock.elapsedRealtime();
            context.sendOrderedBroadcast(
                    new Intent(context, ArrivalReceiver.class).setAction("arrival.location"),
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            expired.countDown();
                        }
                    },
                    main,
                    0,
                    null,
                    null);
            long until = SystemClock.elapsedRealtime() + 2000;
            while (work.getQueue().isEmpty() && SystemClock.elapsedRealtime() < until)
                Thread.sleep(20);
            check(
                    work.getQueue().size() == 1,
                    "the real broadcast occupies the bounded pending slot");
            check(
                    expired.await(10, TimeUnit.SECONDS)
                            && SystemClock.elapsedRealtime() - began < 10_000
                            && release.getCount() == 1,
                    "Android completes the pending broadcast within its budget while prepared work"
                            + " remains blocked");
        } finally {
            release.countDown();
            blocker.get(3, TimeUnit.SECONDS);
            work.submit(() -> {}).get(3, TimeUnit.SECONDS);
        }
    }
}
