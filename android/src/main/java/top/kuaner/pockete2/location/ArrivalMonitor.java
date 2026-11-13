// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.location;

import android.Manifest;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Build;

import org.json.JSONObject;

import top.kuaner.pockete2.core.PlaceLedger;

import java.util.*;

/** Android's passive/network counterpart to visit monitoring. Off by default; never starts GPS. */
public final class ArrivalMonitor {
    private final Context context;
    private final SharedPreferences prefs;
    private final LocationManager manager;

    public ArrivalMonitor(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("place.arrivals", Context.MODE_PRIVATE);
        manager = context.getSystemService(LocationManager.class);
    }

    public boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    public boolean needsPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT >= 29
                        && context.checkSelfPermission(
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                != PackageManager.PERMISSION_GRANTED;
    }

    public void enabled(boolean value) {
        prefs.edit().putBoolean("enabled", value).apply();
        if (!value) prefs.edit().remove("candidate").remove("ledger").apply();
        refresh();
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
        try {
            manager.removeUpdates(intent());
            if (!enabled() || needsPermission()) return;
            manager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, 15 * 60_000, 200, intent());
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                manager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 15 * 60_000, 200, intent());
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private PlaceLedger ledger() {
        try {
            return new PlaceLedger(new JSONObject(prefs.getString("ledger", "{}")));
        } catch (org.json.JSONException ignored) {
            return new PlaceLedger(null);
        }
    }

    public synchronized String pending() {
        return enabled() && !needsPermission() ? ledger().pending() : "";
    }

    public synchronized void settle(String header) {
        if (!enabled()) return;
        PlaceLedger ledger = ledger();
        ledger.settle(header);
        prefs.edit().putString("ledger", ledger.json().toString()).apply();
    }

    public void location(Location location) {
        if (!enabled()
                || needsPermission()
                || location == null
                || location.getAccuracy() > 200
                || location.getTime() < System.currentTimeMillis() - 30 * 60_000) return;
        try {
            JSONObject candidate = new JSONObject(prefs.getString("candidate", "{}"));
            double lat = candidate.optDouble("lat", Double.NaN),
                    lon = candidate.optDouble("lon", Double.NaN);
            long began = candidate.optLong("at", 0);
            float[] distance = new float[1];
            if (Double.isFinite(lat) && Double.isFinite(lon))
                Location.distanceBetween(
                        lat, lon, location.getLatitude(), location.getLongitude(), distance);
            if (!Double.isFinite(lat) || distance[0] > 150 || began == 0) {
                prefs.edit()
                        .putString(
                                "candidate",
                                top.kuaner.pockete2.core.Json.object(
                                                "lat",
                                                location.getLatitude(),
                                                "lon",
                                                location.getLongitude(),
                                                "at",
                                                location.getTime())
                                        .toString())
                        .apply();
                return;
            }
            if (location.getTime() - began < 3 * 60_000 || !Geocoder.isPresent()) return;
            List<Address> places =
                    new Geocoder(context, Locale.getDefault())
                            .getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (places == null || places.isEmpty()) return;
            Address place = places.get(0);
            String name = place.getFeatureName();
            if (name == null || name.trim().isEmpty()) name = place.getThoroughfare();
            if (name == null || name.trim().isEmpty()) return;
            synchronized (this) {
                if (!enabled()) return;
                PlaceLedger ledger = ledger();
                if (ledger.arrived(
                        name, PlaceLedger.area(place.getLocality(), place.getSubLocality()), began))
                    prefs.edit().putString("ledger", ledger.json().toString()).apply();
            }
        } catch (java.io.IOException | org.json.JSONException | RuntimeException ignored) {
        }
    }
}
