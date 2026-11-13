// Copyright 2026 OpenDuo
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import java.io.IOException;

/** Tiny JNI wrapper for the validated 16 kHz mono Pocket encoder. */
public final class OpusEncoder implements AutoCloseable {
    static {
        System.loadLibrary("pocket_e2_opus");
    }

    private long handle;

    public OpusEncoder() throws IOException {
        handle = create();
        if (handle == 0) throw new IOException("无法启动语音编码");
    }

    public byte[] encode(short[] frame) throws IOException {
        if (handle == 0) throw new IOException("语音编码已关闭");
        byte[] packet = encodeNative(handle, frame);
        if (packet == null || packet.length == 0) throw new IOException("语音编码失败");
        return packet;
    }

    @Override
    public void close() {
        if (handle != 0) {
            destroy(handle);
            handle = 0;
        }
    }

    private static native long create();

    private static native byte[] encodeNative(long handle, short[] frame);

    private static native void destroy(long handle);
}
