// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Adapted from the local E2Studio application.
package dev.openduo.pocket.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;

/** Keeps a reading position through layout, following new content only from the bottom. */
public final class ScrollAnchor implements ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    private final ScrollView view;
    private boolean pending;
    private boolean wasAtBottom;
    private boolean follow;
    private boolean touching;
    private int offset;
    private View start;

    public ScrollAnchor(ScrollView view) {
        this.view = view;
        view.getViewTreeObserver().addOnPreDrawListener(this);
        view.setOnTouchListener(
                (unused, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touching = true;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        touching = false;
                    pending = false;
                    start = null;
                    return false;
                });
    }

    public void capture(boolean followNewContent) {
        if (!pending) {
            offset = view.getScrollY();
            wasAtBottom = !view.canScrollVertically(1);
            follow = false;
            pending = true;
        }
        follow |= followNewContent && wasAtBottom && !touching;
    }

    /**
     * Completed long answers should open at their beginning when the reader was following along.
     */
    public void startAt(View content) {
        if (pending && wasAtBottom && !touching) {
            start = content;
            follow = true;
        }
    }

    /** An explicit submission follows the new message even when it began from older content. */
    public void toBottom() {
        capture(false);
        start = null;
        follow = true;
    }

    public void toTop() {
        pending = true;
        offset = 0;
        follow = false;
        start = null;
    }

    @Override
    public boolean onPreDraw() {
        if (!pending) return true;
        pending = false;
        int destination = offset;
        if (follow && start != null) {
            destination = start.getTop();
            android.view.ViewParent parent = start.getParent();
            while (parent instanceof View && parent != view) {
                View ancestor = (View) parent;
                destination += ancestor.getTop() - ancestor.getScrollY();
                parent = ancestor.getParent();
            }
        } else if (follow && view.getChildCount() > 0) {
            destination =
                    Math.max(
                            0,
                            view.getChildAt(0).getHeight()
                                    - view.getHeight()
                                    + view.getPaddingTop()
                                    + view.getPaddingBottom());
        }
        // fullScroll also moves keyboard focus, which can pull the viewport back down later.
        view.scrollTo(0, destination);
        start = null;
        return true;
    }

    @Override
    public void close() {
        if (view.getViewTreeObserver().isAlive())
            view.getViewTreeObserver().removeOnPreDrawListener(this);
        view.setOnTouchListener(null);
        pending = false;
        start = null;
    }
}
