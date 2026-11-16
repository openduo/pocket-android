// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.content.Context;
import android.widget.LinearLayout;

/** Uses the available width up to a readable maximum, so form controls never shrink to a word. */
public final class BoundedColumn extends LinearLayout {
    private final int maximum;

    public BoundedColumn(Context context, int maximum) {
        super(context);
        setOrientation(VERTICAL);
        this.maximum = maximum;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED)
            widthSpec =
                    MeasureSpec.makeMeasureSpec(
                            Math.min(maximum, MeasureSpec.getSize(widthSpec)), MeasureSpec.EXACTLY);
        super.onMeasure(widthSpec, heightSpec);
    }
}
