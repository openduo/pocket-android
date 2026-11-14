// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.location;

import android.Manifest;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import top.kuaner.pockete2.core.PlaceLedger;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Android's passive/network counterpart to visit monitoring. Off by default; never starts GPS. */
public final class ArrivalMonitor {
    private static final Object LOCK = new Object();
    private static final long MAX_AGE_MS = 30 * 60_000, DWELL_MS = 3 * 60_000;

    interface Lookup {
        Address find(double latitude, double longitude) throws java.io.IOException;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final LocationManager manager;
    private final BooleanSupplier permitted;
    private final Lookup lookup;
    private String statusCache = "";
    private long statusCheckedAt;

    public ArrivalMonitor(Context context) {
        this(
                context,
                context.getSharedPreferences("place.arrivals", Context.MODE_PRIVATE),
                null,
                (latitude, longitude) -> {
                    if (!Geocoder.isPresent()) return null;
                    List<Address> places =
                            new Geocoder(context.getApplicationContext(), Locale.getDefault())
                                    .getFromLocation(latitude, longitude, 1);
                    return places == null || places.isEmpty() ? null : places.get(0);
                });
    }

    ArrivalMonitor(
            Context context, SharedPreferences prefs, BooleanSupplier permitted, Lookup lookup) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.permitted = permitted;
        this.lookup = lookup;
        manager = context.getSystemService(LocationManager.class);
    }

