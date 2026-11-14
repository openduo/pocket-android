// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the editor reachable when an IME or a short window leaves little vertical room. */
public final class AdaptiveColumn extends LinearLayout {
    private final Ui ui;
    private View chrome;
    private final Map<EditText, Integer> editors = new LinkedHashMap<>();

    AdaptiveColumn(Ui ui) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
    }

    public void chrome(View view) {
        chrome = view;
    }

    public void editor(EditText editor, int maximumLines) {
        editors.put(editor, maximumLines);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean condensed =
                MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED
                        && MeasureSpec.getSize(heightSpec)
                                < ui.dp(ui.metrics.chromeMinWindowHeightDp());
        if (chrome != null) chrome.setVisibility(condensed ? GONE : VISIBLE);
        for (Map.Entry<EditText, Integer> entry : editors.entrySet()) {
            int lines = condensed ? 1 : entry.getValue();
            if (entry.getKey().getMaxLines() != lines) {
                entry.getKey().setMaxLines(lines);
                entry.getKey().setHorizontallyScrolling(condensed);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
