// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import java.io.IOException;

/** One decoder per declared speech. A declaration resets state without dropping queued speech. */
public final class OpusDecoder implements AutoCloseable {
    static {
        System.loadLibrary("pocket_e2_opus");
    }

    private long handle;

    public OpusDecoder() throws IOException {
        handle = create();
        if (handle == 0) throw new IOException("无法启动语音解码");
    }

    public short[] packet(byte[] bytes) throws IOException {
        short[] result = decode(handle, bytes);
        if (result == null) throw new IOException("语音数据损坏");
        return result;
    }

    @Override
    public void close() {
        if (handle != 0) {
            destroy(handle);
            handle = 0;
        }
    }

    private static native long create();

    private static native short[] decode(long handle, byte[] bytes);

    private static native void destroy(long handle);
}
