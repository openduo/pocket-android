// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Window-local system/IME insets, with a measured fallback for Android 8 immersive firmware. */
public final class InsetsFrame extends FrameLayout {
    private final Ui ui;
    private final Rect visible = new Rect();
    private final android.graphics.Paint systemBarPaint = new android.graphics.Paint();
    private final int[] position = new int[2];
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::measureKeyboard;
    private java.util.function.Consumer<Boolean> keyboardListener;
    private boolean keyboardShown;

    public void keyboardListener(java.util.function.Consumer<Boolean> listener) {
        keyboardListener = listener;
    }

    public InsetsFrame(Ui ui) {
        super(ui.context);
        this.ui = ui;
        setWillNotDraw(false);
        systemBarPaint.setColor(ui.systemBarBackground);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        setPadding(
                0,
                id == 0
                        ? ui.dp(ui.metrics.statusBarFallbackDp())
                        : getResources().getDimensionPixelSize(id),
                0,
                0);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getPaddingTop(), systemBarPaint);
        canvas.drawRect(
                0, getHeight() - getPaddingBottom(), getWidth(), getHeight(), systemBarPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT < 30)
            getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        requestApplyInsets();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT < 30)
            getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        super.onDetachedFromWindow();
    }

    private void measureKeyboard() {
        if (!isAttachedToWindow()) return;
        getWindowVisibleDisplayFrame(visible);
        getLocationOnScreen(position);
        int obscured = Math.max(0, position[1] + getHeight() - visible.bottom);
        boolean shown = obscured > ui.dp(ui.metrics.keyboardThresholdDp());
        if (getPaddingBottom() != obscured)
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), obscured);
        publishKeyboard(shown);
    }

    private void publishKeyboard(boolean shown) {
        if (shown != keyboardShown) {
            keyboardShown = shown;
            if (keyboardListener != null) keyboardListener.accept(shown);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                    insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets navigation =
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            setPadding(
                    bars.left,
                    bars.top,
                    bars.right,
                    Math.max(Math.max(bars.bottom, navigation.bottom), ime.bottom));
            publishKeyboard(insets.isVisible(WindowInsets.Type.ime()));
        } else
            setPadding(
                    insets.getStableInsetLeft(),
                    insets.getStableInsetTop() > 0 ? insets.getStableInsetTop() : getPaddingTop(),
                    insets.getStableInsetRight(),
                    getPaddingBottom());
        return insets;
    }
}
