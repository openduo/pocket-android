// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import top.kuaner.pockete2.ui.Ui;

import java.util.function.Consumer;

/** Bounded, full-width bottom sheets with wrapping labels and 48dp targets. */
public final class Sheets {
    public static final class Action {
        final String text;
        final Runnable run;
        final boolean danger;

        public Action(String text, Runnable run) {
            this(text, false, run);
        }

        public Action(String text, boolean danger, Runnable run) {
            this.text = text;
            this.danger = danger;
            this.run = run;
        }
    }

    private Sheets() {}

    public static Dialog content(Ui ui, String title, LinearLayout body) {
        Dialog dialog = build(ui, title, body);
        show(ui, dialog);
        return dialog;
    }

    public static Dialog actions(Ui ui, String title, Action... actions) {
        LinearLayout body = ui.column();
        Dialog dialog = build(ui, title, body);
        for (Action action : actions)
            body.addView(
                    ui.button(
                            action.text,
                            action.danger ? ui.palette.danger : ui.palette.text,
                            0,
                            () -> {
                                dialog.dismiss();
                                action.run.run();
                            }));
        show(ui, dialog);
        return dialog;
    }

    public static Dialog confirm(
            Ui ui, String title, String message, String commit, Runnable action) {
        LinearLayout body = ui.column();
        Dialog dialog = build(ui, title, body);
        TextView text = ui.label(message, 14, ui.palette.secondary, false);
        ui.inset(text, 8, 12);
        body.addView(text);
        ui.add(
                body,
                ui.button(
                        commit,
                        ui.palette.background,
                        ui.palette.danger,
                        () -> {
                            dialog.dismiss();
                            action.run();
                        }),
                12);
        ui.add(body, ui.button(ui.t("取消"), ui.palette.secondary, 0, dialog::dismiss), 4);
        show(ui, dialog);
        return dialog;
    }

    public static Dialog edit(Ui ui, String title, String initial, Consumer<String> action) {
        LinearLayout body = ui.column();
        Dialog dialog = build(ui, title, body);
        EditText input = ui.input(ui.t("输入文字"), initial, true);
        body.addView(input);
        ui.add(
                body,
                ui.button(
                        ui.t("确定"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> {
                            action.accept(input.getText().toString());
                            dialog.dismiss();
                        }),
                12);
        show(ui, dialog);
        return dialog;
    }

    private static Dialog build(Ui ui, String title, LinearLayout body) {
        Dialog dialog =
                new Dialog(ui.context) {
                    @Override
                    public void onWindowFocusChanged(boolean focused) {
                        super.onWindowFocusChanged(focused);
                        if (focused) ui.immersive(getWindow());
                    }
                };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet =
                new LinearLayout(ui.context) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int maximum = ui.sheetHeight();
                        super.onMeasure(
                                width,
                                View.MeasureSpec.makeMeasureSpec(
                                        maximum, View.MeasureSpec.AT_MOST));
                    }
                };
        sheet.setOrientation(LinearLayout.VERTICAL);
        ui.inset(sheet, 14, 10);
        sheet.setBackground(ui.surface(ui.palette.surface, 18));
        LinearLayout heading = ui.row();
        TextView label = ui.label(title, 16, ui.palette.text, true);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(
                ui.iconButton("chevron_down", ui.t("关闭面板"), ui.palette.secondary, dialog::dismiss),
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        sheet.addView(heading);
        ScrollView scroll = new ScrollView(ui.context);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        ui.immersive(window);
        return dialog;
    }

    private static void show(Ui ui, Dialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        window.setLayout(ui.sheetWidth(), -2);
    }
}
