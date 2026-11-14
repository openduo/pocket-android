// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.ui;

import android.view.View;

import java.util.HashMap;
import java.util.Map;

/** Window-only focus memory. Android performs directional traversal and scroll-to-focus. */
public final class FocusNavigation {
    private final Map<String, View> pages = new HashMap<>();

    public void remember(String page, View root) {
        if (page == null || root == null) return;
        View focus = root.findFocus();
        if (focus != null) pages.put(page, focus);
    }

    public void restore(String page, View root) {
        if (root == null || root.isInTouchMode()) return;
        root.post(
                () -> {
                    if (!root.isShown() || root.isInTouchMode()) return;
                    View previous = pages.get(page);
                    if (previous == null || !previous.isShown() || !previous.requestFocus())
                        root.requestFocus(View.FOCUS_FORWARD);
                });
    }
}
