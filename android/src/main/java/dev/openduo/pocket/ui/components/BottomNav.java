// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.components;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.ui.Ui;

public final class BottomNav {
    public interface Listener {
        void select(String page);
    }

    private final Ui ui;
    private final Listener listener;

    public BottomNav(Ui ui, Listener listener) {
        this.ui = ui;
        this.listener = listener;
    }

    public View render(String selected) {
        return render(selected, false, false);
    }

    public View render(String selected, boolean rail, boolean iconsOnly) {
        LinearLayout root = rail ? ui.column() : ui.row();
        if (rail) {
            root.setPadding(ui.dp(6), ui.dp(iconsOnly ? 4 : 12), ui.dp(6), ui.dp(8));
            root.setBackgroundColor(ui.palette.surface);
        } else {
            root.setMinimumHeight(ui.navigationHeight());
            root.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        }
        for (String[] item :
                new String[][] {
                    {"messages_square", ui.t("对话"), "conversation"},
                    {"audio_lines", ui.t("环境"), "ambient"},
                    {"settings", ui.t("设置"), "settings"}
                }) {
            boolean active = item[2].equals(selected);
            LinearLayout control = ui.column();
            control.setGravity(Gravity.CENTER);
            control.setMinimumHeight(rail ? ui.dp(iconsOnly ? 60 : 76) : ui.touchTarget());
            control.setPadding(0, ui.dp(6), 0, ui.dp(6));
            control.addView(ui.icon(item[0], active ? ui.palette.accent : ui.palette.subtle, 21));
            TextView text =
                    ui.label(item[1], 11, active ? ui.palette.text : ui.palette.subtle, active);
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            text.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-1, -2);
            label.topMargin = ui.dp(4);
            if (!iconsOnly) control.addView(text, label);
            control.setContentDescription(item[1]);
            control.setSelected(active);
            ui.clickable(
                    control, active ? ui.palette.selected : 0, 12, () -> listener.select(item[2]));
            root.addView(
                    control,
                    rail
                            ? new LinearLayout.LayoutParams(-1, -2)
                            : new LinearLayout.LayoutParams(0, -2, 1));
        }
        return rail ? ui.scroll(root) : root;
    }
}
