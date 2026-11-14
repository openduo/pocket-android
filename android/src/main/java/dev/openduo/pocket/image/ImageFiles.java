// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.ImportRead;
import dev.openduo.pocket.core.ImportRoute;

import java.io.*;

/**
 * Shared EXIF-aware decoding. Import conversion preserves resolution; previews request a sample.
 */
public final class ImageFiles {
    private ImageFiles() {}

    public static Bitmap decode(File file, int limit) throws IOException {
        if (limit <= 0) throw new IllegalArgumentException("Invalid image edge bound");
        BitmapFactory.Options options = bounds(file);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > limit)
            options.inSampleSize *= 2;
        return pixels(file, options);
    }

    public static boolean converts(Attachment attachment, ImportRoute.Kind kind) {
        return kind == ImportRoute.Kind.CAMERA
                || kind == ImportRoute.Kind.MEDIA
                        && attachment.mime.startsWith("image/")
                        && !attachment.inlineImage();
    }

    /**
     * Unsupported native formats or a full-resolution decode beyond the memory budget stay files.
     */
    public static Attachment normalize(Attachment source, ImportRoute.Kind kind, long limit)
            throws IOException {
        return normalize(source, kind, limit, new ImportRead());
    }

    public static Attachment normalize(
            Attachment source, ImportRoute.Kind kind, long limit, ImportRead read)
            throws IOException {
        read.check();
        if (!converts(source, kind)) return source;
        File file = new File(source.localPath);
        Bitmap bitmap;
        try {
            BitmapFactory.Options options = bounds(file);
            long budget = Math.min(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3);
            if ((long) options.outWidth * options.outHeight > budget / 8) return source;
            bitmap = pixels(file, options);
        } catch (IOException | OutOfMemoryError unsupported) {
            return source;
        }
        File jpeg = null;
        try {
            jpeg = File.createTempFile("image-", ".jpg", file.getParentFile());
            try (FileOutputStream output = new FileOutputStream(jpeg);
                    OutputStream bounded =
                            new FilterOutputStream(output) {
                                private long written;

                                @Override
                                public void write(int b) throws IOException {
                                    read.check();
                                    if (++written > limit) throw new IOException("文件超过大小上限");
                                    out.write(b);
                                }

                                @Override
                                public void write(byte[] bytes, int start, int count)
                                        throws IOException {
                                    read.check();
                                    if ((written += count) > limit)
                                        throw new IOException("文件超过大小上限");
                                    out.write(bytes, start, count);
                                }
                            }) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bounded))
                    throw new IOException("Cannot encode imported image");
                bounded.flush();
                output.getFD().sync();
            }
            read.check();
            if (!jpeg.renameTo(file)) throw new IOException("Cannot admit converted image");
            String name = source.name;
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            return new Attachment(
                    name + ".jpg",
                    "image/jpeg",
                    source.localPath,
                    "",
                    "",
                    file.length(),
                    Attachment.UPLOADING);
        } finally {
            bitmap.recycle();
            if (jpeg != null) jpeg.delete();
        }
    }

    private static BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private static Bitmap pixels(File file, BitmapFactory.Options options) throws IOException {
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) throw new IOException("Cannot decode image");
        Matrix matrix = new Matrix();
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation =
                    new ExifInterface(file.getPath())
                            .getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
        }
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270);
                break;
            default:
                return decoded;
        }
        try {
            Bitmap upright =
                    Bitmap.createBitmap(
                            decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (upright != decoded) decoded.recycle();
            return upright;
        } catch (RuntimeException | OutOfMemoryError failure) {
            decoded.recycle();
            throw failure;
        }
    }
}
