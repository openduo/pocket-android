// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.ui.pages;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.ImportProgress;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.AdaptiveColumn;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.AttachmentImages;
import dev.openduo.pocket.ui.components.ComposerInput;
import dev.openduo.pocket.ui.components.HoldToTalkView;
import dev.openduo.pocket.ui.components.Sheets;

/** Persistent input controls and their transient editor/scroll state, independent of the feed. */
final class ConversationComposer {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final Runnable sent;
    private final AttachmentImages images;
    private LinearLayout composer, textRow, chipRow, quoteRow, holdPanel, importRow;
    private TextView holdLabel, quoteText, importLabel;
    private EditText input;
    private View voiceToggle, send, importCancel;
    private HoldToTalkView hold;
    private HorizontalScrollView chipScroll;
    private final java.util.Set<String> chipPaths = new java.util.HashSet<>();
    private int pendingChipScroll = -1;
    private String chipKey = "", importId = "";
    private boolean applying;
    private long draftGeneration, pendingEdit;

    ConversationComposer(PageHost host, PocketController controller, Runnable sent) {
        this.host = host;
        this.controller = controller;
        this.sent = sent;
        ui = host.ui();
        images = new AttachmentImages(controller);
    }

    void render(AdaptiveColumn root) {
        AdaptiveColumn adaptive = root;
        holdPanel = ui.column();
        ui.inset(holdPanel, 18, 10);
        holdPanel.setBackground(ui.surface(ui.palette.surface, 16));
        holdLabel = ui.label(ui.t("准备中…"), 18, ui.palette.text, true);
        holdLabel.setGravity(Gravity.CENTER);
        holdPanel.addView(holdLabel);
        HoldToTalkView.Meter meter = new HoldToTalkView.Meter(ui, controller);
        holdPanel.addView(meter, new LinearLayout.LayoutParams(-1, ui.controlHeight()));
        TextView hint = ui.label(ui.t("确认键发送 · 取消按钮放弃本次录音"), 12, ui.palette.secondary, false);
        hint.setGravity(Gravity.CENTER);
        holdPanel.addView(hint);
        LinearLayout voiceActions = ui.row();
        voiceActions.addView(
                ui.button(
                        ui.t("取消录音"),
                        ui.palette.secondary,
                        ui.palette.raised,
                        () ->
                                host.input()
                                        .invoke(
                                                dev.openduo.pocket.input.InputAction.CANCEL_OR_STOP,
                                                host.input().owner("voice-cancel"))),
                new LinearLayout.LayoutParams(0, -2, 1));
        voiceActions.addView(
                ui.button(
                        ui.t("发送录音"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () ->
                                host.input()
                                        .invoke(
                                                dev.openduo.pocket.input.InputAction.SEND_VOICE,
                                                host.input().owner("voice-send"))),
                new LinearLayout.LayoutParams(0, -2, 1));
        holdPanel.addView(voiceActions);
        root.addView(holdPanel);
        holdPanel.setVisibility(View.GONE);
        composer = ui.column();
        composer.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(8));
        quoteRow = ui.row();
        quoteText = ui.label("", 12, ui.palette.secondary, false);
        quoteText.setMaxLines(2);
        quoteRow.addView(quoteText, new LinearLayout.LayoutParams(0, -2, 1));
        quoteRow.addView(
                ui.iconButton(
                        "minus", ui.t("取消引用"), ui.palette.secondary, () -> controller.quote("")));
        composer.addView(quoteRow);
        importRow = ui.row();
        importRow.setMinimumHeight(ui.touchTarget());
        importRow.setBackground(ui.surface(ui.palette.raised, 10));
        importLabel = ui.label("", 12, ui.palette.secondary, false);
        ui.inset(importLabel, 8, 2);
        importLabel.setMaxLines(2);
        importLabel.setEllipsize(TextUtils.TruncateAt.END);
        importRow.addView(importLabel, new LinearLayout.LayoutParams(0, -2, 1));
        importCancel =
                ui.iconButton(
                        "minus",
                        ui.t("取消导入"),
                        ui.palette.secondary,
                        () -> controller.cancelImport(importId));
        importRow.addView(
                importCancel,
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        composer.addView(importRow);
        chipScroll = new HorizontalScrollView(ui.context);
        chipScroll.setHorizontalScrollBarEnabled(true);
        chipScroll.setScrollbarFadingEnabled(false);
        chipScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> images.refreshVisible());
        chipRow = ui.row();
        chipScroll.addView(chipRow);
        composer.addView(chipScroll);
        textRow = ui.row();
        voiceToggle =
                ui.iconButton(
                        "mic", ui.t("切换语音或文字"), ui.palette.secondary, controller::toggleVoiceInput);
        textRow.addView(
                voiceToggle,
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        input =
                ui.configureInput(
                        new ComposerInput(ui.context, controller::importAttachment),
                        ui.t("发消息…"),
                        controller.draft(),
                        true);
        input.setId(dev.openduo.pocket.R.id.composer);
        input.setSaveEnabled(
                false); // The controller owns the durable text; the UI restores selection.
        input.setMaxLines(4);
        adaptive.editor(input, 4);
        input.setMinHeight(ui.touchTarget());
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(
                new TextWatcher() {
                    private String before = "";

                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                        if (!applying) before = s.toString();
                    }

                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        if (!applying)
                            pendingEdit =
                                    controller.editDraft(before, s.toString(), draftGeneration);
                    }

                    public void afterTextChanged(Editable e) {}
                });
        textRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        hold = new HoldToTalkView(host, controller);
        textRow.addView(hold, new LinearLayout.LayoutParams(0, -2, 1));
        textRow.addView(
                ui.iconButton(
                        "plus", ui.t("添加照片、拍照、文件或位置"), ui.palette.secondary, this::attachMenu),
                new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        send =
                ui.iconButton(
                        "chevron_right",
                        ui.t("发送消息"),
                        ui.palette.accent,
                        () -> {
                            controller.sendDraft();
                            sent.run();
                        });
        textRow.addView(
                send, new LinearLayout.LayoutParams(ui.iconButtonSize(), ui.iconButtonSize()));
        composer.addView(textRow);
        root.addView(ui.scrollFooter(composer));
        adaptive.getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        () -> {
                            images.refreshVisible();
                            if (chipScroll != null
                                    && pendingChipScroll >= 0
                                    && chipScroll.getWidth() > 0) {
                                int target = pendingChipScroll;
                                pendingChipScroll = -1;
                                chipScroll.scrollTo(
                                        Math.min(
                                                target,
                                                Math.max(
                                                        0,
                                                        chipRow.getWidth()
                                                                - chipScroll.getWidth())),
                                        0);
                            }
                        });
    }

    private void attachMenu() {
        Sheets.actions(
                ui,
                ui.t("添加到消息"),
                new Sheets.Action(ui.t("照片"), () -> host.pickAttachment("image/*")),
                new Sheets.Action(ui.t("拍照"), host::takePhoto),
                new Sheets.Action(ui.t("文件"), () -> host.pickAttachment("*/*")),
                new Sheets.Action(ui.t("位置"), () -> host.navigate("location")));
    }

    void update(PocketController.Snapshot s) {
        boolean ambientOn = s.ambientActive, voice = s.holdRecording || s.voiceInput && !ambientOn;
        voiceToggle.setVisibility(ambientOn ? View.GONE : View.VISIBLE);
        voiceToggle.setContentDescription(voice ? ui.t("切换到文字输入") : ui.t("切换到按住说话"));
        hold.setVisibility(voice ? View.VISIBLE : View.GONE);
        input.setVisibility(voice ? View.GONE : View.VISIBLE);
        if (voice) ui.hideKeyboard(input);
        boolean editing = draftGeneration == s.draftGeneration && s.draftEditAck < pendingEdit;
        if (!TextUtils.equals(input.getText(), s.draft) && !editing) {
            int start = input.getSelectionStart(), end = input.getSelectionEnd();
            boolean focused = input.hasFocus();
            applying = true;
            input.setText(s.draft);
            if (focused && start >= 0 && end >= 0)
                input.setSelection(Math.min(start, input.length()), Math.min(end, input.length()));
            else input.setSelection(input.length());
            applying = false;
        }
        draftGeneration = s.draftGeneration;
        send.setVisibility(
                (!voice && !s.draft.trim().isEmpty() || !s.chips.isEmpty())
                        ? View.VISIBLE
                        : View.GONE);
        send.setEnabled(s.imports.isEmpty());
        send.setAlpha(s.imports.isEmpty() ? 1f : .4f);
        importRow.setVisibility(s.imports.isEmpty() ? View.GONE : View.VISIBLE);
        if (!s.imports.isEmpty()) {
            ImportProgress progress = s.imports.get(0);
            importId = progress.id;
            String status =
                    progress.cancelling
                            ? ui.t("正在取消导入…")
                            : !progress.started
                                    ? ui.t("等待导入…")
                                    : progress.total == 0
                                            ? ui.t("正在导入…")
                                            : ui.format(
                                                    "正在导入 %d/%d · %s",
                                                    progress.index + 1,
                                                    progress.total,
                                                    ui.format(
                                                            "%d KB",
                                                            (progress.bytes + 1023) / 1024));
            String detail =
                    s.imports.size() > 1
                            ? ui.format("还有 %d 项等待导入", s.imports.size() - 1)
                            : progress.name;
            Ui.updateText(importLabel, status + (detail.isEmpty() ? "" : "\n" + detail));
            importCancel.setEnabled(!progress.cancelling);
            importCancel.setAlpha(progress.cancelling ? .4f : 1f);
        } else importId = "";
        quoteRow.setVisibility(s.quote.isEmpty() ? View.GONE : View.VISIBLE);
        Ui.updateText(quoteText, ui.t("回复：") + s.quote);
        StringBuilder ck = new StringBuilder();
        for (Attachment a : s.chips) ck.append(a.localPath).append(':').append(a.state).append('|');
        if (!chipKey.equals(ck.toString())) {
            chipKey = ck.toString();
            java.util.Set<String> nextPaths = new java.util.HashSet<>();
            boolean added = false;
            for (Attachment a : s.chips) {
                nextPaths.add(a.localPath);
                if (!chipPaths.contains(a.localPath)) added = true;
            }
            if (added) pendingChipScroll = Integer.MAX_VALUE;
            else if (pendingChipScroll < 0) pendingChipScroll = chipScroll.getScrollX();
            chipPaths.clear();
            chipPaths.addAll(nextPaths);
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
                    chip.addView(
                            thumbnail,
                            new LinearLayout.LayoutParams(ui.touchTarget(), ui.touchTarget()));
                    images.bind(thumbnail, a, 128);
                    thumbnail.setContentDescription(a.name);
                    thumbnail.setFocusable(true);
                    thumbnail.setOnClickListener(v -> host.openAttachment(a));
                }
                TextView name =
                        ui.label(
                                a.name
                                        + (a.state == Attachment.UPLOADING
                                                ? ui.t(" · 上传中")
                                                : a.state == Attachment.FAILED
                                                        ? ui.t(" · 重试")
                                                        : ""),
                                12,
                                a.state == Attachment.FAILED
                                        ? ui.palette.attention
                                        : ui.palette.secondary,
                                false);
                name.setMaxWidth(ui.attachmentNameMaxWidth());
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                chip.addView(name);
                name.setOnClickListener(
                        v -> {
                            if (a.state == Attachment.FAILED)
                                controller.retryAttachment(a.localPath);
                            else if (a.state == Attachment.UPLOADED) host.openAttachment(a);
                        });
                chip.addView(
                        ui.iconButton(
                                "minus",
                                ui.t("移除 ") + a.name,
                                ui.palette.secondary,
                                () -> controller.removeAttachment(a.localPath)));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.rightMargin = ui.dp(6);
                chipRow.addView(chip, cp);
            }
        }
        chipRow.setVisibility(s.chips.isEmpty() ? View.GONE : View.VISIBLE);
        hold.update(s);
        holdPanel.setVisibility(s.holdRecording && !hold.touchHolding() ? View.VISIBLE : View.GONE);
        Ui.updateText(
                holdLabel,
                hold.cancelArmed()
                        ? ui.t("松手取消")
                        : s.holdLive ? HoldToTalkView.duration(s.holdDurationMs) : ui.t("准备中…"));
    }

    void focus() {
        input.requestFocus();
        ((android.view.inputmethod.InputMethodManager)
                        ui.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    void hidden() {
        if (hold != null) hold.cancel();
        if (input != null) ui.hideKeyboard(input);
    }

    void close() {
        hidden();
        images.close();
    }
}
