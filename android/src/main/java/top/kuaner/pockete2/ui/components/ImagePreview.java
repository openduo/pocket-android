// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.ui.Ui;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** A full-screen, zoomable preview owns its bitmap and releases it on dismissal. */
public final class ImagePreview {
    private ImagePreview() {}

    public static Dialog show(Ui ui, File file, Runnable share) {
        Dialog dialog = new Dialog(ui.context, android.R.style.Theme_Material_NoActionBar);
        AtomicBoolean dismissed = new AtomicBoolean();
        LinearLayout body = ui.column();
        body.setBackgroundColor(ui.palette.background);
        LinearLayout toolbar = ui.row();
        toolbar.addView(
                ui.iconButton("chevron_down", "关闭图片", ui.palette.secondary, dialog::dismiss));
        TextView title = ui.label("图片", 16, ui.palette.text, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(ui.iconButton("share_2", "分享图片", ui.palette.accent, share));
        body.addView(toolbar);
        ZoomImageView image = new ZoomImageView(ui.context);
        body.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = ui.label("双击或双指缩放 · 拖动查看", 11, ui.palette.subtle, false);
        hint.setGravity(android.view.Gravity.CENTER);
        ui.inset(hint, 12, 10);
        body.addView(hint);
        Bitmap[] owned = {null};
        dialog.setOnDismissListener(
                ignored -> {
                    dismissed.set(true);
                    image.setImageBitmap(null);
                    if (owned[0] != null) {
                        owned[0].recycle();
                        owned[0] = null;
                    }
                });
        dialog.setContentView(body);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        dialog.getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(
                        () -> {
                            Bitmap bitmap = null;
                            try {
                                bitmap = ImageBitmaps.decode(file, 2048);
                            } catch (Exception | OutOfMemoryError ignored) {
                            }
                            Bitmap decoded = bitmap;
                            main.post(
                                    () -> {
                                        if (dismissed.get()) {
                                            if (decoded != null) decoded.recycle();
                                            return;
                                        }
                                        if (decoded == null) {
                                            Ui.updateText(hint, "图片读取失败，可以分享后在其他应用打开");
                                            return;
                                        }
                                        owned[0] = decoded;
                                        image.setImageBitmap(decoded);
                                    });
                        },
                        "Pocket E2 image preview")
                .start();
        return dialog;
    }
}
