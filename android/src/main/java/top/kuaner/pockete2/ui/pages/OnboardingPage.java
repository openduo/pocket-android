// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;

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
        ui.inset(root, 20, 12);
        LinearLayout content = ui.column();
        content.setGravity(Gravity.CENTER_VERTICAL);
        View mark = ui.icon("audio_lines", ui.palette.accent, 54);
        content.addView(mark);
        ui.add(content, ui.label("Pocket E2", 30, ui.palette.text, true), 16);
        ui.add(content, ui.label("随时说，接着聊", 21, ui.palette.text, true), 8);
        TextView desc =
                ui.label(
                        "连接你自己的 ambient 房间。文字、语音和随手拍的照片，都留在同一段对话里。",
                        15,
                        ui.palette.secondary,
                        false);
        desc.setLineSpacing(ui.dp(4), 1);
        ui.add(content, desc, 14);
        status = ui.label("正在读取网络状态…", 13, ui.palette.secondary, false);
        ui.add(content, status, 22);
        root.addView(ui.scroll(content), new LinearLayout.LayoutParams(-1, 0, 1));
        login = ui.button("登录 Tailscale", ui.palette.text, ui.palette.raised, host::loginTailnet);
        ui.add(root, login, 8);
        ui.add(
                root,
                ui.button(
                        "连接房间",
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> host.navigate("pairing")),
                8);
        ui.add(
                root,
                ui.button(
                        "先体验 · 预设对话",
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
                        ? "网络已连接，下一步配对你的房间"
                        : s.tailnetState.startsWith("Needs")
                                ? "先登录你的 Tailscale 网络，再连接房间"
                                : "网络状态：" + s.tailnetState);
        Ui.updateText(login, s.tailnetState.equals("Running") ? "查看 Tailscale 状态" : "登录 Tailscale");
    }

    public void close() {
        controller.removeListener(this);
    }
}
