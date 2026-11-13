// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.Settings;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.Sheets;

import java.util.function.Consumer;

/** Preferences have real storage and side effects. Live status updates retain scroll position. */
public final class SettingsPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root;
    private TextView connection,
            tailnet,
            cache,
            notificationStatus,
            arrivalStatus,
            audioRoute,
            configIssues;
    private TextView exitTrial;
    private Switch arrivalControl;

    public SettingsPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public View render() {
        if (root != null) return root;
        root = ui.column();
        TextView heading = ui.label("设置", 22, ui.palette.text, true);
        ui.inset(heading, 16, 14);
        root.addView(heading);
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(24));
        root.addView(ui.scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
        Settings pref = controller.settings;
        section(body, "连接");
        connection = row(body, "link", "房间与主机", "尚未配对", () -> host.navigate("pairing"));
        tailnet = row(body, "wifi", "Tailscale", "", host::loginTailnet);
        row(body, "rotate_ccw", "重新连接", "同步历史和实时消息", controller::start);
        row(
                body,
                "cloud_off",
                "退出 Tailscale",
                "退出后需要重新登录网络",
                () ->
                        Sheets.confirm(
                                ui,
                                "退出 Tailscale",
                                "当前房间会断开连接，配对凭据仍保存在本机。",
                                "退出登录",
                                controller::logoutTailnet));
        section(body, "声音与屏幕");
        audioRoute = row(body, "info", "音频路径", "", null);
        toggle(
                body,
                "使用耳机麦克风",
                "连接耳机时改用耳机收音；蓝牙耳机会切到通话音质",
                pref.headsetMic(),
                value -> {
                    if (value && android.os.Build.VERSION.SDK_INT >= 31)
                        host.permission(
                                new String[] {android.Manifest.permission.BLUETOOTH_CONNECT},
                                () -> controller.setHeadsetMic(true));
                    else controller.setHeadsetMic(value);
                });
        toggle(body, "播放语音回复", "环境模式关闭时，在前台播放回答", pref.ambientOutput(), controller::setReplyAudio);
        toggle(
                body,
                "离开 App 后继续听",
                "环境模式以常驻通知保持收音",
                pref.keepListening(),
                value -> pref.keepListening(value));
        toggle(
                body,
                "保持屏幕常亮",
                "仅在 Pocket E2 位于前台时生效",
                pref.keepAwake(),
                value -> {
                    controller.setKeepAwake(value);
                    if (value)
                        host.activity()
                                .getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON);
                    else
                        host.activity()
                                .getWindow()
                                .clearFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON);
                });
        toggle(body, "触感反馈", "麦克风就绪、取消和发送时轻震", pref.haptics(), controller::setHaptics);
        section(body, "后台回答");
        toggle(
                body,
                "显示回答通知",
                "离开 App 后收到回答时提醒",
                pref.answerNotifications(),
                value -> {
                    pref.answerNotifications(value);
                    if (value && android.os.Build.VERSION.SDK_INT >= 33)
                        host.permission(
                                new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                                this::changed);
                    changed();
                });
        notificationStatus =
                row(
                        body,
                        "info",
                        "通知与后台同步",
                        "",
                        () -> {
                            if (android.os.Build.VERSION.SDK_INT >= 33
                                    && host.activity()
                                                    .checkSelfPermission(
                                                            android.Manifest.permission
                                                                    .POST_NOTIFICATIONS)
                                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                host.permission(
                                        new String[] {
                                            android.Manifest.permission.POST_NOTIFICATIONS
                                        },
                                        this::changed);
                            else
                                host.activity()
                                        .startActivity(
                                                new android.content.Intent(
                                                                android.provider.Settings
                                                                        .ACTION_APP_NOTIFICATION_SETTINGS)
                                                        .putExtra(
                                                                android.provider.Settings
                                                                        .EXTRA_APP_PACKAGE,
                                                                host.activity().getPackageName()));
                        });
        section(body, "位置");
        arrivalControl =
                toggle(
                        body,
                        "让房间知道我到了哪",
                        "只随下一条消息发送地名，不发送坐标",
                        controller.arrivalsEnabled(),
                        value -> {
                            controller.arrivalsEnabled(value);
                            if (value)
                                host.permission(
                                        new String[] {
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                            android.Manifest.permission.ACCESS_FINE_LOCATION
                                        },
                                        () -> {
                                            if (android.os.Build.VERSION.SDK_INT >= 29
                                                    && controller.arrivalsNeedPermission())
                                                Sheets.actions(
                                                        ui,
                                                        "需要始终允许定位",
                                                        new Sheets.Action(
                                                                "打开应用权限设置",
                                                                () ->
                                                                        host.activity()
                                                                                .startActivity(
                                                                                        new android
                                                                                                .content
                                                                                                .Intent(
                                                                                                android
                                                                                                        .provider
                                                                                                        .Settings
                                                                                                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                                                android
                                                                                                        .net
                                                                                                        .Uri
                                                                                                        .parse(
                                                                                                                "package:"
                                                                                                                        + host.activity()
                                                                                                                                .getPackageName())))));
                                            else controller.arrivalsEnabled(true);
                                        });
                        });
        arrivalStatus = row(body, "info", "到达检测方式", "", null);
        section(body, "配件与会话");
        row(body, "bluetooth", "Passport", "按键语音配件", () -> host.navigate("passport"));
        row(
                body,
                "messages_square",
                "会话查看器",
                "连接 sessions 或 notify 范围的配对",
                () -> host.navigate("sessions"));
        section(body, "外观");
        row(
                body,
                "palette",
                "主题",
                pref.appearance().equals("light")
                        ? "浅色"
                        : pref.appearance().equals("system") ? "跟随系统" : "暖黑",
                () ->
                        Sheets.actions(
                                ui,
                                "主题",
                                new Sheets.Action("暖黑", () -> appearance("dark")),
                                new Sheets.Action("浅色", () -> appearance("light")),
                                new Sheets.Action("跟随系统", () -> appearance("system"))));
        section(body, "诊断");
        toggle(body, "记录网络日志", "排查连接问题时开启", pref.networkLog(), controller::networkLogging);
        cache = row(body, "hard_drive", "本机缓存", "", null);
        configIssues = row(body, "info", "频道配置提示", "", null);
        row(body, "share_2", "导出诊断", "版本、连接状态与运行日志", () -> host.navigate("diagnostics"));
        row(body, "info", "关于 Pocket E2", "Android 原生迁移 · 0.1.0", this::about);
        row(
                body,
                "trash_2",
                "忘记房间",
                "移除配对、缓存和待发内容",
                () ->
                        Sheets.confirm(
                                ui,
                                "忘记这个房间",
                                "本机的设备凭据、离线记录和待发送内容会被删除。服务器上的对话仍然保留。",
                                "忘记房间",
                                () -> {
                                    controller.forgetPairing();
                                    host.navigate("onboarding");
                                }));
        exitTrial =
                ui.button(
                        "退出体验",
                        ui.palette.secondary,
                        ui.palette.raised,
                        () -> {
                            controller.stopTrying();
                            host.navigate(controller.configured() ? "conversation" : "onboarding");
                        });
        ui.add(body, exitTrial, 16);
        controller.addListener(this);
        changed();
        return root;
    }

    private void appearance(String value) {
        controller.settings.appearance(value);
        host.activity().recreate();
    }

    private void section(LinearLayout parent, String title) {
        TextView text = ui.label(title, 12, ui.palette.subtle, true);
        text.setPadding(ui.dp(4), ui.dp(20), 0, ui.dp(8));
        parent.addView(text);
    }

    private TextView row(
            LinearLayout parent, String icon, String title, String detail, Runnable action) {
        LinearLayout row = ui.row();
        ui.inset(row, 12, 10);
        row.setMinimumHeight(ui.dp(56));
        row.addView(ui.icon(icon, ui.palette.secondary, 20));
        LinearLayout labels = ui.column();
        TextView name = ui.label(title, 14, ui.palette.text, false);
        labels.addView(name);
        TextView value = ui.label(detail, 11, ui.palette.subtle, false);
        ui.add(labels, value, 5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = ui.dp(10);
        row.addView(labels, lp);
        if (action != null) {
            row.addView(ui.icon("chevron_right", ui.palette.subtle, 16));
            ui.clickable(row, ui.palette.surface, 12, action);
        } else row.setBackground(ui.surface(ui.palette.surface, 12));
        ui.add(parent, row, 3);
        return value;
    }

    private Switch toggle(
            LinearLayout parent,
            String title,
            String detail,
            boolean value,
            Consumer<Boolean> action) {
        LinearLayout row = ui.row();
        ui.inset(row, 12, 10);
        row.setBackground(ui.surface(ui.palette.surface, 12));
        LinearLayout labels = ui.column();
        labels.addView(ui.label(title, 14, ui.palette.text, false));
        ui.add(labels, ui.label(detail, 11, ui.palette.subtle, false), 5);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(ui.context);
        control.setChecked(value);
        control.setContentDescription(title);
        control.setMinimumHeight(ui.dp(48));
        control.setOnCheckedChangeListener((b, checked) -> action.accept(checked));
        row.addView(control);
        row.setOnClickListener(
                v -> {
                    if (control.isEnabled()) control.toggle();
                });
        ui.add(parent, row, 3);
        return control;
    }

    private void about() {
        Sheets.actions(
                ui,
                "Pocket E2 0.1.0",
                new Sheets.Action(
                        "源项目与迁移说明",
                        () ->
                                ui.shareText(
                                        "Pocket E2 is an Android port of"
                                            + " https://github.com/openduo/pocket-ios\n"
                                            + "Code license: FSL-1.1-Apache-2.0. Neutral branding;"
                                            + " E2Studio design reference.")),
                new Sheets.Action(
                        "开源许可",
                        () -> {
                            try (java.io.InputStream in =
                                    ui.context
                                            .getAssets()
                                            .open("licenses/THIRD_PARTY_NOTICES.md")) {
                                java.io.ByteArrayOutputStream out =
                                        new java.io.ByteArrayOutputStream();
                                byte[] buffer = new byte[4096];
                                int n;
                                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                                Sheets.actions(
                                        ui,
                                        "开源许可",
                                        new Sheets.Action(
                                                new String(
                                                        out.toByteArray(),
                                                        java.nio.charset.StandardCharsets.UTF_8),
                                                () -> {}));
                            } catch (java.io.IOException failure) {
                                host.toast("许可文件读取失败");
                            }
                        }));
    }

    @Override
    public void changed() {
        if (root == null) return;
        PocketController.Snapshot s = controller.snapshot();
        if (s == null) return;
        exitTrial.setVisibility(s.trying ? View.VISIBLE : View.GONE);
        arrivalControl.setEnabled(!s.trying);
        Ui.updateText(
                connection,
                controller.configured() ? s.roomName + " · " + controller.settings.host() : "尚未配对");
        Ui.updateText(tailnet, s.tailnetState + " · pocket-e2");
        Ui.updateText(cache, s.rows.size() + " 条可见记录 · " + s.outbox.size() + " 条待同步");
        Ui.updateText(
                audioRoute,
                s.audioRoute
                        + (s.ambientActive ? s.echoCancellation ? " · 回声消除已开" : " · 回声消除未开" : ""));
        Ui.updateText(
                configIssues,
                s.configIssues.isEmpty() ? "当前没有配置提示" : String.join("\n", s.configIssues));
        boolean allowed =
                host.activity()
                        .getSystemService(android.app.NotificationManager.class)
                        .areNotificationsEnabled();
        Ui.updateText(
                notificationStatus,
                !allowed ? "系统通知未开启，点按设置。" : "实时连接可用时即时提醒；离线时约每 15 分钟补取，系统省电可能延迟。");
        Ui.updateText(
                arrivalStatus,
                controller.arrivalsEnabled() && controller.arrivalsNeedPermission()
                        ? "等待定位权限。需要精确位置；Android 10 及以上还需在系统设置中始终允许。"
                        : "Android 使用低频网络与被动定位，停留后记录地名；省电策略可能延迟。");
    }

    public void close() {
        controller.removeListener(this);
    }
}
