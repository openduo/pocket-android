// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import top.kuaner.pockete2.sight.PreviewFrame;
import top.kuaner.pockete2.ui.Ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** The rear camera's bounded viewfinder; it never opens a second camera or records its frames. */
public final class SightViewfinder extends View {
    private final Supplier<PreviewFrame> frames;
    private final Consumer<Boolean> visible;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private PreviewFrame displayed;
    private Bitmap bitmap;
    private int[] pixels;

    public SightViewfinder(Ui ui, Supplier<PreviewFrame> frames, Consumer<Boolean> visible) {
        super(ui.context);
        this.frames = frames;
        this.visible = visible;
        setContentDescription(ui.t("后置摄像头画面"));
        setBackground(ui.surface(ui.palette.raised, 20));
        setClipToOutline(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PreviewFrame frame = frames.get();
        if (frame == null) releaseBitmap();
        else if (frame != displayed) {
            if (bitmap == null
                    || bitmap.getWidth() != frame.width
                    || bitmap.getHeight() != frame.height) {
                releaseBitmap();
                bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
                pixels = new int[frame.width * frame.height];
            }
            frame.copyPixels(pixels);
            bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height);
            displayed = frame;
        }
        if (bitmap != null) {
            float scale =
                    Math.max(
                            getWidth() / (float) bitmap.getWidth(),
                            getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            destination.set(
                    (getWidth() - width) / 2,
                    (getHeight() - height) / 2,
                    (getWidth() + width) / 2,
                    (getHeight() + height) / 2);
            canvas.drawBitmap(bitmap, null, destination, paint);
        }
        if (isShown()) postInvalidateDelayed(200);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        visible.accept(isShown());
        invalidate();
    }

    @Override
    protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (visible != null) {
            visible.accept(isShown());
            if (!isShown()) releaseBitmap();
            else invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        visible.accept(false);
        releaseBitmap();
        super.onDetachedFromWindow();
    }

    private void releaseBitmap() {
        displayed = null;
        pixels = null;
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
    }
}
