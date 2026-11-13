// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import android.content.Context;
import android.media.*;
import android.os.*;

import java.util.*;

/** Reports actual audio-device changes without selecting or modifying a system route. */
public final class AudioDevices {
    private final Set<Integer> devices = new HashSet<>();

    public AudioDevices(Context context, Runnable changed) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_ALL))
            devices.add(device.getId());
        manager.registerAudioDeviceCallback(
                new AudioDeviceCallback() {
                    @Override
                    public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                        boolean different = false;
                        for (AudioDeviceInfo device : added)
                            different |= devices.add(device.getId());
                        if (different) changed.run();
                    }

                    @Override
                    public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                        boolean different = false;
                        for (AudioDeviceInfo device : removed)
                            different |= devices.remove(device.getId());
                        if (different) changed.run();
                    }
                },
                new Handler(Looper.getMainLooper()));
    }
}
