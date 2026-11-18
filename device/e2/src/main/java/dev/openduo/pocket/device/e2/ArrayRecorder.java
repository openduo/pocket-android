// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device.e2;

/**
 * Internal JNI boundary for E2 firmware 04319; all vendor entry points stay in the native adapter.
 */
public final class ArrayRecorder {
    static {
        System.loadLibrary("tinyalsa");
        System.loadLibrary("tr_recorder");
        System.loadLibrary("e2_array");
    }

    private ArrayRecorder() {}

    public interface Callback {
        void onAudio(byte[] original, byte[] enhanced, float volume, float angle, float gain);

        default void onGain(float gain) {}

        default void onMicEvent(int event) {}

        default void onSwitchGain(boolean manual) {}

        default void onVolume(float volume) {}
    }

    /**
     * Field names and types are part of the firmware ABI, resolved from the actual object class.
     */
    public static final class Config {
        public boolean array;
        public String audioFilePath;
        public String configDir;
        public String frontAudioSavePath;
        public String micAudioSavePath;
        public int model;
        public String nnseModelPath;
        public int noiseReduceLevel;
    }

    public static native long create(Callback callback);

    /** Firmware driver batching in milliseconds; set before creating/starting a session. */
    public static native void setReadInterval(int millis);

    /** Monotonic milliseconds in the upper bits, PCM16 RMS in the low 16; zero while idle. */
    public static native long captureMeter();

    public static native void destroy(long handle);

    public static native int init(long handle, Config config);

    public static native int start(long handle, boolean startDevice);

    public static native int read(long handle);

    public static native int stop(long handle);
}
