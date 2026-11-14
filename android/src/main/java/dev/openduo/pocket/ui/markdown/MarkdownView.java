// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Adapted from the local E2Studio application.
package dev.openduo.pocket.ui.markdown;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.openduo.pocket.text.Markdown;
import dev.openduo.pocket.ui.Ui;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;

/** Native, theme-aware Markdown with wrapping code and locally panning wide tables. */
public final class MarkdownView extends LinearLayout {
    private final Ui ui;

    public MarkdownView(Ui ui, String source) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
        blocks(this, Markdown.parse(source), 0);
    }

    private void blocks(LinearLayout target, Node parent, int depth) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext())
            block(target, node, depth);
    }

    private void block(LinearLayout target, Node node, int depth) {
        if (node instanceof Paragraph || node instanceof Heading) {
            int level = node instanceof Heading ? ((Heading) node).getLevel() : 0;
            TextView text = text(level == 0 ? 16 : level == 1 ? 22 : level == 2 ? 20 : 17);
            if (level > 0) text.setTypeface(Typeface.DEFAULT_BOLD);
            SpannableStringBuilder spans = new SpannableStringBuilder();
            inline(node, spans);
            text.setText(spans);
            if (spans.getSpans(0, spans.length(), URLSpan.class).length > 0)
                text.setMovementMethod(LinkMovementMethod.getInstance());
            add(target, text, level > 0 ? 12 : 7);
        } else if (node instanceof ListBlock) {
            int number =
                    node instanceof OrderedList ? ((OrderedList) node).getMarkerStartNumber() : 0;
            for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                LinearLayout row = ui.row();
                row.setGravity(android.view.Gravity.TOP);
                TextView bullet = text(15);
                bullet.setText(node instanceof OrderedList ? number++ + "." : "•");
                LinearLayout.LayoutParams marker = new LinearLayout.LayoutParams(-2, -2);
                marker.rightMargin = ui.dp(6);
                marker.topMargin = ui.dp(7);
                row.addView(bullet, marker);
                LinearLayout body = ui.column();
                row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
                blocks(body, item, depth + 1);
                add(target, row, 0);
            }
        } else if (node instanceof BlockQuote) {
            LinearLayout quote = ui.column();
            ui.inset(quote, 8, 4);
            quote.setBackground(ui.surface(ui.palette.surface, 8));
            blocks(quote, node, depth + 1);
            add(target, quote, 9);
        } else if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            String code =
                    node instanceof FencedCodeBlock
                            ? ((FencedCodeBlock) node).getLiteral()
                            : ((IndentedCodeBlock) node).getLiteral();
            LinearLayout box = ui.column();
            box.setBackground(ui.surface(ui.palette.surface, 9));
            ui.inset(box, 8, 8);
            if (node instanceof FencedCodeBlock && !((FencedCodeBlock) node).getInfo().isEmpty())
                box.addView(
                        ui.label(
                                ((FencedCodeBlock) node).getInfo(),
                                11,
                                ui.palette.secondary,
                                false));
            TextView text = text(13);
            text.setTypeface(Typeface.MONOSPACE);
            text.setText(code.endsWith("\n") ? code.substring(0, code.length() - 1) : code);
            text.setContentDescription(ui.t("代码块"));
            text.setHorizontallyScrolling(false);
            ui.add(box, text, 4);
            add(target, box, 10);
        } else if (node instanceof TableBlock) {
            add(
                    target,
                    new MarkdownTableView(
                            ui,
                            node,
                            cell -> {
                                SpannableStringBuilder content = new SpannableStringBuilder();
                                inline(cell, content);
                                return content;
                            }),
                    10);
        } else if (node instanceof ThematicBreak) {
            View line = ui.divider();
            add(target, line, 12);
        } else if (node instanceof HtmlBlock) {
            // Raw HTML is text; it never becomes a WebView or executable content.
            TextView text = text(14);
            text.setText(((HtmlBlock) node).getLiteral());
            add(target, text, 7);
        } else blocks(target, node, depth);
    }

    private void inline(Node parent, SpannableStringBuilder out) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            int start = out.length();
            if (node instanceof Text) out.append(((Text) node).getLiteral());
            else if (node instanceof Code) out.append(((Code) node).getLiteral());
            else if (node instanceof SoftLineBreak) out.append(' ');
            else if (node instanceof HardLineBreak) out.append('\n');
            else if (node instanceof Image) {
                out.append(ui.t("[图片："));
                inline(node, out);
                out.append(']');
            } else if (!(node instanceof HtmlInline)) inline(node, out);
            int end = out.length();
            if (end == start) continue;
            if (node instanceof StrongEmphasis) span(out, new StyleSpan(Typeface.BOLD), start, end);
            if (node instanceof Emphasis) span(out, new StyleSpan(Typeface.ITALIC), start, end);
            if (node instanceof Strikethrough) span(out, new StrikethroughSpan(), start, end);
            if (node instanceof Code) {
                span(out, new TypefaceSpan("monospace"), start, end);
                span(out, new BackgroundColorSpan(ui.palette.surface), start, end);
            }
            if (node instanceof Link) {
                String url = ((Link) node).getDestination();
                if (url.matches("(?i)^(https?://|mailto:).+"))
                    span(out, new SafeLinkSpan(url), start, end);
            }
        }
    }

    private TextView text(float size) {
        TextView text = ui.label("", size, ui.palette.text, false);
        text.setLineSpacing(ui.dp(3), 1);
        text.setLinkTextColor(ui.palette.accent);
        return text;
    }

    private void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams p =
                view.getLayoutParams() instanceof LinearLayout.LayoutParams
                        ? (LinearLayout.LayoutParams) view.getLayoutParams()
                        : new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = parent.getChildCount() == 0 ? 0 : ui.dp(gap);
        parent.addView(view, p);
    }

    private static void span(SpannableStringBuilder out, Object span, int start, int end) {
        out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private final class SafeLinkSpan extends URLSpan {
        SafeLinkSpan(String url) {
            super(url);
        }

        @Override
        public void onClick(View widget) {
            try {
                super.onClick(widget);
            } catch (android.content.ActivityNotFoundException ignored) {
                android.widget.Toast.makeText(
                                widget.getContext(),
                                ui.t("没有可打开链接的应用"),
                                android.widget.Toast.LENGTH_SHORT)
                        .show();
            }
        }
    }
}
