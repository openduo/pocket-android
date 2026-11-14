// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device;

import android.content.Context;
import android.media.AudioDeviceInfo;

import dev.openduo.pocket.audio.input.AndroidRecordingInput;
import dev.openduo.pocket.audio.input.RecordingInput;

import java.io.IOException;

/** Default for phones, tablets and display devices with Android framework audio. */
public class AndroidDeviceAdapter implements DeviceAdapter {
    @Override
    public String id() {
        return "android";
    }

    @Override
    public boolean supports(Context context) {
        return true;
    }

    @Override
    public RecordingInput openInput(
            Context context, String mode, String scene, AudioDeviceInfo preferred)
            throws IOException {
        return new AndroidRecordingInput(context, "natural".equals(mode), preferred);
    }
}
