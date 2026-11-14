// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import java.nio.ByteBuffer;

/** A bounded RGB viewfinder frame. Plane buffers are consumed before the camera image is closed. */
public final class PreviewFrame {
    public static final class Plane {
        final ByteBuffer data;
        final int base, row, pixel;

        public Plane(ByteBuffer data, int rowStride, int pixelStride) {
            this.data = data.duplicate();
            base = data.position();
            row = rowStride;
            pixel = pixelStride;
        }

        int at(int x, int y) {
            return data.get(base + y * row + x * pixel) & 255;
        }
    }

    public final int width, height;
    private final int[] argb;

    private PreviewFrame(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public void copyPixels(int[] target) {
        if (target.length != argb.length) throw new IllegalArgumentException("Preview buffer size");
        System.arraycopy(argb, 0, target, 0, argb.length);
    }

    public static PreviewFrame yuv(
            int width,
            int height,
            Plane yPlane,
            Plane uPlane,
            Plane vPlane,
            int clockwise,
            int maxEdge) {
        if (width < 2 || height < 2 || maxEdge < 2 || maxEdge > 480 || clockwise % 90 != 0)
            throw new IllegalArgumentException("Invalid viewfinder format");
        int rotation = (clockwise % 360 + 360) % 360;
        int sample = Math.max(1, (Math.max(width, height) + maxEdge - 1) / maxEdge);
        int sw = Math.max(1, width / sample), sh = Math.max(1, height / sample);
        int outWidth = rotation % 180 == 0 ? sw : sh;
        int outHeight = rotation % 180 == 0 ? sh : sw;
        int[] output = new int[outWidth * outHeight];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) {
                int sx = x * sample, sy = y * sample;
                int luma = Math.max(0, yPlane.at(sx, sy) - 16);
                int u = uPlane.at(sx / 2, sy / 2) - 128, v = vPlane.at(sx / 2, sy / 2) - 128;
                int red = clamp((298 * luma + 409 * v + 128) >> 8);
                int green = clamp((298 * luma - 100 * u - 208 * v + 128) >> 8);
                int blue = clamp((298 * luma + 516 * u + 128) >> 8);
                int dx =
                        rotation == 90
                                ? sh - 1 - y
                                : rotation == 180 ? sw - 1 - x : rotation == 270 ? y : x;
                int dy =
                        rotation == 90
                                ? x
                                : rotation == 180 ? sh - 1 - y : rotation == 270 ? sw - 1 - x : y;
                output[dy * outWidth + dx] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        return new PreviewFrame(outWidth, outHeight, output);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
