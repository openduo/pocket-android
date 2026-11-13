// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.HoldToTalkView;

/** One ambient session, minimisable into the same conversation. */
public final class AmbientPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root;
    private TextView status, caption, action, mute, detail, hush, camera, flip;
    private LinearLayout cameras;
    private View room, transcript;
    private ConversationPage thread;
    private TextView transcriptToggle;
    private top.kuaner.pockete2.ui.components.SightViewfinder viewfinder;

    public AmbientPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public View render() {
        if (root != null) return root;
        root = ui.column();
        LinearLayout bar = ui.row();
        bar.addView(
                ui.iconButton(
                        "chevron_down",
                        ui.t("最小化环境模式"),
                        ui.palette.secondary,
                        () -> host.navigate("conversation")));
        bar.addView(
                ui.label(ui.t("环境模式"), 17, ui.palette.text, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                ui.iconButton(
                        "settings",
                        ui.t("设置"),
                        ui.palette.secondary,
                        () -> host.navigate("settings")));
        root.addView(bar);
        LinearLayout center = ui.column();
        ui.inset(center, 18, 12);
        center.setGravity(Gravity.CENTER_VERTICAL);
        viewfinder =
                new top.kuaner.pockete2.ui.components.SightViewfinder(
                        ui, controller::sightPreview, controller::sightPreviewVisible);
        viewfinder.setVisibility(View.GONE);
        ui.add(center, viewfinder, 0);
        viewfinder.setLayoutParams(new LinearLayout.LayoutParams(-1, ui.dp(180)));
        center.addView(
                new HoldToTalkView.Meter(ui, controller),
                new LinearLayout.LayoutParams(-1, ui.dp(70)));
        status = ui.label(ui.t("环境模式已关闭"), 24, ui.palette.text, true);
        status.setGravity(Gravity.CENTER);
        ui.add(center, status, 12);
        detail = ui.label("", 13, ui.palette.secondary, false);
        detail.setGravity(Gravity.CENTER);
        ui.add(center, detail, 10);
        caption = ui.label("", 16, ui.palette.text, false);
        caption.setLineSpacing(ui.dp(3), 1);
        ui.inset(caption, 12, 12);
        caption.setBackground(ui.surface(ui.palette.raised, 14));
        ui.add(center, caption, 20);
        room = ui.scroll(center);
        root.addView(room, new LinearLayout.LayoutParams(-1, 0, 1));
        thread = new ConversationPage(host, controller, true);
        transcript = thread.render();
        root.addView(transcript, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = ui.row();
        ui.inset(controls, 12, 6);
        mute = ui.button(ui.t("静音"), ui.palette.text, ui.palette.raised, controller::ambientMute);
        hush = ui.button(ui.t("别说了"), ui.palette.text, ui.palette.raised, controller::ambientHush);
        controls.addView(mute, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
        hp.leftMargin = ui.dp(8);
        controls.addView(hush, hp);
        transcriptToggle =
                ui.button(
                        ui.t("对话"),
                        ui.palette.accent,
                        ui.palette.raised,
                        () -> {
                            controller.settings.ambientTranscript(
                                    !controller.settings.ambientTranscript());
                            changed();
                        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
        tp.leftMargin = ui.dp(8);
        controls.addView(transcriptToggle, tp);
        root.addView(controls);
        cameras = ui.row();
        ui.inset(cameras, 12, 4);
        camera =
                ui.button(
                        ui.t("开启相机"),
                        ui.palette.accent,
                        ui.palette.raised,
                        () -> {
                            PocketController.Snapshot s = controller.snapshot();
                            if (s != null && s.cameraRequested && !s.cameraRetry)
                                controller.toggleSight();
                            else
                                host.permission(
                                        new String[] {android.Manifest.permission.CAMERA},
                                        controller::toggleSight);
                        });
        cameras.addView(camera, new LinearLayout.LayoutParams(0, -2, 1));
        flip = ui.button(ui.t("切换镜头"), ui.palette.secondary, 0, controller::flipSight);
        cameras.addView(flip, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(cameras);
        action =
                ui.button(
                        ui.t("打开环境模式"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> {
                            PocketController.Snapshot s = controller.snapshot();
                            if (s != null
                                    && (s.ambient == PocketController.Ambient.SEAT_TAKEN
                                            || s.ambient == PocketController.Ambient.DEAF
                                            || s.ambient == PocketController.Ambient.PAUSED))
                                controller.takeover();
                            else
                                host.permission(
                                        new String[] {android.Manifest.permission.RECORD_AUDIO},
                                        controller::ambientToggle);
                        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, ui.dp(50));
        ap.setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12));
        root.addView(action, ap);
        controller.addListener(this);
        changed();
        return root;
    }

    @Override
    public void changed() {
        if (root == null) return;
        PocketController.Snapshot s = controller.snapshot();
        if (s == null) return;
        boolean on = s.ambientActive;
        boolean expanded = controller.settings.ambientTranscript();
        transcript.setVisibility(expanded ? View.VISIBLE : View.GONE);
        room.setVisibility(expanded ? View.GONE : View.VISIBLE);
        viewfinder.setVisibility(
                !expanded && s.cameraOn && !s.cameraFront ? View.VISIBLE : View.GONE);
        Ui.updateText(transcriptToggle, expanded ? ui.t("收起") : ui.t("对话"));
        transcriptToggle.setContentDescription(expanded ? ui.t("隐藏对话") : ui.t("展开完整对话"));
        String phase =
                s.ambient == PocketController.Ambient.OFF
                        ? ui.t("环境模式已关闭")
                        : s.ambient == PocketController.Ambient.SEAT_TAKEN
                                ? ui.t("另一台设备在听")
                                : s.ambient == PocketController.Ambient.DEAF
                                        ? ui.t("听不到了")
                                        : s.ambient == PocketController.Ambient.PAUSED
                                                ? ui.t("收音已暂停")
                                                : s.ambient == PocketController.Ambient.STARTING
                                                        ? ui.t("正在打开麦克风…")
                                                        : s.ambient
                                                                        == PocketController.Ambient
                                                                                .OFFLINE
                                                                ? ui.t("连不上，正在重试")
                                                                : s.ambient
                                                                                == PocketController
                                                                                        .Ambient
                                                                                        .FAILED
                                                                        ? ui.t("收音失败")
                                                                        : s.ambient
                                                                                        == PocketController
                                                                                                .Ambient
                                                                                                .SPEAKING
                                                                                ? ui.t("正在说")
                                                                                : s.working != null
                                                                                        ? ui.t(
                                                                                                s
                                                                                                        .working
                                                                                                        .label())
                                                                                        : s.ambientMuted
                                                                                                ? ui
                                                                                                        .t(
                                                                                                                "在听 · 静音")
                                                                                                : ui
                                                                                                        .t(
                                                                                                                "在听");
        cameras.setVisibility(s.cameraOffered ? View.VISIBLE : View.GONE);
        flip.setVisibility(s.cameraCanFlip && s.cameraRequested ? View.VISIBLE : View.GONE);
        Ui.updateText(
                camera,
                s.cameraRetry ? ui.t("重试相机") : s.cameraRequested ? ui.t("关闭相机") : ui.t("开启相机"));
        camera.setContentDescription(camera.getText() + " · " + ui.explain(s.sight));
        Ui.updateText(status, phase);
        Ui.updateText(
                action,
                s.ambient == PocketController.Ambient.SEAT_TAKEN
                        ? ui.t("在这台 E2 上听")
                        : s.ambient == PocketController.Ambient.DEAF
                                        || s.ambient == PocketController.Ambient.PAUSED
                                ? ui.t("重新打开")
                                : on ? ui.t("关闭环境模式") : ui.t("打开环境模式"));
        Ui.updateText(
                detail,
                !s.lastError.isEmpty()
                        ? ui.explain(s.lastError)
                        : s.ambientMuted
                                ? ui.t("还在听，回复只显示在屏幕上")
                                : on ? ui.t("说话就能打断它 · 返回对话仍会继续听") : ui.t("打开后会持续收音，回答会在 E2 上播放"));
        String latest = s.working == null ? "" : s.working.text;
        if (latest.isEmpty())
            for (int i = s.rows.size() - 1; i >= 0; i--)
                if (s.rows.get(i).kind == Message.DUODUO) {
                    latest = s.rows.get(i).text;
                    break;
                }
        if (s.cameraOffered) Ui.updateText(detail, ui.explain(s.sight));
        else if (!s.lastHeard.isEmpty() && s.working == null)
            Ui.updateText(detail, ui.t("听到 · ") + s.lastHeard);
        if (s.pressMuted) Ui.updateText(detail, ui.t("按键语音进行中，房间收音暂时静音"));
        else if (s.heatLevel > 0) Ui.updateText(detail, ui.t("设备温度较高，视频已降低功耗；过热时相机会暂停"));
        Ui.updateText(caption, latest);
        caption.setVisibility(latest.isEmpty() ? View.GONE : View.VISIBLE);
        mute.setEnabled(on);
        hush.setEnabled(s.ambient == PocketController.Ambient.SPEAKING);
        Ui.updateText(mute, s.ambientMuted ? ui.t("取消静音") : ui.t("静音"));
    }

    public void close() {
        if (thread != null) thread.close();
        controller.removeListener(this);
    }
}
