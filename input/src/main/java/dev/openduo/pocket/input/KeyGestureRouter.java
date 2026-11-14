// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.input;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reduces paired key edges; repeats never toggle, and release uses the binding from key-down. */
public final class KeyGestureRouter {
    public interface Target {
        void begin(String owner);

        void end(String owner, boolean cancel);

        void invoke(InputAction action, String owner);
    }

    private final Target target;
    private final Map<String, InputAction> pressed = new LinkedHashMap<>();

    public KeyGestureRouter(Target target) {
        this.target = target;
    }

    public boolean down(String key, InputAction binding, boolean repeat) {
        if (pressed.containsKey(key)) {
            if (repeat || pressed.get(key) != InputAction.DISABLED) return true;
            // A new physical press may follow a lost key-up while another window had focus.
            pressed.remove(key);
        }
        if (repeat || binding == null) return false;
        pressed.put(key, binding);
        if (binding == InputAction.HOLD_TO_TALK) target.begin(key);
        return true;
    }

    public boolean up(String key, boolean canceled) {
        InputAction binding = pressed.remove(key);
        if (binding == null) return false;
        if (binding == InputAction.HOLD_TO_TALK) target.end(key, canceled);
        else if (!canceled && binding != InputAction.DISABLED) target.invoke(binding, key);
        return true;
    }

    /** Retain swallowed edges until key-up, so a focus loss cannot turn a held key into a click. */
    public void cancel() {
        for (Map.Entry<String, InputAction> entry : pressed.entrySet()) {
            if (entry.getValue() == InputAction.HOLD_TO_TALK) target.end(entry.getKey(), true);
            entry.setValue(InputAction.DISABLED);
        }
    }
}
