// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.pages;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.core.SessionViewer;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.ScrollAnchor;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.Sheets;
import dev.openduo.pocket.ui.markdown.MarkdownView;

import java.text.SimpleDateFormat;
import java.util.*;

/** Narrow-screen session list and thread. A watch exists only while the page is visible. */
public final class SessionsPage implements PocketController.Listener {
    /** Retained across native theme/configuration recreation without retaining an Activity. */
    public static final class State {
        private final Set<String> groups;
        private final Map<String, String> drafts;
        private final String handledNotify;
        private final long generation;

        private State(SessionsPage page) {
            groups = new HashSet<>(page.expandedGroups);
            drafts = new HashMap<>(page.drafts);
            if (!page.inputKey.isEmpty() && page.input != null)
                drafts.put(page.inputKey, page.input.getText().toString());
            handledNotify = page.handledNotify;
            generation = page.generation;
        }
    }

    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root, content, notify, roomReturn;
    private TextView heading, result, notifyTarget, roomTitle;
    private View sendNotify;
    private EditText input;
    private ScrollAnchor anchor;
    private long revision = -1, generation = -1;
    private SessionViewer.Access access;
    private final Set<String> expandedGroups = new HashSet<>();
    private final Map<String, String> drafts = new HashMap<>();
    private String inputKey = "", handledNotify = "";
    private static final int FOLD_AFTER = 5;

    public SessionsPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public State retain() {
        return new State(this);
    }

    public void restore(State saved) {
        if (saved == null
                || controller.snapshot() == null
                || saved.generation != controller.snapshot().sessions.generation) return;
        generation = saved.generation;
        expandedGroups.addAll(saved.groups);
        drafts.putAll(saved.drafts);
        handledNotify = saved.handledNotify;
    }

