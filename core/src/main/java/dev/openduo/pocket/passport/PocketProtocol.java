// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Pocket protocol 1.3. ATT MTU includes the three-byte ATT header. */
public final class PocketProtocol {
    public static final String SERVICE = "8bd90001-86c5-454b-b65b-3f16cffb662f",
            TX = "8bd90002-86c5-454b-b65b-3f16cffb662f",
            RX = "8bd90003-86c5-454b-b65b-3f16cffb662f";
    public static final int INFO = 1,
            PRESS_START = 2,
            AUDIO = 3,
            PRESS_END = 4,
            STATUS = 5,
            KEEPALIVE = 6;
    public static final int RESULT = 0x81,
            REPLY = 0x82,
            REPLY_DONE = 0x83,
            APP_STATE = 0x84,
            WORK = 0x85,
            MAX_MESSAGE = 4096;

    public enum Direction {
        DEVICE_TO_PHONE,
        PHONE_TO_DEVICE,
        BOTH
    }

    private PocketProtocol() {}

    /** Protocol 1.2 adds the language byte; older devices ignore that trailing byte. */
    public static byte[] appState(int state, boolean english) {
        if (state < 0 || state > 2) throw new IllegalArgumentException("Invalid app state");
        return new byte[] {(byte) state, (byte) (english ? 1 : 0)};
    }

    public static int u16(byte[] b, int at) {
        return (b[at] & 255) | ((b[at + 1] & 255) << 8);
    }

    public static long u32(byte[] b, int at) {
        return u16(b, at) | ((long) u16(b, at + 2) << 16);
    }

    public static byte[] u16s(int... values) {
        byte[] out = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            out[i * 2] = (byte) values[i];
            out[i * 2 + 1] = (byte) (values[i] >>> 8);
        }
        return out;
    }

    public static byte[] u32(long value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }

    public static byte[][] fragments(int type, byte[] payload, int mtu, boolean quiet) {
        int chunk = mtu - 7;
        if (chunk < 1 || payload == null || payload.length > MAX_MESSAGE)
            throw new IllegalArgumentException("Invalid Pocket message");
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        do {
            int n = Math.min(chunk, payload.length - at);
            byte[] pdu = new byte[n + 4];
            pdu[0] = (byte) type;
            pdu[1] = (byte) ((at + n < payload.length ? 1 : 0) | (quiet && type == REPLY ? 2 : 0));
            pdu[2] = (byte) n;
            pdu[3] = (byte) (n >>> 8);
            System.arraycopy(payload, at, pdu, 4, n);
            out.add(pdu);
            at += n;
        } while (at < payload.length);
        return out.toArray(new byte[0][]);
    }

    public static String cut(String value, int budget) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= budget) return value;
        StringBuilder out = new StringBuilder();
        int used = 0;
        java.text.BreakIterator iterator =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(value);
        int start = iterator.first();
        for (int end = iterator.next();
                end != java.text.BreakIterator.DONE;
                start = end, end = iterator.next()) {
            String character = value.substring(start, end);
            int n = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + n > budget - 3) break;
            out.append(character);
            used += n;
        }
        return out.append('…').toString();
    }

    public static byte[] result(int press, int code, String text) {
        return join(
                new byte[] {(byte) press, (byte) (press >>> 8), (byte) code},
                cut(text == null ? "" : text, MAX_MESSAGE - 3).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] reply(long id, boolean done, String text) {
        byte[] prefix = new byte[5];
        System.arraycopy(u32(id), 0, prefix, 0, 4);
        prefix[4] = (byte) (done ? 1 : 0);
        return join(
                prefix,
                cut(text == null ? "" : text, MAX_MESSAGE - 5).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static final class Message {
        public final int type;
        public final byte[] data;
        public final boolean quiet;

        Message(int type, byte[] data, boolean quiet) {
            this.type = type;
            this.data = data;
            this.quiet = quiet;
        }

        public boolean valid(Direction direction) {
            if (type < 0x80 && direction == Direction.PHONE_TO_DEVICE
                    || type >= 0x80 && direction == Direction.DEVICE_TO_PHONE) return false;
            switch (type) {
                case INFO:
                    return data.length >= 6 && data.length >= 6 + (data[2] & 255);
                case PRESS_START:
                case KEEPALIVE:
                case STATUS:
                    return data.length >= 2;
                case AUDIO:
                    return data.length >= 5 && data.length <= 4 + 1275;
                case PRESS_END:
                    return data.length >= 4;
                case RESULT:
                    return data.length >= 3 && (data[2] & 255) <= 4;
                case REPLY:
                    return data.length >= 5;
                case REPLY_DONE:
                    return data.length >= 4;
                case APP_STATE:
                    return data.length >= 1 && (data[0] & 255) <= 2;
                case WORK:
                    return data.length >= 1 && (data[0] & 255) <= 3;
                default:
                    return false;
            }
        }

        public String text(int at) {
            return new String(
                    data,
                    Math.min(at, data.length),
                    Math.max(0, data.length - at),
                    StandardCharsets.UTF_8);
        }
    }

    public static final class Receiver {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(MAX_MESSAGE);
        private final Direction direction;
        private int type = -1;
        private boolean overflow;
        public int errors;

        public Receiver() {
            this(Direction.BOTH);
        }

        public Receiver(Direction direction) {
            this.direction = direction;
        }

        public void reset() {
            buffer.reset();
            type = -1;
            overflow = false;
        }

        public Message push(byte[] pdu) {
            if (pdu == null || pdu.length < 4 || u16(pdu, 2) != pdu.length - 4) {
                errors++;
                reset();
                return null;
            }
            int incoming = pdu[0] & 255;
            if (type != -1 && type != incoming) {
                errors++;
                reset();
            }
            type = incoming;
            if (!overflow && buffer.size() + pdu.length - 4 <= MAX_MESSAGE)
                buffer.write(pdu, 4, pdu.length - 4);
            else overflow = true;
            if ((pdu[1] & 1) != 0) return null;
            Message result = new Message(type, buffer.toByteArray(), (pdu[1] & 2) != 0);
            boolean invalid = overflow || !result.valid(direction);
            reset();
            if (invalid) {
                errors++;
                return null;
            }
            return result;
        }
    }
}
