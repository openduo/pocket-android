// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.location;

import android.content.*;
import android.location.*;

import java.util.concurrent.*;

/** PendingIntent location delivery survives process death without an always-on GPS service. */
public final class ArrivalReceiver extends BroadcastReceiver {
    private static final ExecutorService WORK =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "Pocket E2 arrivals"));

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        WORK.execute(
                () -> {
                    try {
                        ArrivalMonitor monitor = new ArrivalMonitor(context);
                        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
                            monitor.refresh();
                        else {
                            Location location =
                                    intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
                            monitor.location(location);
                        }
                    } finally {
                        pending.finish();
                    }
                });
    }
}
