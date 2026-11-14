// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device.e2;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Build;

import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.device.AndroidDeviceAdapter;

import java.io.IOException;

/** Example OEM adapter. Proprietary array components are an explicit local build option. */
public final class E2DeviceAdapter extends AndroidDeviceAdapter {
    @Override
    public String id() {
        return "e2";
    }

    @Override
    public boolean supports(Context context) {
        return Build.VERSION.SDK_INT == 27 && "E2 AI Recorder".equals(Build.MODEL);
    }

    @Override
    public boolean requiresDarkSystemBars() {
        return true;
    }

    @Override
    public RecordingInput openInput(
            Context context, String mode, String scene, AudioDeviceInfo preferred)
            throws IOException {
        if (preferred == null && E2ArrayInput.available(context)) {
            return new E2ArrayInput(context, mode, scene);
        }
        return super.openInput(context, mode, scene, preferred);
    }
}
