// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.input.InputAction;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;

/** Shared recording feedback above the stationary composer. */
public final class VoiceRecordingPanel extends FrameLayout {
    private final Ui ui;
    private final TextView status, hint;
    private final View actions;
    private final Indicator indicator;

    public VoiceRecordingPanel(PageHost host) {
        super(host.activity());
        ui = host.ui();
        setBackgroundColor(0x46000000);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout body = ui.column();
        ui.inset(body, 16, 8);
        body.setBackground(ui.surface(ui.palette.surface, 20));
        status = ui.label("", 23, ui.palette.text, true);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(ui.dp(32));
        body.addView(status);
        indicator = new Indicator(ui);
        body.addView(indicator, new LinearLayout.LayoutParams(-1, ui.dp(22)));

        // Swap the gesture hint and the keyboard actions in place to keep the timer steady.
        FrameLayout controls = new FrameLayout(ui.context);
        hint = ui.label("", 12, ui.palette.secondary, false);
        hint.setGravity(Gravity.CENTER);
        hint.setMinLines(2);
        controls.addView(hint, new LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout row = ui.row();
        View cancel =
                ui.button(
                        ui.t("取消录音"),
                        ui.palette.text,
                        ui.palette.raised,
                        () ->
                                host.input()
                                        .invoke(
                                                InputAction.CANCEL_OR_STOP,
                                                host.input().owner("voice-cancel")));
        View send =
                ui.button(
                        ui.t("发送录音"),
                        ui.palette.onAccent,
                        ui.palette.accent,
                        () ->
                                host.input()
                                        .invoke(
                                                InputAction.SEND_VOICE,
                                                host.input().owner("voice-send")));
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(0, -2, 1);
        sendSize.leftMargin = ui.dp(8);
        row.addView(send, sendSize);
        controls.addView(row, new LayoutParams(-1, -2, Gravity.CENTER));
        actions = row;
        body.addView(controls);

        LayoutParams panel = new LayoutParams(-1, -2, Gravity.BOTTOM);
        panel.setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(3));
        addView(ui.scrollFooter(body), panel);
        setVisibility(GONE);
    }

    public void update(PocketController.Snapshot s, boolean touching, boolean armed) {
        setVisibility(s.holdRecording ? VISIBLE : GONE);
        if (!s.holdRecording) return;
        Ui.updateText(
                status,
                armed
                        ? ui.t("松手取消")
                        : s.holdLive ? HoldToTalkView.duration(s.holdDurationMs) : ui.t("准备中…"));
        status.setTextColor(armed ? ui.palette.attention : ui.palette.text);
        indicator.show(s.holdLive, armed);
        Ui.updateText(hint, armed ? ui.t("松手取消，移回按钮继续录音") : ui.t("松开发送 · 移出按钮取消"));
        hint.setVisibility(touching ? VISIBLE : INVISIBLE);
        actions.setVisibility(touching ? INVISIBLE : VISIBLE);
    }

    /** A simple activity animation, independent of microphone amplitude. */
    private static final class Indicator extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean animate;

        Indicator(Ui ui) {
            super(ui.context);
            this.ui = ui;
            paint.setStrokeWidth(ui.dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(boolean live, boolean armed) {
            int color = armed ? ui.palette.attention : live ? ui.palette.accent : ui.palette.subtle;
            boolean moving = live && !armed;
            if (paint.getColor() == color && animate == moving) return;
            paint.setColor(color);
            animate = moving;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            double phase = SystemClock.uptimeMillis() / 180.0;
            float center = getHeight() / 2f;
            for (int i = 0; i < 5; i++) {
                float pulse = animate ? (float) (.5 + .5 * Math.sin(phase + i * .8)) : 0;
                float amplitude = ui.dp(2) + pulse * Math.max(0, center - ui.dp(4));
                float x = getWidth() / 2f + (i - 2) * ui.dp(6);
                canvas.drawLine(x, center - amplitude, x, center + amplitude, paint);
            }
            if (animate && isShown()) postInvalidateDelayed(50);
        }
    }
}
