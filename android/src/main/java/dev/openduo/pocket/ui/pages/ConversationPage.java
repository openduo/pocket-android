// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.pages;

import android.text.TextUtils;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.Message;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.core.TurnState;
import dev.openduo.pocket.core.UpgradeNotice;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.ReadableColumn;
import dev.openduo.pocket.ui.ScrollAnchor;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.AttachmentImages;
import dev.openduo.pocket.ui.components.HoldToTalkView;
import dev.openduo.pocket.ui.components.Sheets;
import dev.openduo.pocket.ui.markdown.MarkdownView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable keyed rows and a persistent composer; state updates never replace the page. */
public final class ConversationPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final AttachmentImages images;
    private final boolean readOnly;
    private final Map<String, View> keyed = new LinkedHashMap<>();
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    private final java.util.Set<String> expandedHeard = new java.util.HashSet<>();
    private LinearLayout root, feed;
    private View page;
    private TextView sideRoom, sideStatus, sideHeard;
    private TextView title, subtitle, upgradeNotice, banner, older, working, jump;
    private ScrollView scroll;
    private ScrollAnchor anchor;
    private ConversationComposer editor;
    private String previousLatest = "", lastReading = "";
    private long revision = -1;

    public ConversationPage(PageHost host, PocketController controller) {
        this(host, controller, false);
    }

    public ConversationPage(PageHost host, PocketController controller, boolean readOnly) {
        this.host = host;
        this.controller = controller;
        this.readOnly = readOnly;
        ui = host.ui();
        images = new AttachmentImages(controller);
    }

    public View render() {
        if (root != null) return page;
        dev.openduo.pocket.ui.AdaptiveColumn adaptive = ui.adaptiveColumn();
        root = adaptive;
        root.setBackgroundColor(ui.palette.background);
        LinearLayout chrome = ui.column();
        adaptive.chrome(chrome);
        root.addView(chrome);
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(ui.metrics.compactNavigation() ? 0 : 8), 0, ui.dp(4), 0);
        if (!readOnly && ui.metrics.compactNavigation()) {
            View menu =
                    ui.iconButton(
                            "ellipsis",
                            ui.t("切换页面"),
                            ui.palette.secondary,
                            () ->
                                    Sheets.actions(
                                            ui,
                                            ui.t("切换页面"),
                                            new Sheets.Action(
                                                    ui.t("环境模式"), () -> host.navigate("ambient")),
                                            new Sheets.Action(
                                                    ui.t("会话查看器"), () -> host.navigate("sessions")),
                                            new Sheets.Action(
                                                    ui.t("设置"), () -> host.navigate("settings"))));
            menu.setId(dev.openduo.pocket.R.id.compact_page_menu);
            menu.setNextFocusRightId(dev.openduo.pocket.R.id.compact_ambient_button);
            header.addView(
                    menu, new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        }
        LinearLayout heading = ui.column();
        title = ui.label(ui.t("多多"), 18, ui.palette.text, true);
        if (ui.metrics.compactNavigation()) title.setTextSize(16);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = ui.label(ui.t("加载中…"), 11, ui.palette.secondary, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title);
        ui.add(heading, subtitle, 4);
        heading.setMinimumHeight(
                ui.metrics.compactNavigation() ? ui.touchTarget() : ui.rowHeight());
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        View ambientButton =
                ui.iconButton(
                        "audio_lines",
                        ui.t("环境模式"),
                        ui.palette.accent,
                        () -> host.navigate("ambient"));
        if (!readOnly && ui.metrics.compactNavigation()) {
            ambientButton.setId(dev.openduo.pocket.R.id.compact_ambient_button);
            ambientButton.setNextFocusLeftId(dev.openduo.pocket.R.id.compact_page_menu);
        }
        header.addView(
                ambientButton,
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        chrome.addView(header);
        if (readOnly || ui.metrics.twoPaneWorkspace()) header.setVisibility(View.GONE);
        upgradeNotice = ui.label("", 12, ui.palette.attention, true);
        upgradeNotice.setTag("upgrade-notice");
        upgradeNotice.setMinHeight(ui.touchTarget());
        upgradeNotice.setGravity(Gravity.CENTER_VERTICAL);
        ui.inset(upgradeNotice, ui.pagePaddingDp(), 8);
        ui.clickable(upgradeNotice, ui.palette.attentionTint, 10, this::showUpgrade);
        upgradeNotice.setVisibility(View.GONE);
        chrome.addView(upgradeNotice);
        banner = ui.label("", 12, ui.palette.attention, false);
        ui.inset(banner, ui.pagePaddingDp(), 8);
        banner.setBackground(ui.focusSurface(ui.palette.attentionTint, 10));
        banner.setFocusable(true);
        banner.setOnClickListener(
                v -> {
                    PocketController.Snapshot s = controller.snapshot();
                    if (s != null && s.connection == PocketController.Connection.NEEDS_PAIRING)
                        host.navigate("pairing");
                    else if (s != null && s.tailnetState.startsWith("Needs")) host.loginTailnet();
                    else {
                        controller.clearNotice();
                        controller.start();
                    }
                });
        chrome.addView(banner);
        FrameLayout thread = new FrameLayout(ui.context);
        feed = ui.column();
        feed.setPadding(ui.pagePadding(), ui.dp(8), ui.pagePadding(), ui.dp(16));
        feed.setFocusableInTouchMode(true);
        feed.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scroll = ui.scroll(feed);
        scroll.setId(dev.openduo.pocket.R.id.page_scroll);
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        thread.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        jump =
                ui.button(
                        ui.t("回到最新"),
                        ui.palette.accent,
                        ui.palette.raised,
                        () -> {
                            anchor.toBottom();
                            feed.requestLayout();
                        });
        FrameLayout.LayoutParams jp =
                new FrameLayout.LayoutParams(
                        -2, ui.touchTarget(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        jp.bottomMargin = ui.dp(8);
        thread.addView(jump, jp);
        jump.setVisibility(View.GONE);
        scroll.setOnScrollChangeListener(
                (v, x, y, ox, oy) -> {
                    images.refreshVisible();
                    jump.setVisibility(scroll.canScrollVertically(1) ? View.VISIBLE : View.GONE);
                });
        root.addView(thread, new LinearLayout.LayoutParams(-1, 0, 1));
        anchor = new ScrollAnchor(scroll);
        older = ui.button(ui.t("查看更早的记录"), ui.palette.subtle, 0, controller::loadOlder);
        keyed.put("older", older);
        working = ui.label("", 14, ui.palette.secondary, false);
        ui.inset(working, 12, 12);
        working.setBackground(ui.surface(ui.palette.raised, 12));
        root.getViewTreeObserver().addOnGlobalLayoutListener(images::refreshVisible);
        if (readOnly) {
            page = root;
            controller.addListener(this);
            changed();
            anchor.toBottom();
            return page;
        }
        editor = new ConversationComposer(host, controller, anchor::toBottom);
        editor.render(adaptive);
        page = ui.metrics.twoPaneWorkspace() ? workspace() : root;
        controller.addListener(this);
        changed();
        anchor.toBottom();
        return page;
    }

    private View workspace() {
        LinearLayout layout = ui.row();
        layout.setPadding(ui.pagePadding() / 2, 0, ui.pagePadding() / 2, 0);
        LinearLayout sidebar = ui.column();
        sidebar.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(16));
        sidebar.setBackground(ui.surface(ui.palette.surface, 16));
        ui.add(sidebar, ui.label(ui.t("房间"), 12, ui.palette.subtle, true), 2);
        sideRoom = ui.label("", 21, ui.palette.text, true);
        ui.add(sidebar, sideRoom, 8);
        sideStatus = ui.label("", 13, ui.palette.secondary, false);
        ui.add(sidebar, sideStatus, 6);
        sideHeard = ui.label("", 15, ui.palette.text, false);
        sideHeard.setBackground(ui.surface(ui.palette.raised, 12));
        sideHeard.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        ui.add(sidebar, sideHeard, 28);
        ui.add(
                sidebar,
                ui.button(
                        ui.t("会话查看器"),
                        ui.palette.text,
                        ui.palette.raised,
                        () -> host.navigate("sessions")),
                8);
        ui.add(
                sidebar,
                ui.button(
                        ui.t("打开环境模式"),
                        ui.palette.accent,
                        ui.palette.raised,
                        () -> host.navigate("ambient")),
                8);
        layout.addView(
                sidebar, new LinearLayout.LayoutParams(ui.dp(ui.metrics.sidebarWidthDp()), -1));
        LinearLayout.LayoutParams main = new LinearLayout.LayoutParams(0, -1, 1);
        main.leftMargin = ui.dp(14);
        layout.addView(root, main);
        return layout;
    }

    @Override
    public void changed() {
        if (root == null) return;
        PocketController.Snapshot s = controller.snapshot();
        if (s == null) return;
        if (sideRoom != null) {
            Ui.updateText(sideRoom, s.trying ? ui.t(s.roomName) : s.roomName);
            Ui.updateText(sideStatus, ui.explain(s.status));
            Ui.updateText(sideHeard, s.lastHeard);
            sideHeard.setVisibility(s.lastHeard.isEmpty() ? View.GONE : View.VISIBLE);
        }
        Ui.updateText(
                title,
                s.roomName.isEmpty() ? ui.t("多多") : s.trying ? ui.t(s.roomName) : s.roomName);
        Ui.updateText(subtitle, ui.explain(s.status));
        subtitle.setVisibility(View.VISIBLE);
        Ui.updateText(upgradeNotice, upgradeTitle(s.upgrade));
        upgradeNotice.setVisibility(s.upgrade == UpgradeNotice.NONE ? View.GONE : View.VISIBLE);
        String notice =
                !s.lastError.isEmpty()
                        ? ui.explain(s.lastError)
                        : s.trying
                                ? readOnly ? ui.t("体验房间 · 回答为预设内容") : ""
                                : !s.daemonAvailable ? ui.t("暂时无法回复，消息会先记下") : "";
        Ui.updateText(banner, notice);
        banner.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        if (revision != s.revision || s.trying || !lastReading.equals(s.readingId)) {
            revision = s.revision;
            lastReading = s.readingId;
            updateRows(s);
        }
        updateWorking(s);
        if (readOnly) return;
        editor.update(s);
    }

    private void updateRows(PocketController.Snapshot s) {
        View first = null;
        int firstOffset = 0;
        boolean bottom = !scroll.canScrollVertically(1);
        if (!bottom)
            for (int i = 0; i < feed.getChildCount(); i++)
                if (feed.getChildAt(i).getBottom() > scroll.getScrollY()) {
                    first = feed.getChildAt(i);
                    firstOffset = first.getTop() - scroll.getScrollY();
                    break;
                }
        anchor.capture(true);
        List<String> keys = new ArrayList<>();
        keys.add("older");
        older.setText(s.olderLoading ? ui.t("正在加载更早的记录…") : ui.explain(s.olderLabel));
        older.setEnabled(!s.olderLoading && !s.trying);
        if (s.rows.isEmpty()) {
            keys.add("empty");
            if (!keyed.containsKey("empty")) {
                LinearLayout empty = ui.column();
                ui.inset(empty, 20, 24);
                ui.add(empty, ui.label(ui.t("我在，随时说"), 24, ui.palette.text, true), 16);
                ui.add(
                        empty,
                        ui.label(
                                ui.t("按住下面的按钮说话，或切到键盘输入。环境模式会持续听着，回答接在这段对话里。"),
                                15,
                                ui.palette.secondary,
                                false),
                        12);
                ui.add(
                        empty,
                        ui.button(
                                ui.t("打开环境模式"),
                                ui.palette.accent,
                                ui.palette.raised,
                                () -> host.navigate("ambient")),
                        20);
                keyed.put("empty", empty);
            }
        }
        String day = "";
        long lastTime = 0;
        for (int index = 0; index < s.rows.size(); index++) {
            Message message = s.rows.get(index);
            String date = Json.day(message.at);
            if (!date.equals(day) || message.at - lastTime > 600_000) {
                String key = "time:" + message.id;
                keys.add(key);
                TextView time = (TextView) keyed.get(key);
                if (time == null) {
                    time = ui.label("", 11, ui.palette.subtle, false);
                    time.setGravity(Gravity.CENTER);
                    time.setPadding(0, ui.dp(12), 0, ui.dp(6));
                    keyed.put(key, time);
                }
                time.setText(
                        new SimpleDateFormat(
                                        date.equals(day) ? "HH:mm" : ui.t("M月d日 EEEE HH:mm"),
                                        ui.words.locale)
                                .format(new Date(message.at)));
                day = date;
            }
            lastTime = message.at;
            if (message.kind == Message.HEARD) {
                List<Message> heard = new ArrayList<>();
                heard.add(message);
                while (index + 1 < s.rows.size() && s.rows.get(index + 1).kind == Message.HEARD)
                    heard.add(s.rows.get(++index));
                String key = "heard:" + message.id;
                keys.add(key);
                String fp =
                        heard.size()
                                + ":"
                                + heard.get(heard.size() - 1).id
                                + ":"
                                + expandedHeard.contains(key);
                if (!fp.equals(fingerprints.get(key))) {
                    LinearLayout block = ui.column();
                    int start =
                            heard.size() > 3 && !expandedHeard.contains(key) ? heard.size() - 3 : 0;
                    if (start > 0)
                        block.addView(
                                ui.button(
                                        ui.format("房间里还说了 %d 句", start),
                                        ui.palette.subtle,
                                        0,
                                        () -> {
                                            expandedHeard.add(key);
                                            revision = -1;
                                            changed();
                                        }));
                    for (int i = start; i < heard.size(); i++) {
                        Message heardLine = heard.get(i);
                        TextView line =
                                ui.label(
                                        (heardLine.speaker.isEmpty()
                                                        ? ui.t("听到")
                                                        : heardLine.speaker)
                                                + " · "
                                                + (heardLine.id.startsWith("note:")
                                                        ? ui.t(heardLine.text)
                                                        : heardLine.text),
                                        13,
                                        ui.palette.subtle,
                                        false);
                        line.setPadding(ui.dp(6), ui.dp(5), ui.dp(6), ui.dp(5));
                        block.addView(line);
                    }
                    keyed.put(key, block);
                    fingerprints.put(key, fp);
                }
                continue;
            }
            String key = "row:" + message.id;
            keys.add(key);
            TurnState.Trace trace = s.traces.get(message.id);
            String fp =
                    message.text
                            + '|'
                            + message.delivery
                            + '|'
                            + message.error
                            + '|'
                            + message.durationMs
                            + '|'
                            + message.truncated
                            + '|'
                            + message.spoken
                            + '|'
                            + message.attachments.size()
                            + '|'
                            + (trace == null ? 0 : trace.steps.size())
                            + '|'
                            + s.readingId.equals(message.id);
            if (!fp.equals(fingerprints.get(key))) {
                View row = message(message, trace, s);
                keyed.put(key, row);
                fingerprints.put(key, fp);
            }
        }
        keys.add("working");
        keyed.put("working", working);
        for (int i = feed.getChildCount() - 1; i >= 0; i--)
            if (!keys.contains(String.valueOf(feed.getChildAt(i).getTag()))) feed.removeViewAt(i);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            View child = keyed.get(key);
            child.setTag(key);
            if (i < feed.getChildCount() && feed.getChildAt(i) == child) continue;
            if (child.getParent() == feed) feed.removeView(child);
            if (i < feed.getChildCount() && key.equals(feed.getChildAt(i).getTag()))
                feed.removeViewAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = ui.dp(7);
            feed.addView(child, Math.min(i, feed.getChildCount()), lp);
        }
        keyed.keySet().retainAll(keys);
        fingerprints.keySet().retainAll(keys);
        if (!bottom && first != null) {
            final View remembered = first;
            final int offset = firstOffset;
            scroll.post(
                    () -> {
                        if (remembered.getParent() == feed)
                            scroll.scrollTo(0, remembered.getTop() - offset);
                    });
        }
        if (!s.rows.isEmpty()) {
            String last = s.rows.get(s.rows.size() - 1).id;
            if (!last.equals(previousLatest)
                    && bottom
                    && s.rows.get(s.rows.size() - 1).kind == Message.DUODUO)
                anchor.startAt(keyed.get("row:" + last));
            previousLatest = last;
        }
    }

    private View message(Message m, TurnState.Trace trace, PocketController.Snapshot s) {
        boolean mine = m.kind == Message.MINE;
        LinearLayout row = ui.column();
        row.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        if (trace != null) {
            TextView detail =
                    ui.button(
                            ui.words.traceSummary(trace.steps.size(), trace.elapsedMs),
                            ui.palette.subtle,
                            0,
                            () -> traceSheet(trace));
            row.addView(detail);
        }
        LinearLayout bubble = new ReadableColumn(ui.context, ui.dp(ui.metrics.messageMaxWidthDp()));
        bubble.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        bubble.setBackground(ui.surface(mine ? ui.palette.mine : ui.palette.answer, 16));
        if (!m.voiceSource.isEmpty()) {
            TextView voice =
                    ui.label(
                            (m.voiceSource.equals("passport") ? ui.t("Passport 语音") : ui.t("语音"))
                                    + (m.durationMs > 0
                                            ? " · " + HoldToTalkView.duration(m.durationMs)
                                            : ""),
                            11,
                            mine ? ui.palette.mineText : ui.palette.secondary,
                            true);
            bubble.addView(voice);
        }
        for (Attachment a : m.attachments) {
            if (a.inlineImage() && (!a.sha256.isEmpty() || !a.localPath.isEmpty())) {
                ImageView thumbnail = new ImageView(ui.context);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(ui.surface(ui.palette.raised, 10));
                thumbnail.setClipToOutline(true);
                bubble.addView(
                        thumbnail,
                        new LinearLayout.LayoutParams(
                                ui.inlineImageWidth(), ui.inlineImageHeight()));
                images.bind(thumbnail, a, 512);
                thumbnail.setContentDescription(a.name);
                thumbnail.setFocusable(true);
                thumbnail.setOnClickListener(v -> host.openAttachment(a));
                continue;
            }
            LinearLayout file = ui.row();
            file.addView(
                    ui.icon(a.inlineImage() ? "image" : "file_text", ui.palette.secondary, 20));
            TextView name = ui.label(a.name, 13, ui.palette.text, false);
            name.setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(10));
            file.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            file.setMinimumHeight(ui.touchTarget());
            file.setOnClickListener(v -> host.openAttachment(a));
            file.setAlpha(a.sha256.isEmpty() ? .45f : 1);
            bubble.addView(file, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!m.text.isEmpty()) {
            if (mine) {
                TextView text = ui.label(m.text, 16, ui.palette.mineText, false);
                text.setLineSpacing(ui.dp(3), 1);
                text.setAutoLinkMask(Linkify.WEB_URLS | Linkify.PHONE_NUMBERS);
                text.setLinkTextColor(ui.palette.accent);
                ui.add(bubble, text, m.voiceSource.isEmpty() ? 0 : 6);
            } else ui.add(bubble, new MarkdownView(ui, m.text), 0);
        } else if (m.attachments.isEmpty())
            bubble.addView(
                    ui.label(
                            m.delivery == Message.FAILED ? ui.t("语音未送达") : ui.t("正在识别…"),
                            15,
                            mine ? ui.palette.mineText : ui.palette.secondary,
                            false));
        bubble.setMinimumWidth(ui.dp(66));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.leftMargin = mine ? ui.dp(32) : 0;
        bp.rightMargin = mine ? 0 : ui.dp(24);
        row.addView(bubble, bp);
        bindMenu(bubble, () -> messageActions(m));
        String meta = "";
        if (mine)
            meta =
                    m.delivery == Message.FAILED
                            ? m.error.isEmpty() ? ui.t("没发出去 · 轻点重试") : ui.explain(m.error)
                            : !m.voiceSource.isEmpty() && m.text.isEmpty()
                                    ? ui.t("正在识别…")
                                    : m.delivery == Message.QUEUED
                                            ? ui.t("等待网络 · 已存本机")
                                            : m.delivery == Message.SENDING
                                                    ? ui.t("发送中…")
                                                    : ui.t("已送达");
        else if (m.spoken == Message.PARTLY_SPOKEN || m.truncated) meta = ui.t("只播了一部分");
        else if (m.spoken == Message.SPOKEN) meta = ui.t("已播报");
        if (s.readingId.equals(m.id)) meta = ui.t("正在朗读 · 点按停止");
        if (!meta.isEmpty()) {
            TextView note =
                    ui.label(
                            meta,
                            10,
                            m.delivery == Message.FAILED ? ui.palette.attention : ui.palette.subtle,
                            false);
            note.setPadding(ui.dp(3), ui.dp(5), ui.dp(3), 0);
            row.addView(note);
            if (m.delivery == Message.FAILED) {
                note.setMinHeight(ui.touchTarget());
                note.setGravity(Gravity.CENTER_VERTICAL);
                ui.clickable(note, 0, 8, () -> messageActions(m));
            }
            if (s.readingId.equals(m.id)) note.setOnClickListener(v -> controller.readAloud(m));
        }
        bubble.setFocusable(true);
        bubble.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.addAction(
                                new android.view.accessibility.AccessibilityNodeInfo
                                        .AccessibilityAction(
                                        android.view.accessibility.AccessibilityNodeInfo
                                                .ACTION_LONG_CLICK,
                                        ui.t("消息操作")));
                    }
                });
        return row;
    }

    private String upgradeTitle(UpgradeNotice notice) {
        if (notice == UpgradeNotice.APP_UPDATE) return ui.t("多多 需要更新 · 查看说明");
        if (notice == UpgradeNotice.CHANNEL_UPGRADE) return ui.t("主机服务需要升级 · 查看说明");
        return "";
    }

    private void showUpgrade() {
        PocketController.Snapshot current = controller.snapshot();
        if (current == null || current.upgrade == UpgradeNotice.NONE) return;
        boolean app = current.upgrade == UpgradeNotice.APP_UPDATE;
        LinearLayout body = ui.column();
        TextView explanation =
                ui.label(
                        app
                                ? ui.t("主机服务已升级，请安装新版 多多 后重新打开。已保存的待发内容会保留。")
                                : ui.t("请在电脑上按部署文档升级 ambient，再重新检查。待发内容会保留。"),
                        14,
                        ui.palette.text,
                        false);
        ui.inset(explanation, 8, 10);
        body.addView(explanation);
        android.app.Dialog[] dialog = {null};
        ui.add(
                body,
                ui.button(
                        ui.t("重新检查"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () -> {
                            dialog[0].dismiss();
                            controller.checkCompatibility();
                        }),
                8);
        dialog[0] = Sheets.content(ui, app ? ui.t("多多 需要更新") : ui.t("主机服务需要升级"), body);
    }

    private void bindMenu(View view, Runnable action) {
        view.setOnLongClickListener(
                v -> {
                    action.run();
                    return true;
                });
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) bindMenu(group.getChildAt(i), action);
        }
    }

    private void messageActions(Message m) {
        List<Sheets.Action> actions = new ArrayList<>();
        if (!m.text.isEmpty()) {
            actions.add(new Sheets.Action(ui.t("复制"), () -> ui.copy(ui.t("消息"), m.text)));
            actions.add(new Sheets.Action(ui.t("分享"), () -> ui.shareText(m.text)));
        }
        if (m.kind == Message.DUODUO && !m.text.isEmpty()) {
            actions.add(
                    new Sheets.Action(
                            ui.t("回复"),
                            () -> {
                                controller.quote(m.text);
                                if (readOnly) {
                                    host.navigate("conversation");
                                    return;
                                }
                                editor.focus();
                            }));
            actions.add(
                    new Sheets.Action(
                            controller.snapshot().readingId.equals(m.id)
                                    ? ui.t("停止朗读")
                                    : ui.t("朗读"),
                            () -> controller.readAloud(m)));
        }
        if (m.delivery == Message.FAILED || m.delivery == Message.QUEUED) {
            actions.add(
                    new Sheets.Action(
                            m.seq >= 0 ? ui.t("重新发送") : ui.t("重试"), () -> controller.retry(m.id)));
            if (m.seq < 0)
                actions.add(
                        new Sheets.Action(
                                ui.t("删除待发送消息"),
                                true,
                                () ->
                                        Sheets.confirm(
                                                ui,
                                                ui.t("删除待发送消息"),
                                                ui.t("这条消息和本机保留的录音会被删除。"),
                                                ui.t("删除"),
                                                () -> controller.discard(m.id))));
        }
        Sheets.actions(ui, ui.t("消息"), actions.toArray(new Sheets.Action[0]));
    }

    private void traceSheet(TurnState.Trace trace) {
        List<Sheets.Action> rows = new ArrayList<>();
        for (TurnState.Step step : trace.steps)
            rows.add(
                    new Sheets.Action(
                            (step.done ? "✓ " : "… ") + step.line(),
                            () -> ui.copy(ui.t("工具步骤"), step.line())));
        Sheets.actions(ui, ui.t("执行工具"), rows.toArray(new Sheets.Action[0]));
    }

    private void updateWorking(PocketController.Snapshot s) {
        TurnState.Working w = s.working;
        anchor.capture(true);
        working.setVisibility(w == null ? View.GONE : View.VISIBLE);
        if (w == null) return;
        String value = w.text.isEmpty() ? ui.t(w.label()) : w.text + " ▍";
        if (w.text.isEmpty() && !w.steps.isEmpty()) {
            TurnState.Step step = w.steps.get(w.steps.size() - 1);
            value +=
                    (w.steps.size() > 1 ? " · +" + ui.words.steps(w.steps.size() - 1) : "")
                            + "\n"
                            + step.line();
        }
        Ui.updateText(working, value);
    }

    public void hidden() {
        if (editor != null) editor.hidden();
    }

    public void close() {
        hidden();
        images.close();
        if (editor != null) editor.close();
        controller.removeListener(this);
        if (anchor != null) anchor.close();
    }
}
