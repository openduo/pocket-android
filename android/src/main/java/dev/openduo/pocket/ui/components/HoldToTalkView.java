// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;

/** Touch, keyboard and accessibility controls share the controller's single capture owner. */
public final class HoldToTalkView extends LinearLayout {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final TextView label;
    private boolean held, cancel, live;
    private boolean tapGesture, tapToSend;
    private final String owner;

    public HoldToTalkView(PageHost host, PocketController controller) {
        super(host.activity());
        this.host = host;
        this.controller = controller;
        ui = host.ui();
        owner = host.input().owner("voice-control:" + java.util.UUID.randomUUID());
        setGravity(Gravity.CENTER);
        setMinimumHeight(ui.touchTarget());
        setOrientation(VERTICAL);
        label = ui.label(ui.t("按住说话"), 14, ui.palette.text, true);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label);
        ui.clickable(
                this,
                ui.palette.raised,
                12,
                () ->
                        host.input()
                                .invoke(
                                        recording()
                                                ? dev.openduo.pocket.input.InputAction.SEND_VOICE
                                                : dev.openduo.pocket.input.InputAction.TOGGLE_VOICE,
                                        owner));
        setContentDescription(ui.t("按住说话，手指移出按钮可取消"));
    }

    private void begin() {
        if (held) return;
        held = true;
        cancel = false;
        host.input().begin(owner);
        update(controller.snapshot());
    }

    private void finish(boolean canceled) {
        tapToSend = false;
        tapGesture = false;
        held = false;
        host.input().end(owner, canceled);
        cancel = false;
        live = false;
        setScaleX(1);
        setScaleY(1);
        update(controller.snapshot());
    }

    public void cancel() {
        finish(true);
    }

    private boolean recording() {
        PocketController.Snapshot s = controller.snapshot();
        return s != null && s.holdRecording;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
        info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        recording() ? ui.t("发送录音") : ui.t("开始录音")));
        if (recording())
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_DISMISS, ui.t("取消录音")));
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS && recording()) {
            host.input().invoke(dev.openduo.pocket.input.InputAction.CANCEL_OR_STOP, owner);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && recording()) {
            host.input().invoke(dev.openduo.pocket.input.InputAction.CANCEL_OR_STOP, owner);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (((AccessibilityManager)
                        getContext()
                                .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE))
                .isTouchExplorationEnabled()) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (recording()) {
                    tapGesture = tapToSend = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    host.permission(
                            new String[] {android.Manifest.permission.RECORD_AUDIO},
                            () -> host.toast(ui.t("麦克风已允许，请再次按住说话")));
                    return true;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                begin();
                setScaleX(.98f);
                setScaleY(.98f);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tapGesture) {
                    tapToSend =
                            event.getX() >= 0
                                    && event.getX() <= getWidth()
                                    && event.getY() >= 0
                                    && event.getY() <= getHeight();
                    return true;
                }
                if (!held) return true;
                boolean outside =
                        event.getX() < 0
                                || event.getX() > getWidth()
                                || event.getY() < 0
                                || event.getY() > getHeight();
                if (cancel != outside) {
                    cancel = outside;
                    haptic(HapticFeedbackConstants.CLOCK_TICK);
                    update(controller.snapshot());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (tapGesture) {
                    boolean send = tapToSend;
                    tapGesture = false;
                    tapToSend = false;
                    if (send && recording())
                        host.input().invoke(dev.openduo.pocket.input.InputAction.SEND_VOICE, owner);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                finish(cancel);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (tapGesture) {
                    tapGesture = tapToSend = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                finish(true);
                return true;
            default:
                return true;
        }
    }

    public void update(PocketController.Snapshot s) {
        if (s == null) return;
        boolean own = s.holdRecording && owner.equals(s.holdOwner);
        if (own && s.holdLive && !live) {
            live = true;
            haptic(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        boolean touching = held && own;
        boolean armed = touching && cancel;
        boolean active = s.holdRecording;
        label.setText(
                active
                        ? armed
                                ? ui.t("松手取消")
                                : s.holdLive
                                        ? (touching ? ui.t("松开发送 · ") : ui.t("发送录音 · "))
                                                + duration(s.holdDurationMs)
                                        : ui.t("准备中…")
                        : ui.t("按住说话"));
        label.setTextColor(active && s.holdLive && !armed ? ui.palette.onAccent : ui.palette.text);
        setBackground(
                ui.focusSurface(
                        active
                                ? armed
                                        ? ui.palette.attentionTint
                                        : s.holdLive ? ui.palette.accent : ui.palette.selected
                                : ui.palette.raised,
                        12));
        setContentDescription(
                active
                        ? armed
                                ? ui.t("松手取消，移回按钮继续录音")
                                : s.holdLive
                                        ? touching ? ui.t("正在录音，松开发送") : ui.t("正在录音，再次激活发送")
                                        : ui.t("准备麦克风")
                        : ui.t("按住说话"));
    }

    public boolean cancelArmed() {
        return cancel;
    }

    private void haptic(int kind) {
        if (controller.settings.haptics())
            performHapticFeedback(kind, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancel();
    }

    public static String duration(long ms) {
        long sec = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    public static final class Meter extends View {
        private final Ui ui;
        private final PocketController controller;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Meter(Ui ui, PocketController controller) {
            super(ui.context);
            this.ui = ui;
            this.controller = controller;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float level = controller.level();
            paint.setColor(ui.palette.accent);
            paint.setStrokeWidth(ui.dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            int count = 27;
            float gap = getWidth() / (float) (count + 1), center = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                float taper = (float) Math.sin((i + 1) * Math.PI / (count + 1));
                float amplitude = ui.dp(2) + level * taper * getHeight() * .42f;
                canvas.drawLine(
                        gap * (i + 1),
                        center - amplitude,
                        gap * (i + 1),
                        center + amplitude,
                        paint);
            }
            if (isShown()) postInvalidateDelayed(50);
        }
    }
}
