// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import dev.openduo.pocket.diagnostics.AppLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default-path changes, including a new address on the same Wi-Fi; never changes system settings.
 */
public final class NetworkPath implements AutoCloseable {
    private final ConnectivityManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final AppLog log;
    private String last;
    private boolean registered;
    private final Runnable sample = this::sample;
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    schedule();
                }

                @Override
                public void onLost(Network network) {
                    schedule();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities value) {
                    schedule();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties value) {
                    schedule();
                }
            };

    public NetworkPath(Context context, Runnable changed) {
        manager = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        this.changed = changed;
        log = AppLog.get(context);
    }

    public void start() {
        main.post(
                () -> {
                    if (registered || manager == null) return;
                    try {
                        last = null;
                        manager.registerDefaultNetworkCallback(callback, main);
                        registered = true;
                        sample();
                    } catch (RuntimeException failure) {
                        log.event("network_path", "monitor_unavailable");
                    }
                });
    }

    private void schedule() {
        if (!registered) return;
        // Sample the current default after the callback burst. A delayed onLost for the old
        // network during handover must not label the new default as offline.
        main.removeCallbacks(sample);
        main.postDelayed(sample, 200);
    }

    private void sample() {
        if (!registered) return;
        try {
            Network network = manager.getActiveNetwork();
            String now = signature(network);
            if (now.equals(last)) return;
            boolean first = last == null;
            last = now;
            // The comparison stays in memory. Diagnostics contain no IPs, routes or network IDs.
            log.event(
                    "network_path",
                    (first ? "initial_" : "changed_") + (network == null ? "down" : "up"));
            if (!first) changed.run();
        } catch (RuntimeException failure) {
            log.event("network_path", "sample_unavailable");
        }
    }

    private String signature(Network network) {
        if (network == null) return "down";
        List<String> parts = new ArrayList<>();
        parts.add("network:" + network.getNetworkHandle());
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        if (capabilities != null) {
            parts.add(
                    "internet:"
                            + capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET));
            parts.add(
                    "validated:"
                            + capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            for (int transport = 0; transport <= 7; transport++)
                if (capabilities.hasTransport(transport)) parts.add("transport:" + transport);
        }
        LinkProperties link = manager.getLinkProperties(network);
        if (link != null) {
            parts.add("interface:" + link.getInterfaceName());
            link.getLinkAddresses().forEach(address -> parts.add("address:" + address));
            link.getRoutes().forEach(route -> parts.add("route:" + route));
            link.getDnsServers().forEach(address -> parts.add("dns:" + address.getHostAddress()));
            parts.add("domains:" + link.getDomains());
            parts.add("mtu:" + link.getMtu());
        }
        Collections.sort(parts);
        return parts.toString();
    }

    @Override
    public void close() {
        main.post(
                () -> {
                    main.removeCallbacks(sample);
                    if (!registered) return;
                    registered = false;
                    try {
                        manager.unregisterNetworkCallback(callback);
                    } catch (RuntimeException ignored) {
                    }
                    last = null;
                });
    }
}
