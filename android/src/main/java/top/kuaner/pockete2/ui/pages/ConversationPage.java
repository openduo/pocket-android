// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import top.kuaner.pockete2.core.Attachment;
import top.kuaner.pockete2.core.Json;
import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.TurnState;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.ScrollAnchor;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.AttachmentImages;
import top.kuaner.pockete2.ui.components.ComposerInput;
import top.kuaner.pockete2.ui.components.HoldToTalkView;
import top.kuaner.pockete2.ui.components.Sheets;
import top.kuaner.pockete2.ui.markdown.MarkdownView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private LinearLayout root, feed, composer, textRow, chipRow, quoteRow, holdPanel;
    private TextView title, subtitle, banner, older, working, jump, holdLabel, quoteText;
    private EditText input;
    private View voiceToggle, send, holdSend;
    private HoldToTalkView hold;
    private ScrollView scroll;
    private ScrollAnchor anchor;
    private boolean applying;
    private String chipKey = "", previousLatest = "", lastDraft = "", lastReading = "";
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
        if (root != null) return root;
        root = ui.column();
        root.setBackgroundColor(ui.palette.background);
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(8), 0, ui.dp(4), 0);
        LinearLayout heading = ui.column();
        title = ui.label("Pocket E2", 18, ui.palette.text, true);
        subtitle = ui.label("加载中…", 11, ui.palette.secondary, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title);
        ui.add(heading, subtitle, 4);
        header.addView(heading, new LinearLayout.LayoutParams(0, ui.dp(56), 1));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                ui.iconButton(
                        "bluetooth",
                        "Passport 配件",
                        ui.palette.secondary,
                        () -> host.navigate("passport")),
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        header.addView(
                ui.iconButton(
                        "audio_lines", "环境模式", ui.palette.accent, () -> host.navigate("ambient")),
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        root.addView(header);
        if (readOnly) header.setVisibility(View.GONE);
        banner = ui.label("", 12, ui.palette.attention, false);
        ui.inset(banner, 12, 8);
        banner.setBackground(ui.surface(ui.palette.attentionTint, 10));
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
        root.addView(banner);
        FrameLayout thread = new FrameLayout(ui.context);
        feed = ui.column();
        feed.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(16));
        feed.setFocusableInTouchMode(true);
        scroll = ui.scroll(feed);
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        thread.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        jump =
                ui.button(
                        "回到最新",
                        ui.palette.accent,
                        ui.palette.raised,
                        () -> {
                            anchor.toBottom();
                            feed.requestLayout();
                        });
        FrameLayout.LayoutParams jp =
                new FrameLayout.LayoutParams(
                        -2, ui.dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
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
        older = ui.button("查看更早的记录", ui.palette.subtle, 0, controller::loadOlder);
        keyed.put("older", older);
        working = ui.label("", 14, ui.palette.secondary, false);
        ui.inset(working, 12, 12);
        working.setBackground(ui.surface(ui.palette.raised, 12));
        root.getViewTreeObserver().addOnGlobalLayoutListener(images::refreshVisible);
        if (readOnly) {
            controller.addListener(this);
            changed();
            anchor.toBottom();
            return root;
        }
        holdPanel = ui.column();
        ui.inset(holdPanel, 18, 10);
        holdPanel.setBackground(ui.surface(ui.palette.surface, 16));
        holdLabel = ui.label("准备中…", 18, ui.palette.text, true);
        holdLabel.setGravity(Gravity.CENTER);
        holdPanel.addView(holdLabel);
        HoldToTalkView.Meter meter = new HoldToTalkView.Meter(ui, controller);
        holdPanel.addView(meter, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        TextView hint = ui.label("麦克风就绪后轻震 · 移出按钮取消", 12, ui.palette.secondary, false);
        hint.setGravity(Gravity.CENTER);
        holdPanel.addView(hint);
        root.addView(holdPanel);
        holdPanel.setVisibility(View.GONE);
        composer = ui.column();
        composer.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(8));
        quoteRow = ui.row();
        quoteText = ui.label("", 12, ui.palette.secondary, false);
        quoteText.setMaxLines(2);
        quoteRow.addView(quoteText, new LinearLayout.LayoutParams(0, -2, 1));
        quoteRow.addView(
                ui.iconButton("minus", "取消引用", ui.palette.secondary, () -> controller.quote("")));
        composer.addView(quoteRow);
        HorizontalScrollView chipScroll = new HorizontalScrollView(ui.context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> images.refreshVisible());
        chipRow = ui.row();
        chipScroll.addView(chipRow);
        composer.addView(chipScroll);
        textRow = ui.row();
        voiceToggle =
                ui.iconButton("mic", "切换语音或文字", ui.palette.secondary, controller::toggleVoiceInput);
        textRow.addView(voiceToggle, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        input =
                ui.configureInput(
                        new ComposerInput(ui.context, controller::importAttachment),
                        "发消息…",
                        controller.draft(),
                        true);
        input.setMaxLines(4);
        input.setMinHeight(ui.dp(48));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        if (!applying) controller.draft(s.toString());
                    }

                    public void afterTextChanged(Editable e) {}
                });
        textRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        hold = new HoldToTalkView(host, controller);
        textRow.addView(hold, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        textRow.addView(
                ui.iconButton("plus", "添加照片、拍照、文件或位置", ui.palette.secondary, this::attachMenu),
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        send =
                ui.iconButton(
                        "chevron_right",
                        "发送消息",
                        ui.palette.accent,
                        () -> {
                            controller.sendDraft();
                            anchor.toBottom();
                        });
        textRow.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        composer.addView(textRow);
        root.addView(composer);
        controller.addListener(this);
        changed();
        anchor.toBottom();
        return root;
    }

    private void attachMenu() {
        Sheets.actions(
                ui,
                "添加到消息",
                new Sheets.Action("照片", () -> host.pickAttachment("image/*")),
                new Sheets.Action("拍照", host::takePhoto),
                new Sheets.Action("文件", () -> host.pickAttachment("*/*")),
                new Sheets.Action("位置", () -> host.navigate("location")));
    }

    @Override
    public void changed() {
        if (root == null) return;
        PocketController.Snapshot s = controller.snapshot();
        if (s == null) return;
        Ui.updateText(title, s.roomName.isEmpty() ? "Pocket E2" : s.roomName);
        Ui.updateText(subtitle, s.status);
        String notice =
                !s.lastError.isEmpty()
                        ? s.lastError
                        : s.trying ? "体验房间 · 回答为预设内容" : !s.daemonAvailable ? "暂时无法回复，消息会先记下" : "";
        Ui.updateText(banner, notice);
        banner.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        if (revision != s.revision || s.trying || !lastReading.equals(s.readingId)) {
            revision = s.revision;
            lastReading = s.readingId;
            updateRows(s);
        }
        updateWorking(s);
        if (readOnly) return;
        boolean ambientOn = s.ambientActive, voice = s.voiceInput && !ambientOn;
        voiceToggle.setVisibility(ambientOn ? View.GONE : View.VISIBLE);
        voiceToggle.setContentDescription(voice ? "切换到文字输入" : "切换到按住说话");
        hold.setVisibility(voice ? View.VISIBLE : View.GONE);
        input.setVisibility(voice ? View.GONE : View.VISIBLE);
        if (voice) ui.hideKeyboard(input);
        if (!TextUtils.equals(input.getText(), s.draft)
                && (!input.hasFocus()
                        || s.draft.isEmpty() && TextUtils.equals(input.getText(), lastDraft))) {
            applying = true;
            input.setText(s.draft);
            input.setSelection(input.length());
            applying = false;
        }
        lastDraft = s.draft;
        send.setVisibility(
                (!voice && !s.draft.trim().isEmpty() || !s.chips.isEmpty())
                        ? View.VISIBLE
                        : View.GONE);
        quoteRow.setVisibility(s.quote.isEmpty() ? View.GONE : View.VISIBLE);
        Ui.updateText(quoteText, "回复：" + s.quote);
        StringBuilder ck = new StringBuilder();
        for (Attachment a : s.chips) ck.append(a.localPath).append(':').append(a.state).append('|');
        if (!chipKey.equals(ck.toString())) {
            chipKey = ck.toString();
            chipRow.removeAllViews();
            for (Attachment a : s.chips) {
                LinearLayout chip = ui.row();
                ui.inset(chip, 8, 2);
                chip.setBackground(
                        ui.surface(
                                a.state == Attachment.FAILED
                                        ? ui.palette.attentionTint
                                        : ui.palette.raised,
                                10));
                if (a.inlineImage()) {
                    ImageView thumbnail = new ImageView(ui.context);
                    thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    chip.addView(thumbnail, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
                    images.bind(thumbnail, a, 128);
                    thumbnail.setOnClickListener(v -> host.openAttachment(a));
                }
                TextView name =
                        ui.label(
                                a.name
                                        + (a.state == Attachment.UPLOADING
                                                ? " · 上传中"
                                                : a.state == Attachment.FAILED ? " · 重试" : ""),
                                12,
                                a.state == Attachment.FAILED
                                        ? ui.palette.attention
                                        : ui.palette.secondary,
                                false);
                name.setMaxWidth(ui.dp(180));
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                chip.addView(name);
                name.setOnClickListener(
                        v -> {
                            if (a.state == Attachment.FAILED)
                                controller.retryAttachment(a.localPath);
                        });
                chip.addView(
                        ui.iconButton(
                                "minus",
                                "移除 " + a.name,
                                ui.palette.secondary,
                                () -> controller.removeAttachment(a.localPath)));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.rightMargin = ui.dp(6);
                chipRow.addView(chip, cp);
            }
        }
        chipRow.setVisibility(s.chips.isEmpty() ? View.GONE : View.VISIBLE);
        hold.update(s);
        holdPanel.setVisibility(s.holdRecording ? View.VISIBLE : View.GONE);
        Ui.updateText(
                holdLabel,
                hold.cancelArmed()
                        ? "松手取消"
                        : s.holdLive ? HoldToTalkView.duration(s.holdDurationMs) : "准备中…");
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
        older.setText(s.olderLoading ? "正在加载更早的记录…" : s.olderLabel);
        older.setEnabled(!s.olderLoading && !s.trying);
        if (s.rows.isEmpty()) {
            keys.add("empty");
            if (!keyed.containsKey("empty")) {
                LinearLayout empty = ui.column();
                ui.inset(empty, 20, 24);
                ui.add(empty, ui.label("我在，随时说", 24, ui.palette.text, true), 16);
                ui.add(
                        empty,
                        ui.label(
                                "按住下面的按钮说话，或切到键盘输入。环境模式会持续听着，回答接在这段对话里。",
                                15,
                                ui.palette.secondary,
                                false),
                        12);
                ui.add(
                        empty,
                        ui.button(
                                "打开环境模式",
                                ui.palette.accent,
                                ui.palette.raised,
                                () -> host.navigate("ambient")),
                        20);
                ui.add(
                        empty,
                        ui.button(
                                "添加 Passport",
                                ui.palette.secondary,
                                0,
                                () -> host.navigate("passport")),
                        8);
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
                                        date.equals(day) ? "HH:mm" : "M月d日 EEEE HH:mm",
                                        Locale.CHINA)
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
                                        "房间里还说了 " + start + " 句",
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
                                        (heardLine.speaker.isEmpty() ? "听到" : heardLine.speaker)
                                                + " · "
                                                + heardLine.text,
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
                    ui.button(trace.summary(), ui.palette.subtle, 0, () -> traceSheet(trace));
            row.addView(detail);
        }
        LinearLayout bubble = ui.column();
        bubble.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        bubble.setBackground(ui.surface(mine ? ui.palette.mine : ui.palette.answer, 16));
        if (!m.voiceSource.isEmpty()) {
            TextView voice =
                    ui.label(
                            (m.voiceSource.equals("passport") ? "Passport 语音" : "语音")
                                    + (m.durationMs > 0
                                            ? " · " + HoldToTalkView.duration(m.durationMs)
                                            : ""),
                            11,
                            mine ? ui.palette.accent : ui.palette.secondary,
                            true);
            bubble.addView(voice);
        }
        for (Attachment a : m.attachments) {
            if (a.inlineImage() && (!a.sha256.isEmpty() || !a.localPath.isEmpty())) {
                ImageView thumbnail = new ImageView(ui.context);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(ui.surface(ui.palette.raised, 10));
                thumbnail.setClipToOutline(true);
                bubble.addView(thumbnail, new LinearLayout.LayoutParams(ui.dp(156), ui.dp(140)));
                images.bind(thumbnail, a, 512);
                thumbnail.setOnClickListener(v -> host.openAttachment(a));
                continue;
            }
            LinearLayout file = ui.row();
            file.addView(
                    ui.icon(a.inlineImage() ? "image" : "file_text", ui.palette.secondary, 20));
            TextView name = ui.label(a.name, 13, ui.palette.text, false);
            name.setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(10));
            file.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            file.setMinimumHeight(ui.dp(44));
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
                            m.delivery == Message.FAILED ? "语音未送达" : "正在识别…",
                            15,
                            ui.palette.secondary,
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
                            ? m.error.isEmpty() ? "没发出去 · 轻点重试" : m.error
                            : !m.voiceSource.isEmpty() && m.text.isEmpty()
                                    ? "正在识别…"
                                    : m.delivery == Message.QUEUED
                                            ? "等待网络 · 已存本机"
                                            : m.delivery == Message.SENDING ? "发送中…" : "已送达";
        else if (m.spoken == Message.PARTLY_SPOKEN || m.truncated) meta = "只播了一部分";
        else if (m.spoken == Message.SPOKEN) meta = "已播报";
        if (s.readingId.equals(m.id)) meta = "正在朗读 · 点按停止";
        if (!meta.isEmpty()) {
            TextView note =
                    ui.label(
                            meta,
                            10,
                            m.delivery == Message.FAILED ? ui.palette.attention : ui.palette.subtle,
                            false);
            note.setPadding(ui.dp(3), ui.dp(5), ui.dp(3), 0);
            row.addView(note);
            if (m.delivery == Message.FAILED) note.setOnClickListener(v -> messageActions(m));
            if (s.readingId.equals(m.id)) note.setOnClickListener(v -> controller.readAloud(m));
        }
        row.setContentDescription((mine ? "我" : "回答") + "，" + m.text + "，" + meta);
        return row;
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
            actions.add(new Sheets.Action("复制", () -> ui.copy("消息", m.text)));
            actions.add(new Sheets.Action("分享", () -> ui.shareText(m.text)));
        }
        if (m.kind == Message.DUODUO && !m.text.isEmpty()) {
            actions.add(
                    new Sheets.Action(
                            "回复",
                            () -> {
                                controller.quote(m.text);
                                if (readOnly) {
                                    host.navigate("conversation");
                                    return;
                                }
                                input.requestFocus();
                                ((android.view.inputmethod.InputMethodManager)
                                                ui.context.getSystemService(
                                                        android.content.Context
                                                                .INPUT_METHOD_SERVICE))
                                        .showSoftInput(
                                                input,
                                                android.view.inputmethod.InputMethodManager
                                                        .SHOW_IMPLICIT);
                            }));
            actions.add(
                    new Sheets.Action(
                            controller.snapshot().readingId.equals(m.id) ? "停止朗读" : "朗读",
                            () -> controller.readAloud(m)));
        }
        if (m.delivery == Message.FAILED || m.delivery == Message.QUEUED) {
            actions.add(
                    new Sheets.Action(m.seq >= 0 ? "重新发送" : "重试", () -> controller.retry(m.id)));
            if (m.seq < 0)
                actions.add(
                        new Sheets.Action(
                                "删除待发送消息",
                                true,
                                () ->
                                        Sheets.confirm(
                                                ui,
                                                "删除待发送消息",
                                                "这条消息和本机保留的录音会被删除。",
                                                "删除",
                                                () -> controller.discard(m.id))));
        }
        Sheets.actions(ui, "消息", actions.toArray(new Sheets.Action[0]));
    }

    private void traceSheet(TurnState.Trace trace) {
        List<Sheets.Action> rows = new ArrayList<>();
        for (TurnState.Step step : trace.steps)
            rows.add(
                    new Sheets.Action(
                            (step.done ? "✓ " : "… ") + step.line(),
                            () -> ui.copy("工具步骤", step.line())));
        Sheets.actions(ui, "执行工具", rows.toArray(new Sheets.Action[0]));
    }

    private void updateWorking(PocketController.Snapshot s) {
        TurnState.Working w = s.working;
        anchor.capture(true);
        working.setVisibility(w == null ? View.GONE : View.VISIBLE);
        if (w == null) return;
        String value = w.text.isEmpty() ? w.label() : w.text + " ▍";
        if (w.text.isEmpty() && !w.steps.isEmpty()) {
            TurnState.Step step = w.steps.get(w.steps.size() - 1);
            value +=
                    (w.steps.size() > 1 ? " · +" + (w.steps.size() - 1) + " 步" : "")
                            + "\n"
                            + step.line();
        }
        Ui.updateText(working, value);
    }

    public void hidden() {
        if (hold != null) hold.cancel();
        if (input != null) ui.hideKeyboard(input);
    }

    public void close() {
        hidden();
        images.close();
        controller.removeListener(this);
        if (anchor != null) anchor.close();
    }
}
