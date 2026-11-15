// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Range;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 YUV to MediaCodec. One acquired image, bounded buffers, upright Annex-B output. */
public final class SightCapture implements AutoCloseable {
    public interface Listener {
        void started();

        void chunk(
                boolean key, long at, byte[] annexB, int width, int height, int fps, int bitrate);

        void failed(String reason);

        default void preview(PreviewFrame frame) {}

        default boolean wantsPreview() {
            return false;
        }
    }

    private final Context context;
    private final SightProtocol.Ask ask;
    private final Listener listener;
    private final boolean front;
    private final int deviceOrientation;
    private final HandlerThread thread = new HandlerThread("DuoDuo Pocket camera");
    private final Handler handler;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final java.util.concurrent.CountDownLatch released =
            new java.util.concurrent.CountDownLatch(1);

    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        return released.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader images;
    private MediaCodec encoder;
    private YuvTransform transform;
    private int rotation, width, height, fps, bitrate, ceilingFps;
    private long lastFrame, lastPreview, anchorUs, anchorWall;
    private byte[] config = new byte[0];
    private volatile boolean busy;
    private boolean opening, closingCamera, resourcesClosed;

    public SightCapture(Context context, SightProtocol.Ask ask, boolean front, Listener listener) {
        this(context, ask, front, 0, listener);
    }

    public SightCapture(
            Context context,
            SightProtocol.Ask ask,
            boolean front,
            int deviceOrientation,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.ask = ask;
        this.front = front;
        this.deviceOrientation = deviceOrientation;
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    public void busy(boolean value) {
        busy = value;
    }

    public void thermal(boolean serious) {
        handler.post(
                () -> {
                    int next = serious ? ask.minFps : ceilingFps;
                    int nextBitrate = (int) Math.max(1, (long) ask.bitrate * next / ask.fps);
                    if (encoder == null || fps == next && bitrate == nextBitrate) return;
                    try {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, nextBitrate);
                        encoder.setParameters(params);
                        fps = next;
                        bitrate = nextBitrate;
                    } catch (RuntimeException failure) {
                        fail("encoder_failed");
                    }
                });
    }

    public void requestKey() {
        handler.post(
                () -> {
                    if (encoder != null)
                        try {
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            encoder.setParameters(b);
                        } catch (RuntimeException ignored) {
                        }
                });
    }

