// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Adapted from the local E2Studio application.
package top.kuaner.pockete2.text;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.Arrays;

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
