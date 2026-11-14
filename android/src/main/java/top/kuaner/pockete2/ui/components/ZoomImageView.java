// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.ImageView;

/** Fit, pinch, pan and double-tap zoom with bounds suitable for a narrow portrait viewport. */
public final class ZoomImageView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scales;
    private final GestureDetector gestures;
    private float fitted = 1, zoom = 1;
    private int imageWidth, imageHeight;

    public ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setContentDescription(top.kuaner.pockete2.text.AppText.get(context).t("图片，可双击或双指缩放，拖动查看"));
        top.kuaner.pockete2.ui.ViewportNavigation.attach(
                this,
                new top.kuaner.pockete2.ui.ViewportNavigation.Target() {
                    public void pan(int horizontal, int vertical) {
                        transform.postTranslate(
                                -horizontal * getWidth() * .25f, -vertical * getHeight() * .25f);
                        constrain();
                    }

                    public void zoom(int step) {
                        scale(
                                zoom * (step > 0 ? 1.5f : 1 / 1.5f),
                                getWidth() / 2f,
                                getHeight() / 2f);
                    }
                });
        scales =
                new ScaleGestureDetector(
                        context,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {
                                scale(
                                        zoom * detector.getScaleFactor(),
                                        detector.getFocusX(),
                                        detector.getFocusY());
                                return true;
                            }
                        });
        gestures =
                new GestureDetector(
                        context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onScroll(
                                    MotionEvent first, MotionEvent second, float dx, float dy) {
                                if (!scales.isInProgress()) {
                                    transform.postTranslate(-dx, -dy);
                                    constrain();
                                }
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent event) {
                                scale(
                                        zoom > fitted * 1.5f ? fitted : fitted * 3,
                                        event.getX(),
                                        event.getY());
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent event) {
                                performClick();
                                return true;
                            }
                        });
    }

    @Override
    public void setImageBitmap(Bitmap bitmap) {
        imageWidth = bitmap == null ? 0 : bitmap.getWidth();
        imageHeight = bitmap == null ? 0 : bitmap.getHeight();
        super.setImageBitmap(bitmap);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    private void fit() {
        if (imageWidth == 0 || getWidth() == 0 || getHeight() == 0) return;
        fitted = Math.min((float) getWidth() / imageWidth, (float) getHeight() / imageHeight);
        zoom = fitted;
        transform.setScale(fitted, fitted);
        constrain();
    }

    private void scale(float desired, float x, float y) {
        float next = Math.max(fitted, Math.min(fitted * 6, desired));
        transform.postScale(next / zoom, next / zoom, x, y);
        zoom = next;
        constrain();
    }

    private void constrain() {
        RectF rect = new RectF(0, 0, imageWidth, imageHeight);
        transform.mapRect(rect);
        float dx =
                rect.width() < getWidth()
                        ? (getWidth() - rect.width()) / 2 - rect.left
                        : rect.left > 0
                                ? -rect.left
                                : rect.right < getWidth() ? getWidth() - rect.right : 0;
        float dy =
                rect.height() < getHeight()
                        ? (getHeight() - rect.height()) / 2 - rect.top
                        : rect.top > 0
                                ? -rect.top
                                : rect.bottom < getHeight() ? getHeight() - rect.bottom : 0;
        transform.postTranslate(dx, dy);
        setImageMatrix(transform);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scales.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
