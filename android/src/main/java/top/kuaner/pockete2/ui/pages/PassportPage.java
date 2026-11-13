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
        bar.addView(ui.iconButton("chevron_left", "返回", ui.palette.secondary, host::back));
        bar.addView(ui.label("Passport", 18, ui.palette.text, true));
        root.addView(bar);
        LinearLayout body = ui.column();
        ui.inset(body, 16, 12);
        state = ui.label("未连接", 24, ui.palette.text, true);
        body.addView(state);
        detail = ui.label("", 13, ui.palette.secondary, false);
        ui.add(body, detail, 8);
        info = ui.label("", 12, ui.palette.subtle, false);
        ui.add(body, info, 12);
        ui.add(body, ui.label("Passport 正在显示", 12, ui.palette.subtle, true), 22);
        latest = ui.label("还没有回答", 15, ui.palette.text, false);
        ui.inset(latest, 12, 12);
        latest.setBackground(ui.surface(ui.palette.raised, 12));
        ui.add(body, latest, 8);
        devices = ui.column();
        ui.add(body, devices, 14);
        ui.add(
                body,
                ui.button(
                        "系统蓝牙设置",
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
                                host.toast("系统没有蓝牙设置页面");
                            }
                        }),
                14);
        ui.add(
                body,
                ui.button(
                        "忘记此设备",
                        ui.palette.danger,
                        0,
                        () ->
                                Sheets.confirm(
                                        ui,
                                        "忘记 Passport",
                                        "App 会停止重连。需要重新绑定时，也请在系统蓝牙设置中移除旧配对。",
                                        "忘记设备",
                                        controller::forgetPassport)),
                8);
        root.addView(ui.scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
        TextView scan =
                ui.button(
                        "扫描与配对",
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.dp(50));
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
                        ? "协议版本不匹配"
                        : s.link.ready
                                ? "已连接"
                                : s.link.state.equals("scanning")
                                        ? "正在寻找 Passport…"
                                        : s.link.state.equals("connecting")
                                                ? "正在连接…"
                                                : s.link.state.equals("pairing")
                                                        ? "请确认配对数字"
                                                        : "未连接";
        Ui.updateText(state, s.link.name.isEmpty() ? phase : s.link.name + " · " + phase);
        Ui.updateText(detail, s.link.detail);
        Ui.updateText(
                info,
                s.major == 0
                        ? "连接后会显示电量、固件和协议版本"
                        : "电量 "
                                + (s.battery <= 100 ? s.battery + "%" : "未知")
                                + " · "
                                + (s.charging == 1 ? "充电中" : s.charging == 0 ? "未充电" : "充电状态未知")
                                + "\n固件 "
                                + s.firmware
                                + " · 协议 "
                                + s.major
                                + "."
                                + s.minor
                                + "\n预录 "
                                + (s.preroll ? "开启" : "关闭")
                                + " · MTU "
                                + s.link.mtu);
        Ui.updateText(
                latest, s.latestText.isEmpty() ? s.waiting ? "正在等待回答…" : "还没有回答" : s.latestText);
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
