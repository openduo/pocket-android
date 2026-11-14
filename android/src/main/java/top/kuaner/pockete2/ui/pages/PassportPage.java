// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.passport.PassportCoordinator;
import top.kuaner.pockete2.passport.PassportLink;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.Sheets;

public final class PassportPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root, devices;
    private TextView state, detail, info, latest;
    private String deviceKey = "";

    public PassportPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public View render() {
        if (root != null) return root;
        root = ui.column();
        LinearLayout bar = ui.row();
        bar.addView(ui.iconButton("chevron_left", ui.t("返回"), ui.palette.secondary, host::back));
        bar.addView(ui.label("Passport", 18, ui.palette.text, true));
        root.addView(bar);
        LinearLayout body = ui.column();
        ui.inset(body, ui.pagePaddingDp(), 12);
        state = ui.label(ui.t("未连接"), 24, ui.palette.text, true);
        body.addView(state);
        detail = ui.label("", 13, ui.palette.secondary, false);
        ui.add(body, detail, 8);
        info = ui.label("", 12, ui.palette.subtle, false);
        ui.add(body, info, 12);
        ui.add(body, ui.label(ui.t("Passport 正在显示"), 12, ui.palette.subtle, true), 22);
        latest = ui.label(ui.t("还没有回答"), 15, ui.palette.text, false);
        ui.inset(latest, 12, 12);
        latest.setBackground(ui.surface(ui.palette.raised, 12));
        ui.add(body, latest, 8);
        devices = ui.column();
        ui.add(body, devices, 14);
        ui.add(
                body,
                ui.button(
                        ui.t("系统蓝牙设置"),
                        ui.palette.secondary,
                        ui.palette.surface,
                        () -> {
                            try {
                                host.activity()
                                        .startActivity(
                                                new Intent(
                                                        android.provider.Settings
                                                                .ACTION_BLUETOOTH_SETTINGS));
                            } catch (android.content.ActivityNotFoundException failure) {
                                host.toast(ui.t("系统没有蓝牙设置页面"));
                            }
                        }),
                14);
        ui.add(
                body,
                ui.button(
                        ui.t("忘记此设备"),
                        ui.palette.danger,
                        0,
                        () ->
                                Sheets.confirm(
                                        ui,
                                        ui.t("忘记 Passport"),
                                        ui.t("App 会停止重连。需要重新绑定时，也请在系统蓝牙设置中移除旧配对。"),
                                        ui.t("忘记设备"),
                                        controller::forgetPassport)),
                8);
        root.addView(ui.scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
        TextView scan =
                ui.button(
                        ui.t("扫描与配对"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () ->
                                host.permission(
                                        android.os.Build.VERSION.SDK_INT >= 31
                                                ? new String[] {
                                                    android.Manifest.permission.BLUETOOTH_SCAN,
                                                    android.Manifest.permission.BLUETOOTH_CONNECT
                                                }
                                                : new String[] {
                                                    android.Manifest.permission.ACCESS_FINE_LOCATION
                                                },
                                        controller::scanPassport));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.controlHeight());
        lp.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12));
        root.addView(scan, lp);
        controller.addListener(this);
        changed();
        return root;
    }

    @Override
    public void changed() {
        if (root == null || controller.snapshot() == null) return;
        PassportCoordinator.Snapshot s = controller.snapshot().passport;
        String phase =
                !s.compatible
                        ? ui.t("协议版本不匹配")
                        : s.link.ready
                                ? ui.t("已连接")
                                : s.link.state.equals("scanning")
                                        ? ui.t("正在寻找 Passport…")
                                        : s.link.state.equals("connecting")
                                                ? ui.t("正在连接…")
                                                : s.link.state.equals("pairing")
                                                        ? ui.t("请确认配对数字")
                                                        : ui.t("未连接");
        Ui.updateText(state, s.link.name.isEmpty() ? phase : s.link.name + " · " + phase);
        Ui.updateText(detail, ui.explain(s.link.detail));
        Ui.updateText(
                info,
                s.major == 0
                        ? ui.t("连接后会显示电量、固件和协议版本")
                        : ui.t("电量 ")
                                + (s.battery <= 100 ? s.battery + "%" : ui.t("未知"))
                                + " · "
                                + (s.charging == 1
                                        ? ui.t("充电中")
                                        : s.charging == 0 ? ui.t("未充电") : ui.t("充电状态未知"))
                                + ui.t("\n固件 ")
                                + s.firmware
                                + ui.t(" · 协议 ")
                                + s.major
                                + "."
                                + s.minor
                                + ui.t("\n预录 ")
                                + (s.preroll ? ui.t("开启") : ui.t("关闭"))
                                + " · MTU "
                                + s.link.mtu);
        Ui.updateText(
                latest,
                s.latestText.isEmpty()
                        ? s.waiting ? ui.t("正在等待回答…") : ui.t("还没有回答")
                        : s.latestText);
        StringBuilder key = new StringBuilder();
        for (PassportLink.Device d : s.link.devices) key.append(d.address).append(d.name);
        if (!key.toString().equals(deviceKey)) {
            deviceKey = key.toString();
            devices.removeAllViews();
            for (PassportLink.Device d : s.link.devices)
                ui.add(
                        devices,
                        ui.button(
                                d.name + " · " + d.rssi + " dBm",
                                ui.palette.text,
                                ui.palette.raised,
                                () -> controller.connectPassport(d.address)),
                        6);
        }
    }

    public void close() {
        controller.removeListener(this);
    }
}
