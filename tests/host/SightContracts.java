// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.AmbientInput;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.sight.YuvTransform;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class SightContracts {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static ByteBuffer buffer(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length);
        for (int i = 0; i < values.length; i++) buffer.put(i, (byte) values[i]);
        return buffer;
    }

    private static YuvTransform.Plane plane(ByteBuffer buffer, int row) {
        return new YuvTransform.Plane(buffer, row, 1);
    }

    private static byte[] rotated(int rotation, int width, int height) {
        ByteBuffer y = ByteBuffer.allocate(width * height);
        new YuvTransform(4, 2, width, height, rotation)
                .copy(
                        new YuvTransform.Plane[] {
                            plane(buffer(20, 21, 22, 23, 24, 25, 26, 27), 4),
                            plane(buffer(80, 81), 2),
                            plane(buffer(160, 161), 2)
                        },
                        new YuvTransform.Plane[] {
                            plane(y, width),
                            plane(ByteBuffer.allocate(width * height / 4), width / 2),
                            plane(ByteBuffer.allocate(width * height / 4), width / 2)
                        });
        return y.array();
    }

    static int run() {
        assertions = 0;
        check(
                Arrays.equals(rotated(0, 4, 2), buffer(20, 21, 22, 23, 24, 25, 26, 27).array()),
                "YUV identity preserves the camera's full field");
        check(
                Arrays.equals(rotated(90, 2, 4), buffer(24, 20, 25, 21, 26, 22, 27, 23).array()),
                "YUV clockwise rotation remains upright");
        check(
                Arrays.equals(rotated(180, 4, 2), buffer(27, 26, 25, 24, 23, 22, 21, 20).array()),
                "YUV inverted rotation remains upright");
        check(
                Arrays.equals(rotated(270, 2, 4), buffer(23, 27, 22, 26, 21, 25, 20, 24).array()),
                "YUV counterclockwise rotation remains upright");

        ByteBuffer sourceY =
                buffer(
                        99, 20, 99, 21, 99, 22, 99, 23, 99, 99, 99, 24, 99, 25, 99, 26, 99, 27, 99,
                        99);
        sourceY.position(1);
        ByteBuffer targetY = ByteBuffer.allocate(41);
        Arrays.fill(targetY.array(), (byte) 99);
        targetY.position(1);
        ByteBuffer targetU = ByteBuffer.allocate(8), targetV = ByteBuffer.allocate(8);
        new YuvTransform(4, 2, 8, 4, 90)
                .copy(
                        new YuvTransform.Plane[] {
                            new YuvTransform.Plane(sourceY, 10, 2),
                            plane(buffer(80, 81), 2),
                            plane(buffer(160, 161), 2)
                        },
                        new YuvTransform.Plane[] {
                            new YuvTransform.Plane(targetY, 10, 1),
                            plane(targetU, 4),
                            plane(targetV, 4)
                        });
        check(
                targetY.get(3) == 24
                        && targetY.get(4) == 20
                        && targetY.get(33) == 27
                        && targetY.get(34) == 23,
                "landscape-only encoding keeps all rotated corners without stretching or cropping");
        check(
                targetY.get(1) == 16
                        && targetY.get(2) == 16
                        && targetY.get(5) == 16
                        && targetY.get(8) == 16
                        && targetY.get(31) == 16,
                "landscape fallback pads the upright scene with video black");
        check(
                Arrays.equals(targetU.array(), buffer(128, 80, 128, 128, 128, 81, 128, 128).array())
                        && Arrays.equals(
                                targetV.array(),
                                buffer(128, 160, 128, 128, 128, 161, 128, 128).array()),
                "YUV fallback keeps chroma aligned and padding neutral");
        check(
                sourceY.position() == 1
                        && targetY.position() == 1
                        && targetY.get(0) == 99
                        && targetY.get(9) == 99
                        && targetY.get(10) == 99,
                "strided planes preserve their positions, prefixes and row padding");
        byte[] enlarged = rotated(90, 8, 8);
        check(
                enlarged[2] == 24 && enlarged[5] == 20 && enlarged[58] == 27 && enlarged[61] == 23,
                "aspect-fit scaling preserves the entire upright scene");

        try (AmbientInput input = new AmbientInput()) {
            input.meta(Json.object("conn", "prepared", "media", 1), "prepared-room");
            input.packet("prepared-room", false);
            input.meta(Json.object("role", "master"), "prepared-room");
            byte[] opus = {(byte) 0xb8, (byte) 0xff, (byte) 0xfe};
            check(
                    Arrays.equals(
                            input.uplink(opus),
                            new byte[] {1, (byte) 0xb8, (byte) 0xff, (byte) 0xfe}),
                    "media-v1 adds a kind byte only on the audio uplink");
            check(
                    Arrays.equals(input.downlink(opus), opus),
                    "media-v1 server audio remains an unchanged Opus packet through JNI");
            check(
                    Arrays.equals(input.downlink(new byte[] {1, 2, 3}), new byte[] {1, 2, 3}),
                    "an Opus packet beginning with one is never stripped as a media header");
        }
        return assertions;
    }
}
