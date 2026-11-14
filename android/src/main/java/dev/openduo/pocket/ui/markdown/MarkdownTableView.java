// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Adapted from the local E2Studio application.
package dev.openduo.pocket.ui.markdown;

import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.ui.Ui;

import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.node.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Two-column answers wrap to the E2 width; wider tables keep readable columns and pan locally. */
final class MarkdownTableView extends HorizontalScrollView {
    private final Ui ui;
    private final List<LinearLayout> rows = new ArrayList<>();
    private final int[] preferred;

    MarkdownTableView(Ui ui, Node node, Function<Node, CharSequence> inline) {
        super(ui.context);
        this.ui = ui;
        setContentDescription(ui.t("横向滚动表格"));
        setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        setPadding(0, 0, 0, ui.dp(3));
        LinearLayout table = ui.column();
        table.setBackground(ui.surface(ui.palette.surface, 8));
        int count = 0;
        for (Node cell = node.getFirstChild().getFirstChild().getFirstChild();
                cell != null;
                cell = cell.getNext()) count++;
        preferred = new int[count];
        java.util.Arrays.fill(preferred, ui.markdownTableCellMinWidth());
        for (Node section = node.getFirstChild(); section != null; section = section.getNext())
            for (Node line = section.getFirstChild(); line != null; line = line.getNext()) {
                LinearLayout row = ui.row();
                row.setGravity(Gravity.TOP);
                if (rows.size() % 2 == 1) row.setBackgroundColor(ui.palette.raised);
                int col = 0;
                for (Node value = line.getFirstChild(); value != null; value = value.getNext()) {
                    TableCell cell = (TableCell) value;
                    CharSequence content = inline.apply(cell);
                    TextView text = ui.label("", 13, ui.palette.text, cell.isHeader());
                    text.setLineSpacing(ui.dp(3), 1);
                    text.setText(content);
                    text.setLinkTextColor(ui.palette.accent);
                    if (content instanceof Spanned
                            && ((Spanned) content)
                                            .getSpans(0, content.length(), URLSpan.class)
                                            .length
                                    > 0) text.setMovementMethod(LinkMovementMethod.getInstance());
                    ui.inset(text, 8, 8);
                    if (cell.getAlignment() == TableCell.Alignment.CENTER)
                        text.setGravity(Gravity.CENTER);
                    else if (cell.getAlignment() == TableCell.Alignment.RIGHT)
                        text.setGravity(Gravity.END);
                    preferred[col] =
                            Math.max(
                                    preferred[col],
                                    ui.dp(
                                            Math.min(
                                                    ui.metrics.markdownTableColumnMaxDp(),
                                                    24 + content.length() * 10)));
                    row.addView(text, new LinearLayout.LayoutParams(preferred[col++], -2));
                }
                rows.add(row);
                table.addView(row);
            }
        addView(table);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int total = 0;
        for (int width : preferred) total += width;
        int[] widths = preferred.clone();
        if (preferred.length <= 2
                && available >= ui.markdownTableFitThreshold()
                && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            if (preferred.length == 1) widths[0] = available;
            else {
                widths[0] =
                        Math.max(
                                ui.markdownTableColumnMinWidth(),
                                Math.min(
                                        available - ui.markdownTableColumnMinWidth(),
                                        Math.round(available * preferred[0] / (float) total)));
                widths[1] = available - widths[0];
            }
        }
        for (LinearLayout row : rows)
            for (int col = 0; col < row.getChildCount(); col++)
                row.getChildAt(col).getLayoutParams().width = widths[col];
        super.onMeasure(widthSpec, heightSpec);
    }
}
