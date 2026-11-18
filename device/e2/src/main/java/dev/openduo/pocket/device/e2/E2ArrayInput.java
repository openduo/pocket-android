// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.device.e2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import dev.openduo.pocket.audio.input.RecordingInput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Uses the recording engine supplied with E2 firmware 04319, in the app's recording process. */
public final class E2ArrayInput implements RecordingInput, ArrayRecorder.Callback {
    private static final int RATE = 16000;
    private static final String ASSET = "e2-array-models.zip";
    private final boolean natural;
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();
    private byte[] block;
    private int offset;
    private long handle;
    private boolean started;

    static boolean isE2() {
        return "E2 AI Recorder".equals(Build.MODEL) && Build.VERSION.SDK_INT == 27;
    }

    public static boolean available(Context context) {
        boolean compatible =
                requiresArray()
                        && new File(
                                        context.getApplicationInfo().nativeLibraryDir,
                                        "libtr_recorder.so")
                                .isFile()
                        && new File(context.getApplicationInfo().nativeLibraryDir, "libe2_array.so")
                                .isFile();
        if (!compatible) return false;
        try (java.io.InputStream ignored = context.getAssets().open(ASSET)) {
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    static boolean requiresArray() {
        return isE2() && Build.FINGERPRINT.endsWith("/04319:user/release-keys");
    }

    E2ArrayInput(Context context, String mode, String scene) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("请先授予麦克风权限");
        }
        if (!available(context)) throw new IOException("此 APK 缺少当前 E2 固件的阵列录音组件");
        natural = "natural".equals(mode);
        File models = installModels(context);
        try {
            // The firmware defaults to 300ms batches. The original APK sets this driver
            // parameter explicitly. Its capture library accepts 100ms steps, so use the
            // smallest supported batch for every shared E2 consumer.
            ArrayRecorder.setReadInterval(100);
            handle = ArrayRecorder.create(this);
            if (handle == 0) throw new IOException("无法建立 E2 阵列录音会话");
            ArrayRecorder.Config config = new ArrayRecorder.Config();
            config.model = "lecture".equals(scene) ? 2 : 1;
            config.configDir = new File(models, "config/model" + config.model).getPath();
            config.nnseModelPath = new File(models, "config/nnse").getPath();
            config.noiseReduceLevel = natural ? 0 : "factory".equals(mode) ? 2 : 1;
            check(ArrayRecorder.init(handle, config), "初始化");
        } catch (LinkageError error) {
            close();
            throw new IOException("E2 阵列录音组件无法加载", error);
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    @Override
    public int sampleRate() {
        return RATE;
    }

    @Override
    public boolean needsSoftwareDenoising() {
        return false;
    }

    @Override
    public long inputMeter() {
        return ArrayRecorder.captureMeter();
    }

    @Override
    public void start() throws IOException {
        check(ArrayRecorder.start(handle, true), "启动");
        started = true;
    }

    @Override
    public void onAudio(byte[] original, byte[] enhanced, float volume, float angle, float gain) {
        byte[] data = natural ? original : enhanced;
        if (data == null || data.length < 2) return;
        synchronized (ready) {
            ready.addLast(data.clone());
        }
    }

    @Override
    public int read(short[] samples) throws IOException {
        if (block == null || offset + 1 >= block.length) {
            block = nextBlock();
            offset = 0;
            if (block == null) {
                int status = ArrayRecorder.read(handle);
                if (status < 0) check(status, "读取");
                block = nextBlock();
                if (block == null) return 0;
            }
        }
        int count = Math.min(samples.length, (block.length - offset) / 2);
        for (int i = 0; i < count; i++) {
            samples[i] = (short) ((block[offset] & 0xff) | (block[offset + 1] << 8));
            offset += 2;
        }
        return count;
    }

    private byte[] nextBlock() {
        synchronized (ready) {
            return ready.pollFirst();
        }
    }

    @Override
    public void stop() {
        if (started) {
            ArrayRecorder.stop(handle);
            started = false;
        }
        synchronized (ready) {
            ready.clear();
        }
        block = null;
        offset = 0;
    }

    @Override
    public void close() {
        if (handle == 0) return;
        try {
            stop();
        } finally {
            ArrayRecorder.destroy(handle);
            handle = 0;
        }
    }

    private static void check(int status, String operation) throws IOException {
        if (status != 0) throw new IOException("E2 阵列" + operation + "失败（" + status + "）");
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        long total = 0;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > 32L * 1024 * 1024) throw new IOException("阵列模型超出限制");
            out.write(buffer, 0, count);
        }
    }

    private static synchronized File installModels(Context context) throws IOException {
        File directory = new File(context.getNoBackupFilesDir(), "e2-array-04319");
        File ready = new File(directory, "complete");
        if (ready.isFile()) return directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法建立阵列模型目录");
        String parent = directory.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(ASSET))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(directory, entry.getName());
                if (!file.getCanonicalPath().startsWith(parent)) throw new IOException("阵列模型路径无效");
                File folder = entry.isDirectory() ? file : file.getParentFile();
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法建立阵列模型目录");
                if (entry.isDirectory()) continue;
                try (FileOutputStream out = new FileOutputStream(file)) {
                    copy(zip, out);
                }
            }
        }
        if (!ready.createNewFile()) throw new IOException("无法完成阵列模型准备");
        return directory;
    }
}
