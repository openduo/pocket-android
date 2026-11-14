// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.pages;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.core.PocketController;
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
        root = ui.column();
        ui.inset(root, ui.pagePaddingDp(), 12);
        LinearLayout content = ui.column();
        content.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView mark = new android.widget.ImageView(ui.context);
        mark.setImageResource(
                ui.palette.light
                        ? dev.openduo.pocket.R.drawable.ic_brand_mark_light
                        : dev.openduo.pocket.R.drawable.ic_brand_mark_dark);
        mark.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(mark);
        ui.add(content, ui.label(ui.t("多多"), 30, ui.palette.text, true), 16);
        ui.add(content, ui.label(ui.t("随时说，接着聊"), 21, ui.palette.text, true), 8);
        TextView desc =
                ui.label(
                        ui.t("连接你自己的 ambient 房间。文字、语音和随手拍的照片，都留在同一段对话里。"),
                        15,
                        ui.palette.secondary,
                        false);
        desc.setLineSpacing(ui.dp(4), 1);
        ui.add(content, desc, 14);
        status = ui.label(ui.t("正在读取网络状态…"), 13, ui.palette.secondary, false);
        ui.add(content, status, 22);
        root.addView(ui.scroll(content), new LinearLayout.LayoutParams(-1, 0, 1));
        login =
                ui.button(
                        ui.t("登录 Tailscale"),
                        ui.palette.text,
                        ui.palette.raised,
                        host::loginTailnet);
        ui.add(root, login, 8);
        ui.add(
                root,
                ui.button(
                        ui.t("连接房间"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> host.navigate("pairing")),
                8);
        ui.add(
                root,
                ui.button(
                        ui.t("先体验 · 预设对话"),
                        ui.palette.secondary,
                        0,
                        () -> {
                            controller.startTrying();
                            host.navigate("conversation");
                        }),
                4);
        controller.addListener(this);
        controller.start();
        changed();
        return root;
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
