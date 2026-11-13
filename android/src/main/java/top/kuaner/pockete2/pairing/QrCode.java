// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.pairing;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.*;

/** QR-only local decoding. No image or pairing link leaves the device during scanning. */
public final class QrCode {
    private QrCode() {}

    public static String read(byte[] luminance, int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > luminance.length) return null;
        QRCodeReader reader = new QRCodeReader();
        try {
            BinaryBitmap bitmap =
                    new BinaryBitmap(
                            new HybridBinarizer(
                                    new PlanarYUVLuminanceSource(
                                            luminance, width, height, 0, 0, width, height, false)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, true);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            return reader.decode(bitmap, hints).getText();
        } catch (ReaderException missing) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
