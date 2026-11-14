// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.app;

import android.app.Application;

import dev.openduo.pocket.device.DeviceRuntime;
import dev.openduo.pocket.device.e2.E2DeviceAdapter;

/** Composition root: add hardware integrations here without changing room or layout code. */
public final class PocketApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DeviceRuntime.install(this, new E2DeviceAdapter());
    }
}
