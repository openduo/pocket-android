// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;

import java.util.function.IntConsumer;

/** Camera-only orientation sensing; a 15 degree hysteresis avoids encoder churn near boundaries. */
public final class DeviceOrientation {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OrientationEventListener sensor;
    private volatile boolean active;
    private int rotation;

    public DeviceOrientation(Context context, IntConsumer changed) {
        sensor =
                new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                    @Override
                    public void onOrientationChanged(int degrees) {
                        if (!active || degrees == ORIENTATION_UNKNOWN) return;
                        int next = SightProtocol.quadrant(degrees, rotation);
                        if (next != rotation) {
                            rotation = next;
                            changed.accept(next);
                        }
                    }
                };
    }

    public void active(boolean value) {
        if (active == value) return;
        active = value;
        main.post(
                () -> {
                    if (active && sensor.canDetectOrientation()) sensor.enable();
                    else sensor.disable();
                });
    }
}
