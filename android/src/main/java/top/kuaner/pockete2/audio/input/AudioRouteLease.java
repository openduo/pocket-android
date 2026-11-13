// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio.input;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;

import java.io.IOException;
import java.util.concurrent.*;

/** Capture-thread ownership of a selected headset and any SCO route it explicitly started. */
public final class AudioRouteLease implements AutoCloseable {
    private final Context context;
    private final AudioManager manager;
    private AudioDeviceInfo selected;
    private boolean startedSco, previousSco, registered;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final BroadcastReceiver sco =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                            == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown();
                }
            };

    public AudioRouteLease(Context context, boolean headset) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(AudioManager.class);
        if (!headset) return;
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) return;
            for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int type = input.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    selected = input;
                    if (type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) break;
                }
            }
            if (selected != null && selected.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                previousSco = manager.isBluetoothScoOn();
                if (!previousSco) {
                    context.registerReceiver(
                            sco, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
                    registered = true;
                    startedSco = true;
                    manager.startBluetoothSco();
                    if (connected.await(4, TimeUnit.SECONDS)) manager.setBluetoothScoOn(true);
                    else {
                        selected = null;
                        restore();
                    }
                }
            }
        } catch (SecurityException | IllegalStateException denied) {
            selected = null;
            restore();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            selected = null;
            restore();
        }
    }

    public RecordingInput input() throws IOException {
        return ExclusiveRecordingInput.open(context, "voice", "meeting", selected);
    }

    public String name() {
        return selected == null ? "设备麦克风" : String.valueOf(selected.getProductName());
    }

    private void restore() {
        if (registered) {
            try {
                context.unregisterReceiver(sco);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
        if (startedSco) {
            try {
                manager.setBluetoothScoOn(previousSco);
                manager.stopBluetoothSco();
            } catch (SecurityException | IllegalStateException ignored) {
            }
            startedSco = false;
        }
    }

    @Override
    public void close() {
        restore();
    }
}
