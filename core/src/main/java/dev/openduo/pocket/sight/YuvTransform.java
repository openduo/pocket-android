// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import java.nio.ByteBuffer;

/** Upright, aspect-preserving YUV420 conversion into a supported encoder orientation. */
public final class YuvTransform {
    public static final class Plane {
        private final ByteBuffer data;
        private final int base, row, pixel;

        public Plane(ByteBuffer data, int row, int pixel) {
            this.data = data.duplicate();
            base = data.position();
            this.row = row;
            this.pixel = pixel;
        }

        private byte at(int x, int y) {
            return data.get(base + y * row + x * pixel);
        }

        private void put(int x, int y, byte value) {
            data.put(base + y * row + x * pixel, value);
        }
    }

    private final int sourceWidth, sourceHeight, targetWidth, targetHeight, rotation;
    private final int contentWidth, contentHeight, left, top;

    public YuvTransform(int sw, int sh, int tw, int th, int rotation) {
        if (sw < 2
                || sh < 2
                || tw < 2
                || th < 2
                || ((sw | sh | tw | th) & 1) != 0
                || rotation < 0
                || rotation >= 360
                || rotation % 90 != 0)
            throw new IllegalArgumentException("Invalid YUV420 geometry");
        sourceWidth = sw;
        sourceHeight = sh;
        targetWidth = tw;
        targetHeight = th;
        this.rotation = rotation;
        int rw = rotation % 180 == 0 ? sw : sh;
        int rh = rotation % 180 == 0 ? sh : sw;
        if ((long) tw * rh <= (long) th * rw) {
            contentWidth = tw;
            contentHeight = Math.max(2, (int) ((long) rh * tw / rw) & ~1);
        } else {
            contentHeight = th;
            contentWidth = Math.max(2, (int) ((long) rw * th / rh) & ~1);
        }
        left = (tw - contentWidth) / 2 & ~1;
        top = (th - contentHeight) / 2 & ~1;
    }

    public void copy(Plane[] source, Plane[] target) {
        if (source.length != 3 || target.length != 3)
            throw new IllegalArgumentException("YUV420 requires three planes");
        for (int p = 0; p < 3; p++) {
            int sample = p == 0 ? 1 : 2;
            int sw = sourceWidth / sample, sh = sourceHeight / sample;
            int tw = targetWidth / sample, th = targetHeight / sample;
            int cw = contentWidth / sample, ch = contentHeight / sample;
            int ox = left / sample, oy = top / sample;
            int rw = rotation % 180 == 0 ? sw : sh;
            int rh = rotation % 180 == 0 ? sh : sw;
            byte black = (byte) (p == 0 ? 16 : 128);
            for (int y = 0; y < th; y++) {
                for (int x = 0; x < tw; x++) {
                    if (x < ox || x >= ox + cw || y < oy || y >= oy + ch) {
                        target[p].put(x, y, black);
                        continue;
                    }
                    int rx = (int) ((long) (x - ox) * rw / cw);
                    int ry = (int) ((long) (y - oy) * rh / ch);
                    int sx =
                            rotation == 90
                                    ? ry
                                    : rotation == 180
                                            ? sw - 1 - rx
                                            : rotation == 270 ? sw - 1 - ry : rx;
                    int sy =
                            rotation == 90
                                    ? sh - 1 - rx
                                    : rotation == 180 ? sh - 1 - ry : rotation == 270 ? rx : ry;
                    target[p].put(x, y, source[p].at(sx, sy));
                }
            }
        }
    }
}
