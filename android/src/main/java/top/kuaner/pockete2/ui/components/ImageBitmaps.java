// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.components;

import android.graphics.Bitmap;

import top.kuaner.pockete2.image.ImageFiles;

import java.io.File;
import java.io.IOException;

/** Preview calls use the same EXIF decoder as imported full-resolution photos. */
public final class ImageBitmaps {
    private ImageBitmaps() {}

    public static Bitmap decode(File file, int limit) throws IOException {
        return ImageFiles.decode(file, limit);
    }
}