    public boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    public boolean needsPermission() {
        if (permitted != null) return !permitted.getAsBoolean();
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT >= 29
                        && context.checkSelfPermission(
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                != PackageManager.PERMISSION_GRANTED;
    }

    public void enabled(boolean value) {
        synchronized (LOCK) {
            SharedPreferences.Editor edit = prefs.edit().putBoolean("enabled", value);
            if (enabled() != value) {
                edit.putLong("generation", prefs.getLong("generation", 0) + 1);
                if (value) edit.putLong("enabled_at", System.currentTimeMillis() / 1000 * 1000);
            }
            if (!value) edit.remove("candidate").remove("ledger");
            edit.apply();
        }
        refresh();
    }

    public synchronized String status() {
        long now = SystemClock.elapsedRealtime();
        if (statusCheckedAt > 0 && now - statusCheckedAt < 5_000) return statusCache;
        statusCache = sourceStatus();
        statusCheckedAt = now;
        return statusCache;
    }

    private String sourceStatus() {
        if (!enabled()) return "Android 使用低频网络与被动定位，停留后记录地名；省电策略可能延迟。";
        if (needsPermission()) return "等待定位权限。需要精确位置；Android 10 及以上还需在系统设置中始终允许。";
        try {
            if (manager == null || manager.getAllProviders().isEmpty())
                return "这台设备没有可用的定位来源，到达检测已暂停";
            if (manager.getProviders(true).isEmpty()) return "系统定位未开启，到达检测已暂停；点按打开定位设置";
            if (!Geocoder.isPresent()) return "这台设备暂时无法查询地名，到达检测已暂停";
            if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                return "等待其他应用提供定位，停留后记录地名；不会主动打开 GPS。";
        } catch (SecurityException | IllegalArgumentException unavailable) {
            return "定位暂时不可用，到达检测已暂停；点按检查定位设置";
        }
        return "Android 使用低频网络与被动定位，停留后记录地名；省电策略可能延迟。";
    }

    private PendingIntent intent() {
        return PendingIntent.getBroadcast(
                context,
                31,
                new Intent(context, ArrivalReceiver.class).setAction("arrival.location"),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
    }

    public void refresh() {
        synchronized (this) {
            statusCheckedAt = 0;
        }
        synchronized (LOCK) {
            if (manager == null) return;
            try {
                manager.removeUpdates(intent());
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
            if (!enabled() || needsPermission()) return;
            for (String provider :
                    new String[] {
                        LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER
                    }) {
                try {
                    if (manager.getAllProviders().contains(provider))
                        manager.requestLocationUpdates(provider, 15 * 60_000, 200, intent());
                } catch (SecurityException | IllegalArgumentException ignored) {
                }
            }
        }
    }

    private PlaceLedger ledger() {
        try {
            return new PlaceLedger(new JSONObject(prefs.getString("ledger", "{}")));
        } catch (org.json.JSONException ignored) {
            return new PlaceLedger(null);
        }
    }

    public String pending() {
        synchronized (LOCK) {
            return enabled() && !needsPermission() ? ledger().pending() : "";
        }
    }

    public void settle(String header) {
        synchronized (LOCK) {
            if (!enabled() || needsPermission() || !prefs.contains("ledger")) return;
            PlaceLedger ledger = ledger();
            ledger.settle(header, prefs.getLong("enabled_at", 0));
            prefs.edit().putString("ledger", ledger.json().toString()).apply();
        }
    }

    public void location(Location location) {
        location(location, () -> true);
    }

    void location(Location location, BooleanSupplier current) {
        if (location == null || !location.hasAccuracy()) return;
        long elapsed = location.getElapsedRealtimeNanos() / 1_000_000;
        long age = SystemClock.elapsedRealtime() - elapsed;
        if (elapsed <= 0
                || age < 0
                || age > MAX_AGE_MS
                || location.getTime() <= 0
                || location.getTime() > System.currentTimeMillis() + 60_000
                || !Float.isFinite(location.getAccuracy())
                || location.getAccuracy() < 0
                || location.getAccuracy() > 200
                || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude())
                || Math.abs(location.getLatitude()) > 90
                || Math.abs(location.getLongitude()) > 180) return;
        try {
            long generation, began;
            synchronized (LOCK) {
                if (!enabled() || needsPermission() || !current.getAsBoolean()) return;
                if (location.getTime() < prefs.getLong("enabled_at", 0)) return;
                generation = prefs.getLong("generation", 0);
                JSONObject candidate = new JSONObject(prefs.getString("candidate", "{}"));
                double lat = candidate.optDouble("lat", Double.NaN),
                        lon = candidate.optDouble("lon", Double.NaN);
                long since = candidate.optLong("elapsed", 0),
                        last = candidate.optLong("last_elapsed", 0);
                int boot =
                        android.provider.Settings.Global.getInt(
                                context.getContentResolver(),
                                android.provider.Settings.Global.BOOT_COUNT,
                                -1);
                boolean reboot =
                        candidate.optInt("boot", -2) != boot
                                || last > SystemClock.elapsedRealtime();
                began = candidate.optLong("at", 0);
                if (!reboot && last > 0 && elapsed <= last) return;
                float[] distance = new float[1];
                if (Double.isFinite(lat) && Double.isFinite(lon))
                    Location.distanceBetween(
                            lat, lon, location.getLatitude(), location.getLongitude(), distance);
                boolean fresh =
                        !Double.isFinite(lat)
                                || !Double.isFinite(lon)
                                || distance[0] > 150
                                || reboot
                                || since <= 0
                                || began <= 0
                                || elapsed - last > MAX_AGE_MS;
                if (fresh) {
                    candidate =
                            top.kuaner.pockete2.core.Json.object(
                                    "lat", location.getLatitude(),
                                    "lon", location.getLongitude(),
                                    "at", location.getTime(),
                                    "elapsed", elapsed,
                                    "boot", boot);
                }
                candidate.put("last_elapsed", elapsed);
                prefs.edit().putString("candidate", candidate.toString()).apply();
                if (fresh || elapsed - since < DWELL_MS) return;
            }
            if (!current.getAsBoolean()) return;
            Address place = lookup.find(location.getLatitude(), location.getLongitude());
            if (place == null) return;
            String name = place.getFeatureName();
            if (name == null || name.trim().isEmpty()) name = place.getThoroughfare();
            if (name == null || name.trim().isEmpty()) return;
            synchronized (LOCK) {
                if (!enabled()
                        || needsPermission()
                        || !current.getAsBoolean()
                        || generation != prefs.getLong("generation", 0)
                        || new JSONObject(prefs.getString("candidate", "{}")).optLong("at")
                                != began) return;
                PlaceLedger ledger = ledger();
                if (ledger.arrived(
                        name, PlaceLedger.area(place.getLocality(), place.getSubLocality()), began))
                    prefs.edit().putString("ledger", ledger.json().toString()).apply();
            }
        } catch (java.io.IOException | org.json.JSONException | RuntimeException ignored) {
        }
    }
}
