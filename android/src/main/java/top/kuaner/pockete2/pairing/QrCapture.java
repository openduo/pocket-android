// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.pairing;

import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;

import top.kuaner.pockete2.core.Endpoint;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded Camera2 image, sampled at four frames per second for local QR decoding. */
public final class QrCapture implements AutoCloseable {
    public interface Listener {
        void preview(Bitmap bitmap);

        void code(String text);

        void failed(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("Pocket E2 QR camera");
    private final Handler handler;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch released = new CountDownLatch(1);
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private long lastFrame;
    private int rotation;
    private boolean opening, closing, cleaned;

    public QrCapture(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    private void open() {
        if (stopped.get()) {
            cleanup();
            return;
        }
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String selected = null;
            Size size = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap formats =
                        info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (formats == null) continue;
                for (Size candidate : formats.getOutputSizes(ImageFormat.YUV_420_888)) {
                    if (candidate.getWidth() > 640 || candidate.getHeight() > 480) continue;
                    if (size == null
                            || candidate.getWidth() * candidate.getHeight()
                                    > size.getWidth() * size.getHeight()) size = candidate;
                }
                if (size != null) {
                    selected = id;
                    Integer angle = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    rotation = angle == null ? 0 : angle;
                    break;
                }
            }
            if (selected == null) {
                fail("没有可用的后置相机，可以粘贴配对链接");
                return;
            }
            reader =
                    ImageReader.newInstance(
                            size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(
                    source -> {
                        Image image = source.acquireLatestImage();
                        if (image == null) return;
                        try {
                            frame(image);
                        } catch (RuntimeException failure) {
                            fail("相机画面读取中断，可以关闭后重试");
                        } finally {
                            image.close();
                        }
                    },
                    handler);
            opening = true;
            manager.openCamera(
                    selected,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            opening = false;
                            if (stopped.get()) {
                                closing = true;
                                device.close();
                                return;
                            }
                            camera = device;
                            try {
                                device.createCaptureSession(
                                        Collections.singletonList(reader.getSurface()),
                                        new CameraCaptureSession.StateCallback() {
                                            @Override
                                            public void onConfigured(CameraCaptureSession capture) {
                                                if (stopped.get()) {
                                                    capture.close();
                                                    return;
                                                }
                                                session = capture;
                                                try {
                                                    CaptureRequest.Builder request =
                                                            device.createCaptureRequest(
                                                                    CameraDevice.TEMPLATE_PREVIEW);
                                                    request.addTarget(reader.getSurface());
                                                    request.set(
                                                            CaptureRequest.CONTROL_AF_MODE,
                                                            CaptureRequest
                                                                    .CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                                    capture.setRepeatingRequest(
                                                            request.build(), null, handler);
                                                } catch (CameraAccessException
                                                        | RuntimeException failure) {
                                                    fail("相机被占用，可以关闭其他相机功能后重试");
                                                }
                                            }

                                            @Override
                                            public void onConfigureFailed(
                                                    CameraCaptureSession capture) {
                                                fail("相机不支持扫码画面，可以粘贴链接");
                                            }
                                        },
                                        handler);
                            } catch (CameraAccessException | RuntimeException failure) {
                                fail("相机打开失败，可以粘贴链接");
                            }
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            ended(device);
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            ended(device);
                        }

                        private void ended(CameraDevice device) {
                            opening = false;
                            closing = true;
                            device.close();
                            if (!stopped.get()) fail("相机被占用或已断开，可以关闭后重试");
                        }

                        @Override
                        public void onClosed(CameraDevice device) {
                            closing = false;
                            finish();
                        }
                    },
                    handler);
        } catch (CameraAccessException | RuntimeException failure) {
            opening = false;
            fail("无法打开相机，请允许相机权限或粘贴配对链接");
        }
    }

    private void frame(Image image) {
        long now = SystemClock.elapsedRealtime();
        if (stopped.get() || now - lastFrame < 250) return;
        lastFrame = now;
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int offset = buffer.position(),
                stride = plane.getRowStride(),
                pixel = plane.getPixelStride();
        byte[] luminance = new byte[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                luminance[y * width + x] = buffer.get(offset + y * stride + x * pixel);
        String value = QrCode.read(luminance, width, height);
        if (value != null) {
            try {
                Endpoint.PairingLink.parse(value);
                listener.code(value);
                close();
                return;
            } catch (IllegalArgumentException unrelated) {
            }
        }
        int previewWidth = Math.max(1, width / 2), previewHeight = Math.max(1, height / 2);
        int[] colors = new int[previewWidth * previewHeight];
        for (int y = 0; y < previewHeight; y++)
            for (int x = 0; x < previewWidth; x++) {
                int valueY = luminance[y * 2 * width + x * 2] & 255;
                colors[y * previewWidth + x] = 0xff000000 | valueY << 16 | valueY << 8 | valueY;
            }
        Bitmap bitmap =
                Bitmap.createBitmap(colors, previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        if (rotation != 0) {
            Matrix transform = new Matrix();
            transform.setRotate(rotation);
            Bitmap upright =
                    Bitmap.createBitmap(bitmap, 0, 0, previewWidth, previewHeight, transform, true);
            if (bitmap != upright) bitmap.recycle();
            bitmap = upright;
        }
        listener.preview(bitmap);
    }

    private void fail(String reason) {
        if (stopped.compareAndSet(false, true)) {
            listener.failed(reason);
            cleanup();
        }
    }

    private void cleanup() {
        if (cleaned) {
            finish();
            return;
        }
        cleaned = true;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            closing = true;
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        finish();
    }

    private void finish() {
        if (!cleaned || opening || closing) return;
        released.countDown();
        thread.quitSafely();
    }

    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        return released.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) handler.post(this::cleanup);
    }
}
