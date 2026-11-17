// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.ui.pages;

import android.graphics.drawable.InsetDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.R;
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
import dev.openduo.pocket.ui.components.VoiceRecordingPanel;

/** Persistent input controls and their transient editor/scroll state, independent of the feed. */
final class ConversationComposer {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final Runnable sent;
    private final AttachmentImages images;
    private LinearLayout chipRow, quoteRow, importRow;
    private TextView quoteText, importLabel;
    private EditText input;
    private View voiceToggle, attach, send, importCancel;
    private ImageView modeIcon;
    private HoldToTalkView hold;
    private VoiceRecordingPanel recording;
    private HorizontalScrollView chipScroll;
    private final java.util.Set<String> chipPaths = new java.util.HashSet<>();
    private int pendingChipScroll = -1;
    private String chipKey = "", importId = "";
    private boolean applying, focusTypingOnToggle;
    private long draftGeneration, pendingEdit;

    ConversationComposer(PageHost host, PocketController controller, Runnable sent) {
        this.host = host;
        this.controller = controller;
        this.sent = sent;
        ui = host.ui();
        images = new AttachmentImages(controller);
    }

    void render(AdaptiveColumn root, LinearLayout chat, FrameLayout thread) {
        recording = new VoiceRecordingPanel(host);
        thread.addView(recording, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout footer = ui.column();
        footer.addView(ui.divider());
        LinearLayout composer = ui.column();
        int sidePadding = Math.max(ui.dp(8), ui.pagePadding() - ui.dp(4));
        composer.setPadding(sidePadding, ui.dp(3), sidePadding, ui.dp(4));
        footer.addView(ui.scrollFooter(composer));
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
        LinearLayout textRow = ui.row();
        textRow.setGravity(Gravity.BOTTOM);
        modeIcon = ui.icon("mic", ui.palette.secondary, 19);
        voiceToggle = action(modeIcon, ui.t("切换语音或文字"), ui.palette.raised, this::toggleMode);
        textRow.addView(voiceToggle, actionSize());
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
        root.editor(input, 4);
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setIncludeFontPadding(false);
        input.setMinimumHeight(ui.touchTarget());
        input.setMinHeight(ui.touchTarget());
        ui.composerSurface(input, ui.palette.raised);
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
        hold = new HoldToTalkView(host, controller, () -> updateRecording(controller.snapshot()));
        textRow.addView(hold, new LinearLayout.LayoutParams(0, -2, 1));
        attach =
                action(
                        ui.icon("plus", ui.palette.secondary, 19),
                        ui.t("添加照片、拍照、文件或位置"),
                        ui.palette.raised,
                        this::attachMenu);
        textRow.addView(attach, actionSize());
        send =
                action(
                        ui.icon("arrow_up", ui.palette.onAccent, 19),
                        ui.t("发送消息"),
                        ui.palette.accent,
                        () -> {
                            controller.sendDraft();
                            sent.run();
                        });
        textRow.addView(send, actionSize());
        composer.addView(textRow);
        chat.addView(footer);
        root.getViewTreeObserver()
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

    private View action(ImageView icon, String description, int fill, Runnable click) {
        FrameLayout button = new FrameLayout(ui.context);
        button.addView(icon, new FrameLayout.LayoutParams(ui.dp(19), ui.dp(19), Gravity.CENTER));
        button.setContentDescription(description);
        ui.clickable(button, fill, 20, click);
        button.setBackground(new InsetDrawable(ui.focusSurface(fill, 20), ui.dp(4)));
        return button;
    }

    private LinearLayout.LayoutParams actionSize() {
        return new LinearLayout.LayoutParams(ui.touchTarget(), ui.touchTarget());
    }

    private void toggleMode() {
        focusTypingOnToggle = hold.isShown();
        controller.toggleVoiceInput();
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
        modeIcon.setImageResource(voice ? R.drawable.ic_lucide_keyboard : R.drawable.ic_lucide_mic);
        voiceToggle.setEnabled(!s.holdRecording);
        attach.setEnabled(!s.holdRecording);
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
        send.setEnabled(!s.holdRecording && s.imports.isEmpty());
        send.setAlpha(send.isEnabled() ? 1f : .4f);
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
        updateRecording(s);
        if (focusTypingOnToggle && !voice) {
            focusTypingOnToggle = false;
            if (input.isShown()) focus();
        }
    }

    private void updateRecording(PocketController.Snapshot s) {
        if (s == null) return;
        hold.update(s);
        boolean touching = hold.touchHolding(s);
        recording.update(s, touching, touching && hold.cancelArmed());
    }

    void focus() {
        input.requestFocus();
        ((android.view.inputmethod.InputMethodManager)
                        ui.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    void hidden() {
        focusTypingOnToggle = false;
        if (hold != null) hold.cancel();
        if (input != null) ui.hideKeyboard(input);
    }

    void close() {
        hidden();
        images.close();
    }
}
