// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Unicode 16 extended grapheme boundaries (UAX #29), identical across supported Android APIs. */
public final class Graphemes {
    private Graphemes() {}

    public static List<Integer> boundaries(String text) {
        List<Integer> ends = new ArrayList<>();
        ends.add(0);
        int previous = -1, regional = 0, conjunct = 0;
        boolean pictExtend = false, zwjAfterPict = false;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            int property = GraphemeData.property(point);
            int incb = GraphemeData.conjunct(point);
            boolean pict = GraphemeData.pictographic(point);
            boolean join;
            if (previous < 0) join = true;
            else if (previous == 1 && property == 2) join = true; // CR x LF
            else if (previous <= 3 && previous != 0 || property <= 3 && property != 0) join = false;
            else if (previous == 9
                    && (property == 9 || property == 10 || property == 12 || property == 13))
                join = true;
            else if ((previous == 12 || previous == 10) && (property == 10 || property == 11))
                join = true;
            else if ((previous == 13 || previous == 11) && property == 11) join = true;
            else if (property == 4 || property == 5 || property == 8 || previous == 7) join = true;
            else if (incb == 1 && conjunct == 2) join = true;
            else if (pict && previous == 5 && zwjAfterPict) join = true;
            else join = previous == 6 && property == 6 && regional % 2 == 1;
            if (!join) ends.add(at);
            regional = property == 6 ? regional + 1 : 0;
            if (incb == 1) conjunct = 1;
            else if (conjunct > 0 && incb == 3) conjunct = 2;
            else if (incb != 2) conjunct = 0;
            zwjAfterPict = property == 5 && pictExtend;
            if (pict) pictExtend = true;
            else if (property != 4) pictExtend = false;
            previous = property;
            at += Character.charCount(point);
        }
        if (!text.isEmpty()) ends.add(text.length());
        return ends;
    }

    public static String fitUtf8(String text, int limit) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= limit) return text;
        int used = 0, start = 0;
        for (int end : boundaries(text)) {
            if (end == 0) continue;
            int bytes = text.substring(start, end).getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > limit - 3) break;
            used += bytes;
            start = end;
        }
        return text.substring(0, start) + "…";
    }

    /** Foundation whitespacesAndNewlines, including non-breaking spaces and NEXT LINE. */
    public static String trimmed(String text) {
        int begin = 0, end = text.length();
        while (begin < end && white(text.codePointAt(begin)))
            begin += Character.charCount(text.codePointAt(begin));
        while (end > begin && white(text.codePointBefore(end)))
            end -= Character.charCount(text.codePointBefore(end));
        return text.substring(begin, end);
    }

    private static boolean white(int point) {
        return point == 0x85 || Character.isWhitespace(point) || Character.isSpaceChar(point);
    }
}
