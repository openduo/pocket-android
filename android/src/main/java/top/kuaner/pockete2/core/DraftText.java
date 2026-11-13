// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

/** Reconciles a native editor change with content appended before its state acknowledgement. */
public final class DraftText {
    private DraftText() {}

    public static String append(String draft, String incoming) {
        String text = incoming == null ? "" : incoming.trim();
        return text.isEmpty() ? draft : draft.isEmpty() ? text : draft + "\n" + text;
    }

    public static String edit(String current, String before, String after) {
        if (current.equals(before)) return after;
        if (before.isEmpty()) return append(after, current);
        if (current.startsWith(before + "\n"))
            return append(after, current.substring(before.length() + 1));
        return after;
    }
}
