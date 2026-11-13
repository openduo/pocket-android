// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.ui.Ui;

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
        LinearLayout root = ui.row();
        root.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5));
        for (String[] item :
                new String[][] {
                    {"messages_square", "对话", "conversation"},
                    {"audio_lines", "环境", "ambient"},
                    {"settings", "设置", "settings"}
                }) {
            boolean active = item[2].equals(selected);
            LinearLayout control = ui.column();
            control.setGravity(Gravity.CENTER);
            control.addView(ui.icon(item[0], active ? ui.palette.accent : ui.palette.subtle, 21));
            TextView text =
                    ui.label(item[1], 11, active ? ui.palette.text : ui.palette.subtle, active);
            text.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-1, -2);
            label.topMargin = ui.dp(4);
            control.addView(text, label);
            control.setContentDescription(item[1]);
            ui.clickable(
                    control, active ? ui.palette.selected : 0, 12, () -> listener.select(item[2]));
            root.addView(control, new LinearLayout.LayoutParams(0, ui.dp(50), 1));
        }
        return root;
    }
}
