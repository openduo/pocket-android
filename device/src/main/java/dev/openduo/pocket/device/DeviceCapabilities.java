// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Geocoder;

/** Report discovered capabilities separately from model names and runtime permissions. */
public final class DeviceCapabilities {
    public final boolean microphone, camera, location, geocoder, bluetooth;

    private DeviceCapabilities(Context context) {
        PackageManager pm = context.getPackageManager();
        microphone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
        camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        location = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION);
        geocoder = Geocoder.isPresent();
        bluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
    }

    public static DeviceCapabilities discover(Context context) {
        return new DeviceCapabilities(context);
    }

    @Override
    public String toString() {
        return "microphone="
                + microphone
                + ", camera="
                + camera
                + ", location="
                + location
                + ", geocoder="
                + geocoder
                + ", bluetooth="
                + bluetooth;
    }
}
