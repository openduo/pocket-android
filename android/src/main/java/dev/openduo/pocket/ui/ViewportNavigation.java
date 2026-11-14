// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import dev.openduo.pocket.R;
import dev.openduo.pocket.text.AppText;
import dev.openduo.pocket.text.TextCatalog;

/** Shared keyboard and accessibility alternatives to map/image drag and pinch gestures. */
public final class ViewportNavigation {
    public interface Target {
        void pan(int horizontal, int vertical);

        void zoom(int step);
    }

    private ViewportNavigation() {}

    public static void attach(View view, Target target) {
        TextCatalog words = AppText.get(view.getContext());
        view.setFocusable(true);
        view.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
                        info.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
                        info.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
                        info.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
                        info.addAction(
                                new AccessibilityNodeInfo.AccessibilityAction(
                                        R.id.viewport_zoom_in, words.t("放大")));
                        info.addAction(
                                new AccessibilityNodeInfo.AccessibilityAction(
                                        R.id.viewport_zoom_out, words.t("缩小")));
                    }

                    @Override
                    public boolean performAccessibilityAction(
                            View host, int action, Bundle arguments) {
                        if (action == R.id.viewport_zoom_in) target.zoom(1);
                        else if (action == R.id.viewport_zoom_out) target.zoom(-1);
                        else if (action
                                == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                                        .getId()) target.pan(0, -1);
                        else if (action
                                == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                                        .getId()) target.pan(0, 1);
                        else if (action
                                == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                                        .getId()) target.pan(-1, 0);
                        else if (action
                                == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                                        .getId()) target.pan(1, 0);
                        else return super.performAccessibilityAction(host, action, arguments);
                        host.sendAccessibilityEvent(
                                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
                        return true;
                    }
                });
        view.setOnKeyListener(
                (host, code, event) -> {
                    boolean handled =
                            code == KeyEvent.KEYCODE_DPAD_UP
                                    || code == KeyEvent.KEYCODE_DPAD_DOWN
                                    || code == KeyEvent.KEYCODE_DPAD_LEFT
                                    || code == KeyEvent.KEYCODE_DPAD_RIGHT
                                    || code == KeyEvent.KEYCODE_PLUS
                                    || code == KeyEvent.KEYCODE_EQUALS
                                    || code == KeyEvent.KEYCODE_MINUS
                                    || code == KeyEvent.KEYCODE_PAGE_UP
                                    || code == KeyEvent.KEYCODE_PAGE_DOWN;
                    if (!handled || event.getAction() != KeyEvent.ACTION_DOWN) return handled;
                    if (code == KeyEvent.KEYCODE_DPAD_UP) target.pan(0, -1);
                    else if (code == KeyEvent.KEYCODE_DPAD_DOWN) target.pan(0, 1);
                    else if (code == KeyEvent.KEYCODE_DPAD_LEFT) target.pan(-1, 0);
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) target.pan(1, 0);
                    else
                        target.zoom(
                                code == KeyEvent.KEYCODE_MINUS || code == KeyEvent.KEYCODE_PAGE_DOWN
                                        ? -1
                                        : 1);
                    return true;
                });
    }
}
