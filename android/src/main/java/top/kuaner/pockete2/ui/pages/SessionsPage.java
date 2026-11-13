// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.SessionViewer;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.ScrollAnchor;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.Sheets;
import top.kuaner.pockete2.ui.markdown.MarkdownView;

/** Narrow-screen session list and thread. A watch exists only while the page is visible. */
public final class SessionsPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root, content, notify;
    private TextView heading, result;
    private EditText input;
    private ScrollAnchor anchor;
    private long revision = -1;

    public SessionsPage(PageHost host, PocketController controller) {
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
                        "chevron_left",
                        "返回",
                        ui.palette.secondary,
                        () -> {
                            SessionViewer.Snapshot s = controller.snapshot().sessions;
                            if (!s.openKey.isEmpty()) controller.sessionList();
                            else host.back();
                        }));
        heading = ui.label("会话", 18, ui.palette.text, true);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                ui.iconButton(
                        "rotate_ccw",
                        "刷新会话",
                        ui.palette.secondary,
                        () -> controller.showSessions(true)));
        root.addView(bar);
        content = ui.column();
        ui.inset(content, 12, 10);
        ScrollView scroll = ui.scroll(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        anchor = new ScrollAnchor(scroll);
        result = ui.label("", 12, ui.palette.attention, false);
        ui.inset(result, 12, 6);
        root.addView(result);
        notify = ui.row();
        ui.inset(notify, 8, 8);
        input = ui.input("通知这个会话…", "", true);
        notify.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        notify.addView(
                ui.iconButton(
                        "chevron_right",
                        "发送通知",
                        ui.palette.accent,
                        () -> controller.notifySession(input.getText().toString())),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(notify);
        controller.addListener(this);
        changed();
        return root;
    }

    public void visible(boolean value) {
        controller.showSessions(value);
    }

    @Override
    public void changed() {
        if (root == null || controller.snapshot() == null) return;
        SessionViewer.Snapshot s = controller.snapshot().sessions;
        if (s.revision == revision) return;
        revision = s.revision;
        anchor.capture(true);
        content.removeAllViews();
        boolean permitted =
                controller.snapshot().trying
                        || controller.settings.scope().matches("sessions|notify");
        if (!permitted) {
            content.addView(
                    ui.label(
                            "当前配对只允许访问房间。在房间管理页用 sessions 或 notify 范围生成新配对链接，才能查看其他会话。",
                            15,
                            ui.palette.secondary,
                            false));
            ui.add(
                    content,
                    ui.button(
                            "重新配对",
                            ui.palette.accent,
                            ui.palette.raised,
                            () -> host.navigate("pairing")),
                    16);
            notify.setVisibility(View.GONE);
            return;
        }
        if (!s.error.isEmpty())
            content.addView(
                    ui.label(
                            s.error.equals("forbidden") ? "当前设备没有查看会话的权限" : "暂时无法读取会话，服务器仍在重试",
                            14,
                            ui.palette.attention,
                            false));
        if (s.openKey.isEmpty()) {
            heading.setText("会话");
            notify.setVisibility(View.GONE);
            if (!s.listed) content.addView(ui.label("正在读取会话列表…", 14, ui.palette.subtle, false));
            else if (s.sessions.isEmpty())
                content.addView(ui.label("还没有会话", 14, ui.palette.subtle, false));
            for (String group : new String[] {"chat", "job"}) {
                boolean header = false;
                for (SessionViewer.Summary item : s.sessions)
                    if (item.group.equals(group)) {
                        if (!header) {
                            ui.add(
                                    content,
                                    ui.label(
                                            group.equals("chat") ? "对话" : "任务",
                                            12,
                                            ui.palette.subtle,
                                            true),
                                    16);
                            header = true;
                        }
                        TextView row =
                                ui.button(
                                        item.name
                                                + "\n"
                                                + (item.running() ? "正在运行" : item.status)
                                                + (item.tools.isEmpty()
                                                        ? ""
                                                        : " · "
                                                                + item.tools.get(
                                                                        item.tools.size() - 1)),
                                        item.running() ? ui.palette.accent : ui.palette.text,
                                        ui.palette.surface,
                                        () -> {
                                            controller.openSession(item.key);
                                            anchor.toBottom();
                                        });
                        row.setGravity(
                                android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
                        ui.add(content, row, 6);
                    }
            }
        } else {
            SessionViewer.Summary open = s.open();
            heading.setText(open == null ? "会话" : open.name);
            if (!s.loaded) content.addView(ui.label("正在读取会话…", 14, ui.palette.subtle, false));
            java.util.List<SessionViewer.Row> tools = new java.util.ArrayList<>();
            for (SessionViewer.Row row : s.rows) {
                if (row.kind.equals("tool")) {
                    tools.add(row);
                    continue;
                }
                flushTools(tools);
                LinearLayout box = ui.column();
                ui.inset(box, 12, 10);
                box.setBackground(
                        ui.surface(
                                row.kind.equals("message") ? ui.palette.mine : ui.palette.raised,
                                12));
                String meta =
                        row.kind.equals("notify")
                                ? "来自 " + (row.fromLabel.isEmpty() ? row.from : row.fromLabel)
                                : row.kind.equals("system")
                                        ? "系统"
                                        : row.kind.equals("gap") ? "记录间隔" : "";
                if (!meta.isEmpty()) box.addView(ui.label(meta, 11, ui.palette.subtle, false));
                if (row.kind.equals("reply")) ui.add(box, new MarkdownView(ui, row.text), 4);
                else
                    ui.add(
                            box,
                            ui.label(
                                    row.text,
                                    15,
                                    row.failed ? ui.palette.attention : ui.palette.text,
                                    false),
                            4);
                box.setOnLongClickListener(
                        v -> {
                            ui.copy("会话内容", row.text);
                            return true;
                        });
                ui.add(content, box, 8);
            }
            flushTools(tools);
            notify.setVisibility(open != null && open.notify ? View.VISIBLE : View.GONE);
            notify.setEnabled(s.pending.isEmpty());
        }
        result.setText(
                s.pending.isEmpty()
                        ? s.outcome.equals("sent")
                                ? "通知已送达"
                                : s.outcome.isEmpty()
                                        ? ""
                                        : s.outcome.equals("forbidden")
                                                ? "当前设备没有发送通知的权限"
                                                : "通知未确认送达，可检查后重试"
                        : "正在发送通知…");
        if (s.outcome.equals("sent")) input.setText("");
    }

    private void flushTools(java.util.List<SessionViewer.Row> tools) {
        if (tools.isEmpty()) return;
        java.util.List<Sheets.Action> actions = new java.util.ArrayList<>();
        for (SessionViewer.Row row : tools)
            actions.add(
                    new Sheets.Action(
                            (row.failed ? "失败 · " : "") + row.toolLine(),
                            () -> ui.copy("工具步骤", row.toolLine())));
        ui.add(
                content,
                ui.button(
                        "执行工具 · " + tools.size() + " 步",
                        ui.palette.secondary,
                        ui.palette.surface,
                        () -> Sheets.actions(ui, "执行工具", actions.toArray(new Sheets.Action[0]))),
                8);
        tools.clear();
    }

    public void close() {
        controller.removeListener(this);
        if (anchor != null) anchor.close();
    }
}