    private void open() {
        if (stopped.get()) {
            cleanup();
            return;
        }
        String reason = "camera_taken";
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String chosen = null;
            CameraCharacteristics characteristics = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null
                        && facing
                                == (front
                                        ? CameraCharacteristics.LENS_FACING_FRONT
                                        : CameraCharacteristics.LENS_FACING_BACK)) {
                    chosen = id;
                    characteristics = c;
                    break;
                }
            }
            if (chosen == null) {
                fail("no_camera");
                return;
            }
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            rotation =
                    SightProtocol.bufferRotation(
                            sensor == null ? 0 : sensor, front, deviceOrientation);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = null;
            if (map != null)
                for (Size supported : map.getOutputSizes(ImageFormat.YUV_420_888))
                    if (supported.getWidth() == ask.width && supported.getHeight() == ask.height
                            || supported.getWidth() == ask.height
                                    && supported.getHeight() == ask.width) {
                        size = supported;
                        break;
                    }
            if (size == null) {
                fail("no_format");
                return;
            }
            width = rotation % 180 == 0 ? size.getWidth() : size.getHeight();
            height = rotation % 180 == 0 ? size.getHeight() : size.getWidth();
            fps = ask.fps;
            bitrate = ask.bitrate;
            Range<Integer>[] ranges =
                    characteristics.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> rate = null;
            if (ranges != null)
                for (Range<Integer> range : ranges) {
                    int candidate = Math.min(ask.fps, range.getUpper());
                    if (candidate >= ask.minFps
                            && range.getLower() <= ask.fps
                            && (rate == null || candidate > Math.min(ask.fps, rate.getUpper())))
                        rate = range;
                }
            if (rate == null) {
                fail("no_format");
                return;
            }
            fps = Math.min(ask.fps, rate.getUpper());
            ceilingFps = fps;
            bitrate = (int) Math.max(1, (long) ask.bitrate * fps / ask.fps);
            final Range<Integer> selectedRate = rate;
            reason = "no_h264";
            MediaFormat format =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, (float) (ask.keyIntervalMs / 1000.0));
            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            int profile =
                    ask.profile == 66
                            ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                            : ask.profile == 77
                                    ? MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                                    : ask.profile == 100
                                            ? MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                                            : 0;
            if (profile == 0) {
                fail("no_h264");
                return;
            }
            format.setInteger(MediaFormat.KEY_PROFILE, profile);
            int level = level(ask.level);
            if (level == 0) {
                fail("no_h264");
                return;
            }
            format.setInteger(MediaFormat.KEY_LEVEL, level);
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String codec = codecs.findEncoderForFormat(format);
            if (codec == null && width != height) {
                int swapped = width;
                width = height;
                height = swapped;
                format.setInteger(MediaFormat.KEY_WIDTH, width);
                format.setInteger(MediaFormat.KEY_HEIGHT, height);
                codec = codecs.findEncoderForFormat(format);
            }
            if (codec == null) {
                fail("no_h264");
                return;
            }
            transform =
                    new YuvTransform(size.getWidth(), size.getHeight(), width, height, rotation);
            encoder = MediaCodec.createByCodecName(codec);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            images =
                    ImageReader.newInstance(
                            size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            images.setOnImageAvailableListener(
                    reader -> {
                        Image image = reader.acquireLatestImage();
                        if (image == null) return;
                        try {
                            encode(image);
                        } finally {
                            image.close();
                        }
                    },
                    handler);
            reason = "camera_taken";
            opening = true;
            manager.openCamera(
                    chosen,
                    new CameraDevice.StateCallback() {
                        public void onOpened(CameraDevice c) {
                            opening = false;
                            if (stopped.get()) {
                                closingCamera = true;
                                c.close();
                                return;
                            }
                            camera = c;
                            try {
                                c.createCaptureSession(
                                        Collections.singletonList(images.getSurface()),
                                        new CameraCaptureSession.StateCallback() {
                                            public void onConfigured(CameraCaptureSession s) {
                                                if (stopped.get()) {
                                                    s.close();
                                                    return;
                                                }
                                                session = s;
                                                try {
                                                    CaptureRequest.Builder request =
                                                            c.createCaptureRequest(
                                                                    CameraDevice.TEMPLATE_RECORD);
                                                    request.addTarget(images.getSurface());
                                                    request.set(
                                                            CaptureRequest
                                                                    .CONTROL_AE_TARGET_FPS_RANGE,
                                                            selectedRate);
                                                    request.set(
                                                            CaptureRequest.CONTROL_AF_MODE,
                                                            CaptureRequest
                                                                    .CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                                    s.setRepeatingRequest(
                                                            request.build(), null, handler);
                                                    listener.started();
                                                    drainLater();
                                                } catch (CameraAccessException
                                                        | RuntimeException failure) {
                                                    fail("camera_taken");
                                                }
                                            }

                                            public void onConfigureFailed(CameraCaptureSession s) {
                                                fail("no_format");
                                            }
                                        },
                                        handler);
                            } catch (CameraAccessException | RuntimeException failure) {
                                fail("camera_taken");
                            }
                        }

                        public void onDisconnected(CameraDevice c) {
                            opening = false;
                            closingCamera = true;
                            c.close();
                            if (!stopped.get()) fail("camera_taken");
                        }

                        public void onError(CameraDevice c, int error) {
                            opening = false;
                            closingCamera = true;
                            c.close();
                            if (!stopped.get()) fail("camera_taken");
                        }

                        @Override
                        public void onClosed(CameraDevice c) {
                            closingCamera = false;
                            finishClose();
                        }
                    },
                    handler);
        } catch (Exception | LinkageError failure) {
            opening = false;
            fail(reason);
        }
    }

    private void encode(Image image) {
        if (stopped.get() || encoder == null) return;
        long at = image.getTimestamp() / 1000;
        if (!front
                && listener.wantsPreview()
                && (lastPreview == 0 || at - lastPreview >= 200_000)) {
            lastPreview = at;
            try {
                Image.Plane[] planes = image.getPlanes();
                PreviewFrame.Plane[] input = new PreviewFrame.Plane[3];
                for (int i = 0; i < 3; i++)
                    input[i] =
                            new PreviewFrame.Plane(
                                    planes[i].getBuffer(),
                                    planes[i].getRowStride(),
                                    planes[i].getPixelStride());
                listener.preview(
                        PreviewFrame.yuv(
                                image.getWidth(),
                                image.getHeight(),
                                input[0],
                                input[1],
                                input[2],
                                rotation,
                                240));
            } catch (RuntimeException ignored) {
                // Preview failure must not interrupt the video stream's independent YUV path.
            }
        }
        if (lastFrame != 0 && at - lastFrame < 1_000_000L / fps) return;
        if (busy) {
            requestKey();
            return;
        }
        lastFrame = at;
        try {
            int index = encoder.dequeueInputBuffer(0);
            if (index < 0) return;
            Image target = encoder.getInputImage(index);
            if (target == null) throw new IllegalStateException("Encoder lacks YUV planes");
            try {
                transform.copy(planes(image), planes(target));
            } finally {
                target.close();
            }
            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, at, 0);
            drain();
        } catch (RuntimeException failure) {
            fail("encoder_failed");
        }
    }

    private static YuvTransform.Plane[] planes(Image image) {
        Image.Plane[] source = image.getPlanes();
        YuvTransform.Plane[] planes = new YuvTransform.Plane[3];
        for (int p = 0; p < 3; p++)
            planes[p] =
                    new YuvTransform.Plane(
                            source[p].getBuffer(),
                            source[p].getRowStride(),
                            source[p].getPixelStride());
        return planes;
    }

    private void drainLater() {
        if (stopped.get()) return;
        try {
            drain();
        } catch (RuntimeException failure) {
            fail("encoder_failed");
            return;
        }
        handler.postDelayed(this::drainLater, 15);
    }

    private void drain() {
        if (encoder == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int tries = 0; tries < 16; tries++) {
            int index = encoder.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat output = encoder.getOutputFormat();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (String key : new String[] {"csd-0", "csd-1"})
                    if (output.containsKey(key)) {
                        ByteBuffer b = output.getByteBuffer(key).duplicate();
                        byte[] value = new byte[b.remaining()];
                        b.get(value);
                        byte[] annex = SightProtocol.annexB(value);
                        bytes.write(annex, 0, annex.length);
                    }
                config = bytes.toByteArray();
                continue;
            }
            if (index < 0) continue;
            try {
                ByteBuffer buffer = encoder.getOutputBuffer(index);
                if (buffer == null || info.size == 0) continue;
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                byte[] bytes = new byte[info.size];
                buffer.get(bytes);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    config = SightProtocol.annexB(bytes);
                    continue;
                }
                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                byte[] payload = SightProtocol.annexB(bytes);
                if (key && config.length > 0) {
                    byte[] joined = new byte[config.length + payload.length];
                    System.arraycopy(config, 0, joined, 0, config.length);
                    System.arraycopy(payload, 0, joined, config.length, payload.length);
                    payload = joined;
                }
                if (anchorUs == 0) {
                    anchorUs = info.presentationTimeUs;
                    anchorWall = System.currentTimeMillis();
                }
                listener.chunk(
                        key,
                        anchorWall + (info.presentationTimeUs - anchorUs) / 1000,
                        payload,
                        width,
                        height,
                        fps,
                        bitrate);
            } finally {
                encoder.releaseOutputBuffer(index, false);
            }
        }
    }

    private static int level(int value) {
        switch (value) {
            case 10:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel1;
            case 11:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel11;
            case 12:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel12;
            case 13:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel13;
            case 20:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel2;
            case 21:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel21;
            case 22:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel22;
            case 30:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel3;
            case 31:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
            case 32:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
            case 40:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
            case 41:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel41;
            case 42:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
            case 50:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel5;
            case 51:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
            case 52:
                return MediaCodecInfo.CodecProfileLevel.AVCLevel52;
            default:
                return 0;
        }
    }

    private void fail(String reason) {
        if (stopped.compareAndSet(false, true)) {
            listener.failed(reason);
            cleanup();
        }
    }

    private void cleanup() {
        if (resourcesClosed) {
            finishClose();
            return;
        }
        resourcesClosed = true;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (camera != null) {
            closingCamera = true;
            camera.close();
            camera = null;
        }
        if (images != null) {
            images.close();
            images = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
            encoder = null;
        }
        finishClose();
    }

    private void finishClose() {
        if (!resourcesClosed || opening || closingCamera) return;
        released.countDown();
        thread.quitSafely();
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) handler.post(this::cleanup);
    }
}
