// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui;

import android.graphics.Rect;
import android.view.Display;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Android 8 immersive mode needs a measured IME inset; adjustResize alone is insufficient. */
public final class InsetsFrame extends FrameLayout {
    private final Ui ui;
    private final Rect visible = new Rect();
    private final android.graphics.Paint systemBarPaint = new android.graphics.Paint();
    private final int[] position = new int[2];
    private final android.graphics.Point displaySize = new android.graphics.Point();
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
        setPadding(0, id == 0 ? ui.dp(24) : getResources().getDimensionPixelSize(id), 0, 0);
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
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        super.onDetachedFromWindow();
    }

    private void measureKeyboard() {
        Display display = getDisplay();
        if (!isAttachedToWindow() || display == null) return;
        getWindowVisibleDisplayFrame(visible);
        getLocationOnScreen(position);
        int obscured = Math.max(0, position[1] + getHeight() - visible.bottom);
        display.getRealSize(displaySize);
        boolean shown = displaySize.y - visible.bottom > ui.dp(100);
        int inset = obscured;
        if (getPaddingBottom() != inset) setPadding(0, getPaddingTop(), 0, inset);
        if (shown != keyboardShown) {
            keyboardShown = shown;
            if (keyboardListener != null) keyboardListener.accept(shown);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        setPadding(0, Math.max(getPaddingTop(), insets.getStableInsetTop()), 0, getPaddingBottom());
        return insets;
    }
}
