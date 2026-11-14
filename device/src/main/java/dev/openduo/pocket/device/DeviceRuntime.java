// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device;

import android.content.Context;

/** The application composition root registers adapters once, before any capture starts. */
public final class DeviceRuntime {
    private static volatile DeviceAdapter current = new AndroidDeviceAdapter();

    private DeviceRuntime() {}

    public static void install(Context context, DeviceAdapter... adapters) {
        for (DeviceAdapter adapter : adapters) {
            if (adapter.supports(context)) {
                current = adapter;
                return;
            }
        }
        current = new AndroidDeviceAdapter();
    }

    public static DeviceAdapter current() {
        return current;
    }
}
