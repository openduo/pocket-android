// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device.e2;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.view.KeyEvent;

import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.device.AndroidDeviceAdapter;
import dev.openduo.pocket.input.InputAction;

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
    public InputAction keyAction(int keyCode, int scanCode) {
        if (keyCode == KeyEvent.KEYCODE_F1) return InputAction.TOGGLE_VOICE;
        if (keyCode == KeyEvent.KEYCODE_F2) return InputAction.CANCEL_OR_STOP;
        return super.keyAction(keyCode, scanCode);
    }

    @Override
    public RecordingInput openInput(
            Context context, String mode, String scene, AudioDeviceInfo preferred)
            throws IOException {
        if (preferred == null && E2ArrayInput.requiresArray()) {
            if (E2ArrayInput.available(context)) return new E2ArrayInput(context, mode, scene);
            // This firmware's framework input opens successfully but supplies no microphone signal.
            throw new RecordingInput.Unavailable("当前版本无法使用此设备的内置麦克风，请连接耳机麦克风");
        }
        return super.openInput(context, mode, scene, preferred);
    }
}
