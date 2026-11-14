// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.app.Dialog;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;

/** Visible login handoff, including devices without a browser. Auth URLs stay in memory. */
public final class TailnetLoginSheet {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final TextView status, detail, link;
    private final View copy, browser, join;
    private final Code qr;
    private final boolean hasBrowser;
    private final Dialog dialog;
    private String url = "";
    private boolean automatic;
    private long deadline;
    private final Runnable timeout = this::update;

    public TailnetLoginSheet(
            PageHost host, PocketController controller, boolean automatic, Runnable dismissed) {
        this.host = host;
        this.controller = controller;
        this.automatic = automatic;
        ui = host.ui();
        hasBrowser =
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://login.tailscale.com"))
                                .resolveActivity(host.activity().getPackageManager())
                        != null;
        LinearLayout body = ui.column();
        status = ui.label("", 15, ui.palette.text, true);
        ui.add(body, status, 4);
        detail = ui.label("", 13, ui.palette.secondary, false);
        ui.add(body, detail, 8);
        qr = new Code();
        qr.setContentDescription(ui.t("Tailscale 登录二维码"));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.topMargin = ui.dp(12);
        body.addView(qr, qp);
        link = ui.label("", 12, ui.palette.secondary, false);
        link.setTextIsSelectable(true);
        ui.add(body, link, 8);
        copy =
                ui.button(
                        ui.t("复制登录链接"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> ui.copy(ui.t("Tailscale 登录链接"), url));
        ui.add(body, copy, 8);
        browser = ui.button(ui.t("在浏览器中登录"), ui.palette.accent, ui.palette.raised, this::open);
        ui.add(body, browser, 4);
        join =
                ui.button(
                        ui.t("继续连接房间"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> {
                            close();
                            host.navigate(controller.configured() ? "conversation" : "pairing");
                        });
        ui.add(body, join, 8);
        ui.add(body, ui.button(ui.t("刷新登录状态"), ui.palette.secondary, 0, this::refresh), 4);
        dialog = Sheets.content(ui, ui.t("登录 Tailscale"), body);
        dialog.setOnDismissListener(
                ignored -> {
                    status.removeCallbacks(timeout);
                    dismissed.run();
                });
        deadline = SystemClock.elapsedRealtime() + 20000;
        status.postDelayed(timeout, 20000);
        update();
    }

    public void update() {
        PocketController.Snapshot snapshot = controller.snapshot();
        String state = snapshot == null ? "NotStarted" : snapshot.tailnetState;
        String next = snapshot == null ? "" : snapshot.authUrl;
        boolean connected = state.equals("Running"), failed = state.equals("Failed");
        boolean available = !connected && !next.isEmpty();
        String heading =
                connected
                        ? ui.t("Tailscale 已连接，节点 pocket-e2")
                        : state.equals("NeedsMachineAuth")
                                ? ui.t("等待管理员批准这台设备")
                                : available
                                        ? ui.t("请完成 Tailscale 授权")
                                        : failed
                                                ? ui.t("无法启动 Tailscale，请检查网络后重试")
                                                : SystemClock.elapsedRealtime() >= deadline
                                                        ? ui.t("尚未取得登录链接，请检查 Wi-Fi 后刷新")
                                                        : ui.t("正在获取 Tailscale 登录地址…");
        Ui.updateText(status, heading);
        Ui.updateText(
                detail,
                connected
                        ? ui.t("下一步读取或扫描房间管理页生成的配对链接。")
                        : state.equals("NeedsMachineAuth")
                                ? ui.t("请在 Tailscale 管理后台批准 pocket-e2，然后刷新状态。")
                                : available
                                        ? hasBrowser
                                                ? ui.t("在浏览器中登录与房间相同的 Tailscale 网络，也可以用手机扫描下方二维码。")
                                                : ui.t("设备没有浏览器，请用手机扫码登录房间所在的 Tailscale 网络。")
                                        : ui.t("请保持 Wi-Fi 连接。取得登录链接后会显示二维码。"));
        qr.setVisibility(available ? View.VISIBLE : View.GONE);
        link.setVisibility(available ? View.VISIBLE : View.GONE);
        copy.setVisibility(available ? View.VISIBLE : View.GONE);
        browser.setVisibility(available && hasBrowser ? View.VISIBLE : View.GONE);
        join.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (!url.equals(next)) {
            url = next;
            qr.value(next);
            Ui.updateText(link, next);
        }
        if (available && hasBrowser && automatic) {
            automatic = false;
            open();
        }
    }

    private void refresh() {
        deadline = SystemClock.elapsedRealtime() + 20000;
        status.removeCallbacks(timeout);
        status.postDelayed(timeout, 20000);
        controller.loginTailnet();
        update();
    }

    private void open() {
        if (url.isEmpty()) return;
        try {
            host.activity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Ui.updateText(detail, ui.t("无法打开浏览器，请用手机扫描二维码或复制登录链接。"));
        }
    }

    public void close() {
        dialog.dismiss();
    }

    private final class Code extends View {
        private final Paint paint = new Paint();
        private BitMatrix modules;

        Code() {
            super(ui.context);
        }

        void value(String text) {
            try {
                modules =
                        text.isEmpty()
                                ? null
                                : new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1);
            } catch (WriterException | IllegalArgumentException invalid) {
                modules = null;
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int width, int height) {
            int size = Math.min(MeasureSpec.getSize(width), ui.dp(216));
            setMeasuredDimension(MeasureSpec.getSize(width), size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (modules == null) return;
            int scale =
                    Math.min(getWidth() / modules.getWidth(), getHeight() / modules.getHeight());
            if (scale < 1) return;
            int size = scale * modules.getWidth(),
                    x = (getWidth() - size) / 2,
                    y = (getHeight() - size) / 2;
            paint.setColor(Color.WHITE);
            canvas.drawRect(x, y, x + size, y + size, paint);
            paint.setColor(Color.BLACK);
            for (int row = 0; row < modules.getHeight(); row++)
                for (int col = 0; col < modules.getWidth(); col++)
                    if (modules.get(col, row))
                        canvas.drawRect(
                                x + col * scale,
                                y + row * scale,
                                x + (col + 1) * scale,
                                y + (row + 1) * scale,
                                paint);
        }
    }
}
