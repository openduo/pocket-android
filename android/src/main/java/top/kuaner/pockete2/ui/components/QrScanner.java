// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

import top.kuaner.pockete2.pairing.QrCapture;
import top.kuaner.pockete2.ui.Ui;

import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** A small-screen scan sheet with one pending preview bitmap, owned until dismissal. */
public final class QrScanner implements QrCapture.Listener {
    private final Dialog dialog;
    private final ImageView image;
    private final TextView hint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Bitmap> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(), dismissed = new AtomicBoolean();
    private final Consumer<String> result;
    private final QrCapture capture;
    private Bitmap displayed;

    public static Dialog show(Ui ui, Consumer<String> result) {
        return new QrScanner(ui, result).dialog;
    }

    private QrScanner(Ui ui, Consumer<String> result) {
        this.result = result;
        LinearLayout body = ui.column();
        image = new ImageView(ui.context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("房间二维码相机画面");
        body.addView(image, new LinearLayout.LayoutParams(-1, ui.dp(230)));
        hint = ui.label("对准房间管理页的配对二维码。扫码在本机完成，读取后可检查地址再连接。", 13, ui.palette.secondary, false);
        ui.inset(hint, 8, 12);
        body.addView(hint);
        dialog = Sheets.content(ui, "扫描房间二维码", body);
        capture = new QrCapture(ui.context, this);
        dialog.setOnDismissListener(
                ignored -> {
                    dismissed.set(true);
                    capture.close();
                    Bitmap leftover = pending.getAndSet(null);
                    if (leftover != null) leftover.recycle();
                    image.setImageDrawable(null);
                    if (displayed != null) {
                        displayed.recycle();
                        displayed = null;
                    }
                });
    }

    @Override
    public void preview(Bitmap bitmap) {
        if (dismissed.get()) {
            bitmap.recycle();
            return;
        }
        Bitmap previous = pending.getAndSet(bitmap);
        if (previous != null) previous.recycle();
        if (dismissed.get()) {
            Bitmap stale = pending.getAndSet(null);
            if (stale != null) stale.recycle();
            return;
        }
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) main.post(this::render);
    }

    private void render() {
        Bitmap bitmap = pending.getAndSet(null);
        if (bitmap != null) {
            if (dismissed.get()) bitmap.recycle();
            else {
                image.setImageBitmap(bitmap);
                if (displayed != null) displayed.recycle();
                displayed = bitmap;
            }
        }
        scheduled.set(false);
        if (pending.get() != null) schedule();
    }

    @Override
    public void code(String text) {
        main.post(
                () -> {
                    if (!dismissed.get()) {
                        dialog.dismiss();
                        result.accept(text);
                    }
                });
    }

    @Override
    public void failed(String reason) {
        main.post(
                () -> {
                    if (!dismissed.get()) hint.setText(reason);
                });
    }
}
