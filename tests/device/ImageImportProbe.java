// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;

import top.kuaner.pockete2.app.PocketFiles;
import top.kuaner.pockete2.core.*;

import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;

/** Native decoders/encoder, original-byte preservation and post-conversion room-size limits. */
final class ImageImportProbe {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    static int run(Context context) throws Exception {
        File shared = new File(context.getCacheDir(), "shared");
        shared.mkdirs();
        File source = File.createTempFile("qa-format-", ".bin", shared);
        File copy = File.createTempFile("qa-converted-", ".tmp", context.getCacheDir());
        AttachmentStore store = new AttachmentStore(context);
        try {
            byte[] bmp = bitmapBytes(320, 160);
            Files.write(source.toPath(), bmp);
            Uri uri = PocketFiles.uri(context, source, "原始 图像.bmp", "image/bmp");
            Attachment image = store.importFile(uri, copy, 4096, ImportRoute.Kind.MEDIA);
            check(
                    image.inlineImage()
                            && image.mime.equals("image/jpeg")
                            && image.name.equals("原始 图像.jpg")
                            && image.size <= 4096
                            && bmp.length > 4096
                            && image.size == copy.length(),
                    "non-inline shared images become JPEG at the channel boundary and the room"
                            + " limit applies to converted bytes");
            Bitmap decoded = BitmapFactory.decodeFile(copy.getPath());
            try {
                int color = decoded.getPixel(100, 70);
                check(
                        decoded.getWidth() == 320
                                && decoded.getHeight() == 160
                                && Math.abs(Color.green(color) - 137) < 10
                                && Math.abs(Color.red(color) - 59) < 10,
                        "JPEG conversion preserves full image dimensions and its actual colours");
            } finally {
                decoded.recycle();
            }
            Attachment file = store.importFile(uri, copy, 0, ImportRoute.Kind.FILES);
            check(
                    file.name.equals("原始 图像.bmp")
                            && file.mime.equals("image/bmp")
                            && Arrays.equals(Files.readAllBytes(copy.toPath()), bmp),
                    "ordinary file selection preserves the original image format and bytes like the"
                            + " upstream file importer");
            boolean oversized = false;
            try {
                store.importFile(uri, copy, 128, ImportRoute.Kind.MEDIA);
            } catch (IOException expected) {
                oversized = true;
            }
            check(
                    oversized
                            && !copy.exists()
                            && Arrays.equals(Files.readAllBytes(source.toPath()), bmp),
                    "an oversized encoded image cannot leave a partial chip or modify its provider"
                            + " source");
            Bitmap original = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888);
            original.eraseColor(0xff3b8971);
            try (FileOutputStream out = new FileOutputStream(source)) {
                original.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            ExifInterface exif = new ExifInterface(source.getPath());
            exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            exif.saveAttributes();
            byte[] jpeg = Files.readAllBytes(source.toPath());
            Attachment preserved =
                    store.importFile(
                            PocketFiles.uri(context, source, "already.jpg", "image/jpeg"),
                            copy,
                            0,
                            ImportRoute.Kind.MEDIA);
            check(
                    Arrays.equals(Files.readAllBytes(copy.toPath()), jpeg)
                            && preserved.name.equals("already.jpg"),
                    "an already-inline shared JPEG remains byte-for-byte original, including EXIF");
            Attachment camera =
                    store.importFile(
                            PocketFiles.uri(context, source, "capture.jpg", "image/jpeg"),
                            copy,
                            0,
                            ImportRoute.Kind.CAMERA);
            Bitmap upright = BitmapFactory.decodeFile(copy.getPath());
            try {
                ExifInterface clean = new ExifInterface(copy.getPath());
                int orientation =
                        clean.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                check(
                        camera.mime.equals("image/jpeg")
                                && upright.getWidth() == 160
                                && upright.getHeight() == 320
                                && clean.getAttribute(ExifInterface.TAG_GPS_LATITUDE) == null
                                && (orientation == ExifInterface.ORIENTATION_NORMAL
                                        || orientation == ExifInterface.ORIENTATION_UNDEFINED),
                        "camera JPEG encoding bakes the actual EXIF orientation into"
                                + " full-resolution pixels and omits location tags: "
                                + upright.getWidth()
                                + "x"
                                + upright.getHeight()
                                + " GPS="
                                + clean.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                                + " orientation="
                                + clean.getAttributeInt(
                                        ExifInterface.TAG_ORIENTATION,
                                        ExifInterface.ORIENTATION_NORMAL));
            } finally {
                upright.recycle();
            }
            try (FileOutputStream out = new FileOutputStream(source)) {
                original.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            byte[] png = Files.readAllBytes(source.toPath());
            Attachment inline =
                    store.importFile(
                            PocketFiles.uri(context, source, "keep.png", "image/png"),
                            copy,
                            0,
                            ImportRoute.Kind.MEDIA);
            check(
                    inline.mime.equals("image/png")
                            && Arrays.equals(Files.readAllBytes(copy.toPath()), png),
                    "inline PNG imports retain original data instead of introducing JPEG loss");
            original.recycle();
            byte[] unsupported = new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
            Files.write(source.toPath(), unsupported);
            Attachment fallback =
                    store.importFile(
                            PocketFiles.uri(context, source, "unsupported.heic", "image/heic"),
                            copy,
                            0,
                            ImportRoute.Kind.MEDIA);
            check(
                    fallback.mime.equals("image/heic")
                            && fallback.name.equals("unsupported.heic")
                            && Arrays.equals(Files.readAllBytes(copy.toPath()), unsupported),
                    "undecodable image content remains an honest usable file rather than a falsely"
                            + " labelled JPEG");
        } finally {
            source.delete();
            copy.delete();
        }
        return assertions;
    }

    private static byte[] bitmapBytes(int width, int height) {
        int stride = (width * 3 + 3) & ~3;
        ByteBuffer bytes = ByteBuffer.allocate(54 + stride * height).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 'B').put((byte) 'M').putInt(bytes.capacity()).putInt(0).putInt(54);
        bytes.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24);
        bytes.putInt(0).putInt(stride * height).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) bytes.put((byte) 113).put((byte) 137).put((byte) 59);
            while ((bytes.position() - 54) % stride != 0) bytes.put((byte) 0);
        }
        return bytes.array();
    }
}
