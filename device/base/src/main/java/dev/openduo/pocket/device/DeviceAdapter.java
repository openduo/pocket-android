// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.view.KeyEvent;

import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.input.InputAction;

import java.io.IOException;

/** Hardware integration only. Window geometry and room state do not belong here. */
public interface DeviceAdapter {
    String id();

    boolean supports(Context context);

    /** Called on the capture owner thread, after acquiring the shared microphone lease. */
    RecordingInput openInput(Context context, String mode, String scene, AudioDeviceInfo preferred)
            throws IOException;

    /** Some firmware cannot render dark system icons, even when requested by Android. */
    default boolean requiresDarkSystemBars() {
        return false;
    }

    /** Foreground key defaults only. Unknown keys and standard navigation stay with Android. */
    default InputAction keyAction(int keyCode, int scanCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ? InputAction.TOGGLE_VOICE : null;
    }
}
