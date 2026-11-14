// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Adapted from the local E2Studio application.
package dev.openduo.pocket.text;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One CommonMark dialect for presentation and speech; no Android or cloud dependencies. */
public final class Markdown {
    private static final Parser PARSER =
            Parser.builder()
                    .extensions(
                            Arrays.<Extension>asList(
                                    TablesExtension.create(), StrikethroughExtension.create()))
                    .build();

    public static Node parse(String source) {
        return PARSER.parse(source == null ? "" : source);
    }

    /** Passport text follows upstream plainText: preserve code/HTML and flatten table rows. */
    public static String plain(String source) {
        List<String> lines = new ArrayList<>();
        blocks(parse(source), lines);
        return String.join("\n", lines);
    }

    private static String trimNewline(String value) {
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static void blocks(Node node, List<String> lines) {
        if (node instanceof ThematicBreak) return;
        if (node instanceof FencedCodeBlock) {
            lines.add(trimNewline(((FencedCodeBlock) node).getLiteral()));
        } else if (node instanceof IndentedCodeBlock) {
            lines.add(trimNewline(((IndentedCodeBlock) node).getLiteral()));
        } else if (node instanceof HtmlBlock) {
            lines.add(trimNewline(((HtmlBlock) node).getLiteral()));
        } else if (node instanceof Paragraph || node instanceof Heading) {
            StringBuilder text = new StringBuilder();
            inlines(node, text);
            lines.add(text.toString());
        } else if (node instanceof TableBlock) {
            List<List<String>> rows = new ArrayList<>();
            tableRows(node, rows);
            int width = 0;
            for (List<String> row : rows) width = Math.max(width, row.size());
            for (List<String> row : rows) {
                while (row.size() < width) row.add("");
                lines.add(String.join(" · ", row));
            }
        } else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                blocks(child, lines);
        }
    }

    private static void tableRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow) {
            List<String> cells = new ArrayList<>();
            for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext()) {
                StringBuilder text = new StringBuilder();
                inlines(cell, text);
                cells.add(text.toString());
            }
            rows.add(cells);
        } else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                tableRows(child, rows);
        }
    }

    private static void inlines(Node node, StringBuilder out) {
        if (node instanceof Text) out.append(((Text) node).getLiteral());
        else if (node instanceof Code) out.append(((Code) node).getLiteral());
        else if (node instanceof HtmlInline) out.append(((HtmlInline) node).getLiteral());
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) out.append('\n');
        else {
            int start = out.length();
            if (node instanceof Image) out.append("🖼 ");
            int content = out.length();
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                inlines(child, out);
            if (out.length() == content) {
                if (node instanceof Image) out.append(((Image) node).getDestination());
                else if (node instanceof Link) out.append(((Link) node).getDestination());
            }
            if (node instanceof Paragraph && node.getParent() instanceof ListItem) {
                String value = out.substring(start);
                if (value.matches("^\\[[ xX]\\] .*")) out.replace(start, start + 4, "");
            }
        }
    }

    /** Speak labels and prose, omitting URLs, markup, raw HTML and fenced/indented source code. */
    public static String spoken(String source) {
        StringBuilder output = new StringBuilder();
        prose(parse(source), output);
        return output.toString().replaceAll("[ \\t]+", " ").replaceAll(" *\\n+ *", "\n").trim();
    }

    private static void prose(Node node, StringBuilder out) {
        if (node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock
                || node instanceof HtmlBlock
                || node instanceof HtmlInline
                || node instanceof ThematicBreak) return;
        if (node instanceof Text) out.append(((Text) node).getLiteral());
        else if (node instanceof Code) out.append(((Code) node).getLiteral());
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) out.append('\n');
        else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                prose(child, out);
            if (node instanceof Block) out.append('\n');
            else if (node instanceof TableCell) out.append(' ');
        }
    }

    private Markdown() {}
}
