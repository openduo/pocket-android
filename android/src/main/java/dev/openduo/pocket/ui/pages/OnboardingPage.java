// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.pages;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.BoundedColumn;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;

/** First-run network login and pairing. Try mode is explicitly identified as prepared content. */
public final class OnboardingPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root;
    private TextView status, login;

    public OnboardingPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public View render() {
        if (root != null) return root;
        boolean wide = ui.metrics.twoPaneOnboarding();
        boolean narrow = ui.metrics.narrowWidth();
        boolean shortWindow = ui.metrics.shortHeight();
        root = wide ? ui.row() : ui.column();
        ui.inset(root, ui.pagePaddingDp(), narrow || shortWindow ? 8 : 12);
        BoundedColumn content = new BoundedColumn(ui.context, ui.dp(wide ? 560 : 540));
        android.widget.ImageView mark = new android.widget.ImageView(ui.context);
        mark.setImageResource(
                ui.palette.light
                        ? dev.openduo.pocket.R.drawable.ic_brand_mark_light
                        : dev.openduo.pocket.R.drawable.ic_brand_mark_dark);
        mark.setLayoutParams(
                new LinearLayout.LayoutParams(
                        ui.dp(narrow || shortWindow ? 42 : 72),
                        ui.dp(narrow || shortWindow ? 42 : 72)));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView title =
                ui.label(ui.t("多多"), narrow || shortWindow ? 20 : 30, ui.palette.text, true);
        if (narrow || shortWindow) {
            LinearLayout brand = ui.row();
            brand.addView(mark);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setAutoSizeTextTypeUniformWithConfiguration(
                    12, 22, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
            titleParams.leftMargin = ui.dp(8);
            brand.addView(title, titleParams);
            content.addView(brand);
        } else {
            content.addView(mark);
            ui.add(content, title, 16);
        }
        ui.add(
                content,
                ui.label(ui.t("随时说，接着聊"), narrow || shortWindow ? 17 : 21, ui.palette.text, true),
                8);
        TextView desc =
                ui.label(
                        ui.t("连接你自己的 ambient 房间。文字、语音和随手拍的照片，都留在同一段对话里。"),
                        narrow || shortWindow ? 13 : 15,
                        ui.palette.secondary,
                        false);
        desc.setLineSpacing(ui.dp(4), 1);
        ui.add(content, desc, narrow || shortWindow ? 10 : 14);
        status = ui.label(ui.t("正在读取网络状态…"), narrow ? 12 : 13, ui.palette.secondary, false);
        ui.add(content, status, narrow || shortWindow ? 12 : 22);
        BoundedColumn actions = new BoundedColumn(ui.context, ui.dp(wide ? 420 : 540));
        login =
                ui.button(
                        ui.t("登录 Tailscale"),
                        ui.palette.text,
                        ui.palette.raised,
                        host::loginTailnet);
        ui.add(actions, login, 8);
        ui.add(
                actions,
                ui.button(
                        ui.t("连接房间"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> host.navigate("pairing")),
                8);
        ui.add(
                actions,
                ui.button(
                        ui.t("先体验 · 预设对话"),
                        ui.palette.secondary,
                        0,
                        () -> {
                            controller.startTrying();
                            host.navigate("conversation");
                        }),
                4);
        if (wide) {
            root.addView(centeredScroll(content), new LinearLayout.LayoutParams(0, -1, 1));
            FrameLayout actionPane = new FrameLayout(ui.context);
            actionPane.setPadding(ui.dp(16), 0, 0, 0);
            actions.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
            actions.setBackground(ui.surface(ui.palette.surface, 18));
            actionPane.addView(actions, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            root.addView(actionPane, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            ui.add(content, actions, narrow ? 16 : 24);
            root.addView(centeredScroll(content), new LinearLayout.LayoutParams(-1, -1));
        }
        controller.addListener(this);
        controller.start();
        changed();
        return root;
    }

    private ScrollView centeredScroll(View content) {
        FrameLayout centering = new FrameLayout(ui.context);
        centering.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        ScrollView scroll = ui.scroll(centering);
        scroll.setFillViewport(true);
        scroll.setDefaultFocusHighlightEnabled(false);
        return scroll;
    }

    @Override
    public void changed() {
        PocketController.Snapshot s = controller.snapshot();
        if (s == null || root == null) return;
        Ui.updateText(
                status,
                s.tailnetState.equals("Running")
                        ? ui.t("网络已连接，下一步配对你的房间")
                        : s.tailnetState.startsWith("Needs")
                                ? ui.t("先登录你的 Tailscale 网络，再连接房间")
                                : ui.t("网络状态：") + s.tailnetState);
        Ui.updateText(
                login,
                s.tailnetState.equals("Running") ? ui.t("查看 Tailscale 状态") : ui.t("登录 Tailscale"));
    }

    public void close() {
        controller.removeListener(this);
    }
}
