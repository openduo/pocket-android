// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.app.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import dev.openduo.pocket.device.DeviceRuntime;
import dev.openduo.pocket.input.InputAction;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-input-device overrides; Android navigation and system keys always retain their meaning. */
public final class InputBindings {
    public static final class Binding {
        public final String id, device;
        public final int key, scan;
        public final InputAction action;

        Binding(String id, String device, int key, int scan, InputAction action) {
            this.id = id;
            this.device = device;
            this.key = key;
            this.scan = scan;
            this.action = action;
        }
    }

    private final SharedPreferences preferences;

    public InputBindings(Context context) {
        preferences =
                context.getSharedPreferences("foreground-input-bindings", Context.MODE_PRIVATE);
    }

    public static boolean reserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    public static boolean reserved(KeyEvent event) {
        return reserved(event.getKeyCode())
                || event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && event.getScanCode() == 0;
    }

    public static String device(KeyEvent event) {
        InputDevice device = event.getDevice();
        return device == null ? "virtual" : device.getDescriptor();
    }

    public static String id(KeyEvent event) {
        String descriptor = device(event);
        // The descriptor is local only; stable UUID avoids leaking hardware names into preference
        // keys.
        String hash =
                java.util
                        .UUID
                        .nameUUIDFromBytes(
                                descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        return hash + ":" + event.getKeyCode() + ":" + event.getScanCode();
    }

    public InputAction resolve(KeyEvent event) {
        if (reserved(event)) return null;
        String saved = preferences.getString(id(event), null);
        if (saved != null) {
            try {
                return InputAction.valueOf(new JSONObject(saved).getString("action"));
            } catch (Exception invalid) {
                /* Fall back to the current adapter's default. */
            }
        }
        return DeviceRuntime.current().keyAction(event.getKeyCode(), event.getScanCode());
    }

    public void save(KeyEvent event, InputAction action) {
        if (reserved(event)) throw new IllegalArgumentException("Reserved input key");
        InputDevice device = event.getDevice();
        try {
            JSONObject value = new JSONObject();
            value.put("device", device == null ? "" : device.getName());
            value.put("key", event.getKeyCode());
            value.put("scan", event.getScanCode());
            value.put("action", action.name());
            preferences.edit().putString(id(event), value.toString()).apply();
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public void remove(String id) {
        preferences.edit().remove(id).apply();
    }

    public List<Binding> defaults() {
        List<Binding> result = new ArrayList<>();
        for (int key = 1; key <= KeyEvent.getMaxKeyCode(); key++) {
            InputAction action = DeviceRuntime.current().keyAction(key, 0);
            if (action != null && !reserved(key)) result.add(new Binding("", "", key, 0, action));
        }
        return result;
    }

    public List<Binding> all() {
        List<Binding> result = new ArrayList<>();
        for (String id : preferences.getAll().keySet()) {
            try {
                JSONObject value = new JSONObject(preferences.getString(id, ""));
                result.add(
                        new Binding(
                                id,
                                value.optString("device"),
                                value.getInt("key"),
                                value.getInt("scan"),
                                InputAction.valueOf(value.getString("action"))));
            } catch (Exception invalid) {
                /* Ignore malformed preferences without changing defaults. */
            }
        }
        result.sort(Comparator.comparing(b -> b.id));
        return result;
    }
}
