// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.app.input;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import dev.openduo.pocket.input.InputAction;
import dev.openduo.pocket.input.InputTarget;
import dev.openduo.pocket.input.KeyGestureRouter;

/**
 * Receives only keys dispatched to the foreground activity; ordinary focus traversal stays native.
 */
public final class HardwareInput {
    private final InputBindings bindings;
    private final InputTarget target;
    private final KeyGestureRouter gestures;

    public HardwareInput(InputBindings bindings, InputTarget target) {
        this.bindings = bindings;
        this.target = target;
        gestures = new KeyGestureRouter(target);
    }

    public boolean dispatch(KeyEvent event, View focus) {
        String owner = target.owner("key:" + event.getDeviceId() + ":" + InputBindings.id(event));
        if (event.getAction() == KeyEvent.ACTION_UP) return gestures.up(owner, event.isCanceled());
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        InputAction action = bindings.resolve(event);
        // Never take text entry or modified shortcuts away from an editor.
        if (focus instanceof EditText && (event.isPrintingKey() || event.getUnicodeChar() != 0)
                || !event.hasNoModifiers()) action = null;
        return gestures.down(owner, action, event.getRepeatCount() != 0);
    }

    public void cancel() {
        gestures.cancel();
    }
}