    public View render() {
        if (root != null) return root;
        dev.openduo.pocket.ui.AdaptiveColumn adaptive = ui.adaptiveColumn();
        root = adaptive;
        LinearLayout bar = ui.row();
        bar.addView(
                ui.iconButton(
                        "chevron_left",
                        ui.t("返回"),
                        ui.palette.secondary,
                        () -> {
                            SessionViewer.Snapshot s = controller.snapshot().sessions;
                            if (!s.openKey.isEmpty()) {
                                controller.sessionList();
                                anchor.toTop();
                            } else host.back();
                        }));
        heading = ui.label(ui.t("会话"), 18, ui.palette.text, true);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                ui.iconButton(
                        "rotate_ccw",
                        ui.t("刷新会话"),
                        ui.palette.secondary,
                        () -> controller.showSessions(true)));
        root.addView(bar);
        adaptive.chrome(bar);
        roomReturn = ui.row();
        ui.inset(roomReturn, 12, 6);
        roomReturn.setMinimumHeight(ui.rowHeight());
        roomReturn.addView(ui.icon("monitor_smartphone", ui.palette.accent, 22));
        LinearLayout roomLabels = ui.column();
        roomTitle = ui.label("", 14, ui.palette.text, true);
        roomLabels.addView(roomTitle);
        ui.add(roomLabels, ui.label(ui.t("这台设备的房间"), 11, ui.palette.subtle, false), 3);
        LinearLayout.LayoutParams roomParams = new LinearLayout.LayoutParams(0, -2, 1);
        roomParams.leftMargin = ui.dp(10);
        roomReturn.addView(roomLabels, roomParams);
        ui.clickable(roomReturn, ui.palette.surface, 12, () -> host.navigate("conversation"));
        root.addView(roomReturn);
        content = ui.column();
        ui.inset(content, ui.pagePaddingDp(), 10);
        ScrollView scroll = ui.scroll(content);
        scroll.setId(dev.openduo.pocket.R.id.page_scroll);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        anchor = new ScrollAnchor(scroll);
        result = ui.label("", 12, ui.palette.attention, false);
        ui.inset(result, 12, 6);
        root.addView(result);
        notify = ui.column();
        ui.inset(notify, 8, 8);
        notifyTarget = ui.label("", 13, ui.palette.text, true);
        notify.addView(notifyTarget);
        ui.add(notify, ui.label(ui.t("多多会在那个会话里处理这条通知"), 11, ui.palette.subtle, false), 4);
        LinearLayout notifyRow = ui.row();
        input = ui.input(ui.t("要告诉它什么"), "", true);
        input.setId(dev.openduo.pocket.R.id.composer);
        input.setSaveEnabled(false);
        input.setMaxLines(3);
        adaptive.editor(input, 3);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after) {}

                    public void onTextChanged(CharSequence text, int start, int before, int count) {
                        if (!inputKey.isEmpty()) drafts.put(inputKey, text.toString());
                        updateSend();
                    }

                    public void afterTextChanged(Editable text) {}
                });
        notifyRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        sendNotify =
                ui.iconButton(
                        "chevron_right",
                        ui.t("发送通知"),
                        ui.palette.accent,
                        () -> controller.notifySession(input.getText().toString()));
        notifyRow.addView(
                sendNotify,
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        ui.add(notify, notifyRow, 6);
        root.addView(ui.scrollFooter(notify));
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
        PocketController.Snapshot room = controller.snapshot();
        SessionViewer.Snapshot s = room.sessions;
        if (s.revision == revision && access == room.sessionsAccess) {
            updateSend();
            return;
        }
        revision = s.revision;
        access = room.sessionsAccess;
        if (s.generation != generation) {
            generation = s.generation;
            drafts.clear();
            expandedGroups.clear();
            handledNotify = "";
            inputKey = "";
            input.setText("");
        }
        if (!s.openKey.equals(inputKey)) {
            if (!inputKey.isEmpty()) drafts.put(inputKey, input.getText().toString());
            inputKey = s.openKey;
            input.setText(drafts.getOrDefault(inputKey, ""));
        }
        if (s.outcome.equals("sent") && !s.notifyId.equals(handledNotify)) {
            if (drafts.getOrDefault(s.notifyKey, "").trim().equals(s.notifyText)) {
                drafts.remove(s.notifyKey);
                if (s.notifyKey.equals(inputKey)) input.setText("");
            }
            handledNotify = s.notifyId;
        }
        anchor.capture(!s.openKey.isEmpty());
        content.removeAllViews();
        roomReturn.setVisibility(s.openKey.isEmpty() ? View.VISIBLE : View.GONE);
        Ui.updateText(roomTitle, room.trying ? ui.t("体验房间") : room.roomName);
        if (!access.canView) {
            content.addView(
                    ui.label(
                            ui.t("当前配对只允许访问房间。在房间管理页用 sessions 或 notify 范围生成新配对链接，才能查看其他会话。"),
                            15,
                            ui.palette.secondary,
                            false));
            ui.add(
                    content,
                    ui.button(
                            ui.t("重新配对"),
                            ui.palette.accent,
                            ui.palette.raised,
                            () -> host.navigate("pairing")),
                    16);
            notify.setVisibility(View.GONE);
            result.setVisibility(View.GONE);
            return;
        }
        if (!s.error.isEmpty())
            content.addView(
                    ui.label(
                            s.error.equals("forbidden")
                                    ? ui.t("当前设备没有查看会话的权限")
                                    : ui.t("暂时无法读取会话，服务器仍在重试"),
                            14,
                            ui.palette.attention,
                            false));
        if (s.openKey.isEmpty()) {
            heading.setText(ui.t("会话"));
            notify.setVisibility(View.GONE);
            if (!s.listed)
                content.addView(ui.label(ui.t("正在读取会话列表…"), 14, ui.palette.subtle, false));
            else if (s.sessions.isEmpty())
                content.addView(ui.label(ui.t("还没有会话"), 14, ui.palette.subtle, false));
            for (String group : new String[] {"chat", "job"}) group(s, group);
        } else {
            SessionViewer.Summary open = s.open();
            heading.setText(open == null ? ui.t("会话") : open.name);
            if (!s.loaded) content.addView(ui.label(ui.t("正在读取会话…"), 14, ui.palette.subtle, false));
            if (s.loaded && s.rows.isEmpty())
                content.addView(ui.label(ui.t("最近没有活动"), 14, ui.palette.subtle, false));
            java.util.List<SessionViewer.Row> tools = new java.util.ArrayList<>();
            for (SessionViewer.Row row : s.rows) {
                if (row.kind.equals("tool")) {
                    tools.add(row);
                    continue;
                }
                flushTools(tools);
                row(s, row);
            }
            flushTools(tools);
            if (open != null && !access.canNotify)
                ui.add(
                        content,
                        ui.label(
                                ui.t("当前配对只能查看会话。使用 notify 范围重新配对后，才能发送通知。"),
                                14,
                                ui.palette.secondary,
                                false),
                        12);
            notify.setVisibility(s.canNotify(access) ? View.VISIBLE : View.GONE);
            if (open != null) Ui.updateText(notifyTarget, ui.format("通知 %s", open.name));
            input.setEnabled(s.canNotify(access) && s.pending.isEmpty());
        }
        updateSend();
    }

    private void updateSend() {
        if (sendNotify == null || controller.snapshot() == null) return;
        PocketController.Snapshot room = controller.snapshot();
        SessionViewer.Snapshot s = room.sessions;
        boolean empty = input.getText().toString().trim().isEmpty();
        result.setText(
                !room.sessionsAccess.canNotify || !s.notifyKey.equals(s.openKey)
                        ? ""
                        : s.pending.isEmpty()
                                ? s.outcome.equals("sent")
                                        ? empty ? ui.t("通知已送达") : ""
                                        : s.outcome.isEmpty() ? "" : notifyFailure(s.outcome)
                                : ui.t("正在发送通知…"));
        result.setVisibility(result.length() == 0 ? View.GONE : View.VISIBLE);
        boolean enabled =
                s.canNotify(room.sessionsAccess)
                        && s.pending.isEmpty()
                        && room.connection == PocketController.Connection.ONLINE
                        && !empty;
        sendNotify.setEnabled(enabled);
        sendNotify.setAlpha(enabled ? 1 : .4f);
    }

    private String notifyFailure(String reason) {
        switch (reason) {
            case "forbidden":
                return ui.t("这台设备没有发通知的权限，需要重新配对");
            case "not_notifiable":
                return ui.t("这个会话不接收通知");
            case "not_found":
                return ui.t("多多那边已经没有这个会话了");
            default:
                return ui.t("通知未确认送达，可检查后重试");
        }
    }

    private void group(SessionViewer.Snapshot state, String group) {
        List<SessionViewer.Summary> items = new ArrayList<>();
        for (SessionViewer.Summary item : state.sessions)
            if (item.group.equals(group)) items.add(item);
        if (items.isEmpty()) return;
        ui.add(
                content,
                ui.label(
                        group.equals("chat") ? ui.t("会话") : ui.t("任务"),
                        12,
                        ui.palette.subtle,
                        true),
                16);
        boolean expanded = expandedGroups.contains(group);
        int count = expanded ? items.size() : Math.min(FOLD_AFTER, items.size());
        for (int i = 0; i < count; i++) {
            SessionViewer.Summary item = items.get(i);
            LinearLayout row = ui.row();
            row.setMinimumHeight(ui.rowHeight());
            ui.inset(row, 12, 10);
            row.addView(
                    ui.icon(
                            group.equals("chat") ? "messages_square" : "clock",
                            ui.palette.accent,
                            20));
            LinearLayout labels = ui.column();
            labels.addView(ui.label(item.name, 14, ui.palette.text, true));
            String detail = detail(item);
            if (!detail.isEmpty())
                ui.add(
                        labels,
                        ui.label(
                                detail,
                                11,
                                item.running() ? ui.palette.accent : ui.palette.subtle,
                                false),
                        4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = ui.dp(10);
            row.addView(labels, lp);
            ui.clickable(
                    row,
                    ui.palette.surface,
                    12,
                    () -> {
                        controller.openSession(item.key);
                        anchor.toBottom();
                    });
            row.setContentDescription(item.name + (detail.isEmpty() ? "" : ", " + detail));
            ui.add(content, row, 6);
        }
        if (items.size() > FOLD_AFTER)
            ui.add(
                    content,
                    ui.button(
                            expanded ? ui.t("收起") : ui.format("还有 %d 个", items.size() - FOLD_AFTER),
                            ui.palette.accent,
                            0,
                            () -> {
                                if (!expandedGroups.add(group)) expandedGroups.remove(group);
                                revision = -1;
                                changed();
                            }),
                    4);
    }

    private String detail(SessionViewer.Summary item) {
        List<String> values = new ArrayList<>();
        if (item.running()) values.add(ui.t("正在运行"));
        String source = item.source();
        switch (source) {
            case "lark":
            case "feishu":
                values.add(ui.t("飞书"));
                break;
            case "ambient":
                values.add(ui.t("房间"));
                break;
            case "job":
            case "":
                break;
            default:
                values.add(source);
        }
        if (item.activity() != Long.MIN_VALUE) {
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            boolean today =
                    java.time.Instant.ofEpochMilli(item.activity())
                            .atZone(zone)
                            .toLocalDate()
                            .equals(java.time.LocalDate.now(zone));
            values.add(
                    new SimpleDateFormat(today ? "HH:mm" : ui.t("M月d日"), ui.words.locale)
                            .format(new Date(item.activity())));
        }
        return String.join(" · ", values);
    }

    private void row(SessionViewer.Snapshot state, SessionViewer.Row row) {
        if (row.kind.equals("gap") || row.kind.equals("system")) {
            TextView text =
                    ui.label(
                            row.kind.equals("gap") ? ui.t("此处有未显示的事件") : row.text,
                            11,
                            ui.palette.subtle,
                            false);
            text.setGravity(Gravity.CENTER);
            ui.inset(text, 8, 6);
            ui.add(content, text, 4);
            return;
        }
        if (row.text.trim().isEmpty()) return;
        boolean mine = row.kind.equals("message"), notice = row.kind.equals("notify");
        LinearLayout box = ui.column();
        ui.inset(box, 12, 10);
        box.setBackground(
                ui.surface(
                        mine
                                ? ui.palette.mine
                                : notice ? ui.palette.attentionTint : ui.palette.raised,
                        12));
        String sender = notice && !row.fromLabel.isEmpty() ? row.fromLabel : state.sender(row);
        String meta =
                notice
                        ? sender.isEmpty() ? ui.t("通知") : ui.format("通知 · 来自「%s」", sender)
                        : mine && !sender.isEmpty() ? ui.format("来自「%s」", sender) : "";
        try {
            if (!mine) {
                String time =
                        new SimpleDateFormat("HH:mm", ui.words.locale)
                                .format(Date.from(java.time.Instant.parse(row.at)));
                meta += (meta.isEmpty() ? "" : " · ") + time;
            }
        } catch (java.time.DateTimeException | ArithmeticException ignored) {
        }
        if (!meta.isEmpty())
            box.addView(
                    ui.label(
                            meta,
                            11,
                            mine
                                    ? ui.palette.mineText
                                    : notice ? ui.palette.attention : ui.palette.subtle,
                            false));
        if (row.kind.equals("reply")) ui.add(box, new MarkdownView(ui, row.text), 4);
        else
            ui.add(
                    box,
                    ui.label(row.text, 15, mine ? ui.palette.mineText : ui.palette.text, false),
                    4);
        box.setOnLongClickListener(
                v -> {
                    ui.copy(ui.t("会话内容"), row.text);
                    return true;
                });
        ui.add(content, box, 8);
    }

    private void flushTools(java.util.List<SessionViewer.Row> tools) {
        if (tools.isEmpty()) return;
        java.util.List<Sheets.Action> actions = new java.util.ArrayList<>();
        for (SessionViewer.Row row : tools)
            actions.add(
                    new Sheets.Action(
                            (row.failed ? ui.t("失败 · ") : "") + row.toolLine(),
                            () -> ui.copy(ui.t("工具步骤"), row.toolLine())));
        boolean failed = tools.stream().anyMatch(row -> row.failed);
        String last = tools.get(tools.size() - 1).toolLine();
        ui.add(
                content,
                ui.button(
                        (failed ? ui.t("失败 · ") : "")
                                + (tools.size() == 1
                                        ? last
                                        : ui.words.steps(tools.size()) + " · " + last),
                        failed ? ui.palette.attention : ui.palette.secondary,
                        ui.palette.surface,
                        () ->
                                Sheets.actions(
                                        ui, ui.t("执行工具"), actions.toArray(new Sheets.Action[0]))),
                8);
        tools.clear();
    }

    public void close() {
        controller.removeListener(this);
        if (anchor != null) anchor.close();
    }
}
