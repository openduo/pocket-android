// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.ui;

import android.content.Context;
import android.widget.LinearLayout;

/** Bounds a message or form's line length without shrinking its surrounding page. */
public final class ReadableColumn extends LinearLayout {
    private final int maximum;

    public ReadableColumn(Context context, int maximum) {
        super(context);
        setOrientation(VERTICAL);
        this.maximum = maximum;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED)
            widthSpec =
                    MeasureSpec.makeMeasureSpec(
                            Math.min(maximum, MeasureSpec.getSize(widthSpec)), MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, heightSpec);
    }
}
