// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Actual framework geocoder against a public landmark; never substitutes a fix or a backend. */
final class LocationServiceProbe {
    String run(Context context) throws Exception {
        LocationManager locations = context.getSystemService(LocationManager.class);
        JSONObject result =
                new JSONObject()
                        .put("api", android.os.Build.VERSION.SDK_INT)
                        .put("geocoderPresent", Geocoder.isPresent())
                        .put("providers", String.valueOf(locations.getAllProviders()))
                        .put("enabledProviders", String.valueOf(locations.getProviders(true)))
                        .put(
                                "scope",
                                "actual public-landmark geocoder; no physical visit or simulated"
                                        + " fix");
        boolean passed = false;
        if (Geocoder.isPresent()) {
            java.util.concurrent.ExecutorService worker =
                    Executors.newSingleThreadExecutor(
                            r -> {
                                Thread thread = new Thread(r, "Pocket QA geocoder");
                                thread.setDaemon(true);
                                return thread;
                            });
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                JSONObject lookup =
                        worker.submit(
                                        () -> {
                                            Geocoder geocoder =
                                                    new Geocoder(context, Locale.ENGLISH);
                                            List<Address> search =
                                                    geocoder.getFromLocationName(
                                                            "Tiananmen Square, Beijing", 3);
                                            List<Address> reverse =
                                                    geocoder.getFromLocation(39.9087, 116.3975, 1);
                                            return new JSONObject()
                                                    .put(
                                                            "searchResults",
                                                            search == null ? 0 : search.size())
                                                    .put(
                                                            "reverseResults",
                                                            reverse == null ? 0 : reverse.size());
                                        })
                                .get(20, TimeUnit.SECONDS);
                result.put("searchResults", lookup.getInt("searchResults"))
                        .put("reverseResults", lookup.getInt("reverseResults"));
                passed = lookup.getInt("searchResults") > 0 && lookup.getInt("reverseResults") > 0;
            } catch (java.util.concurrent.ExecutionException failure) {
                result.put("errorType", failure.getCause().getClass().getSimpleName());
            } catch (java.util.concurrent.TimeoutException failure) {
                result.put("errorType", "TimeoutException");
            } finally {
                worker.shutdownNow();
            }
            result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
        }
        result.put("passed", passed);
        File directory = new File(context.getExternalCacheDir(), "location-service");
        directory.mkdirs();
        Files.write(
                new File(directory, "result.json").toPath(),
                result.toString(2).getBytes(StandardCharsets.UTF_8));
        return (passed ? "PASS " : "NOT_RUN actual geocoder unavailable: ") + result;
    }
}
