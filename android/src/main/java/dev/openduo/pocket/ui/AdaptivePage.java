// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Centers a bounded page column without making page code depend on a physical display size. */
final class AdaptivePage extends FrameLayout {
    private final Ui ui;

    AdaptivePage(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setClipChildren(false);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int widthMode = MeasureSpec.getMode(widthSpec);
        int availableWidth = MeasureSpec.getSize(widthSpec);
        if (widthMode == MeasureSpec.UNSPECIFIED) availableWidth = ui.screenWidth();
        int contentWidth = Math.min(availableWidth, ui.dp(ui.metrics.contentMaxWidthDp()));
        int heightMode = MeasureSpec.getMode(heightSpec);
        int availableHeight = MeasureSpec.getSize(heightSpec);
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            int childHeightSpec =
                    getChildMeasureSpec(heightSpec, 0, child.getLayoutParams().height);
            child.measure(
                    MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                    childHeightSpec);
        }
        int measuredHeight =
                heightMode != MeasureSpec.EXACTLY && getChildCount() > 0
                        ? getChildAt(0).getMeasuredHeight()
                        : availableHeight;
        setMeasuredDimension(
                resolveSize(availableWidth, widthSpec), resolveSize(measuredHeight, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (getChildCount() == 0) return;
        View child = getChildAt(0);
        int width = child.getMeasuredWidth();
        int height = child.getMeasuredHeight();
        int x = (getMeasuredWidth() - width) / 2;
        child.layout(x, 0, x + width, height);
    }
}
