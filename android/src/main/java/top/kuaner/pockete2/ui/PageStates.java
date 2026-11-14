// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui;

import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.View;
import android.widget.EditText;

/** Saves native form/scroll state per retained page, including pages not currently visible. */
public final class PageStates {
    private final Bundle pages;

    public PageStates(Bundle saved) {
        pages = saved == null ? new Bundle() : new Bundle(saved);
    }

    public void capture(String name, View page) {
        Bundle state = new Bundle();
        SparseArray<Parcelable> hierarchy = new SparseArray<>();
        page.saveHierarchyState(hierarchy);
        state.putSparseParcelableArray("hierarchy", hierarchy);
        View focused = page.findFocus();
        if (focused != null && focused.getId() != View.NO_ID) {
            state.putInt("focus", focused.getId());
            if (focused instanceof EditText) {
                state.putInt("start", ((EditText) focused).getSelectionStart());
                state.putInt("end", ((EditText) focused).getSelectionEnd());
            }
        }
        pages.putBundle(name, state);
    }

    public void restore(String name, View page) {
        Bundle state = pages.getBundle(name);
        if (state == null) return;
        SparseArray<Parcelable> hierarchy = state.getSparseParcelableArray("hierarchy");
        if (hierarchy != null) page.restoreHierarchyState(hierarchy);
        page.post(
                () -> {
                    View focused = page.findViewById(state.getInt("focus", View.NO_ID));
                    if (focused == null || !focused.isShown()) return;
                    focused.requestFocus();
                    if (focused instanceof EditText && state.containsKey("start")) {
                        EditText field = (EditText) focused;
                        int start = Math.max(0, Math.min(field.length(), state.getInt("start")));
                        int end = Math.max(start, Math.min(field.length(), state.getInt("end")));
                        field.setSelection(start, end);
                    }
                });
    }

    public Bundle save() {
        return new Bundle(pages);
    }
}
