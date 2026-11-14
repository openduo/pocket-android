// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import android.content.*;
import android.os.*;

import java.util.function.IntConsumer;

/** Native thermal status where available; E2/API27 uses a conservative battery-temperature hint. */
public final class DeviceHeat {
    public static final int NORMAL = 0, SERIOUS = 1, CRITICAL = 2;
    private final IntConsumer changed;
    private int level;

    public DeviceHeat(Context context, IntConsumer changed) {
        this.changed = changed;
        if (Build.VERSION.SDK_INT >= 29) {
            Api29.watch(context.getSystemService(PowerManager.class), this::thermal);
        } else {
            context.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    public int level() {
        return level;
    }

    private static final class Api29 {
        static void watch(PowerManager manager, IntConsumer changed) {
            manager.addThermalStatusListener(changed::accept);
            changed.accept(manager.getCurrentThermalStatus());
        }
    }

    private void thermal(int status) {
        int next =
                status >= PowerManager.THERMAL_STATUS_CRITICAL
                        ? CRITICAL
                        : status >= PowerManager.THERMAL_STATUS_SEVERE ? SERIOUS : NORMAL;
        set(next);
    }

    private final BroadcastReceiver battery =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (temperature < 0) return;
                    int next =
                            temperature >= 480 ? CRITICAL : temperature >= 430 ? SERIOUS : NORMAL;
                    if (level == CRITICAL && temperature > 450) next = CRITICAL;
                    else if (level == SERIOUS && temperature > 400) next = Math.max(next, SERIOUS);
                    set(next);
                }
            };

    private void set(int next) {
        if (level != next) {
            level = next;
            changed.accept(next);
        }
    }
}
