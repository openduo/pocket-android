// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.sight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import dev.openduo.pocket.core.Json;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** State-executor owner of the camera ask, gates, lifecycle, rate and media-v1 stream. */
public final class SightSession {
    public interface Listener {
        void changed();

        void text(JSONObject frame);

        void binary(byte[] frame);
    }

    public static final class Snapshot {
        public final boolean offered, statusVisible, on, requested, retry, front, canFlip;
        public final String status, refusal;
        public final int rotation;

        Snapshot(SightSession session) {
            boolean unsupported =
                    session.active && session.master && !session.framed && !session.notice;
            offered = session.ask != null && session.active && session.master && session.framed;
            statusVisible =
                    session.active
                            && session.master
                            && (session.ask != null || session.notice || unsupported);
            on = session.capture != null && session.transmitting;
            requested = session.enabled;
            retry = session.recovery.exhausted();
            front = session.front;
            canFlip = session.frontAvailable && session.rearAvailable;
            status = unsupported ? "当前房间服务未提供视频能力" : session.status;
            refusal = session.refusal;
            rotation = session.deviceOrientation;
        }
    }

    private final Context context;
    private final Executor state, closer;
    private final Listener listener;
    private final BooleanSupplier connected, congested;
    private final SightProtocol.Uplink uplink = new SightProtocol.Uplink();
    private final SightRecovery recovery = new SightRecovery();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager cameras;
    private final String frontCamera, rearCamera;
    private CameraManager.AvailabilityCallback availability;
    private String observedCamera = "";
    private Boolean cameraAvailable;
    private volatile int availabilityEpoch;
    private boolean ownRelease;
    private Runnable retryWake;
    private SightProtocol.Ask ask;
    private SightCapture capture;
    private boolean active, master, framed, foreground, enabled, front, stopping, transmitting;
    private boolean notice;
    private final boolean frontAvailable, rearAvailable;
    private final DeviceOrientation orientation;
    private volatile boolean previewWanted;
    private volatile PreviewFrame preview;
    private int heat, maxBytes, deviceOrientation;
    private volatile int epoch;
    private String status = "相机未开启", declined = "", refusal = "";

    public SightSession(
            Context context,
            Executor state,
            Executor closer,
            BooleanSupplier connected,
            BooleanSupplier congested,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.state = state;
        this.closer = closer;
        this.connected = connected;
        this.congested = congested;
        this.listener = listener;
        cameras = context.getSystemService(CameraManager.class);
        String frontId = null, rearId = null;
        try {
            for (String id : cameras.getCameraIdList()) {
                Integer facing =
                        cameras.getCameraCharacteristics(id)
                                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (frontId == null
                        && facing != null
                        && facing
                                == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
                    frontId = id;
                if (rearId == null
                        && facing != null
                        && facing
                                == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
                    rearId = id;
            }
        } catch (android.hardware.camera2.CameraAccessException | RuntimeException ignored) {
        }
        frontCamera = frontId;
        rearCamera = rearId;
        frontAvailable = frontId != null;
        rearAvailable = rearId != null;
        front =
                frontAvailable
                        && (!rearAvailable
                                || context.getSharedPreferences(
                                                dev.openduo.pocket.app.AppIdentity.SIGHT,
                                                Context.MODE_PRIVATE)
                                        .getBoolean("front", true));
        orientation =
                new DeviceOrientation(
                        context, degrees -> state.execute(() -> orientation(degrees)));
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public PreviewFrame preview() {
        return preview;
    }

    public void previewWanted(boolean value) {
        previewWanted = value;
        if (!value) preview = null;
    }

    public void orientation(int degrees) {
        if (degrees % 90 != 0 || degrees < 0 || degrees >= 360)
            throw new IllegalArgumentException("Invalid camera orientation");
        if (deviceOrientation == degrees) return;
        deviceOrientation = degrees;
        recovery.inputsChanged();
        if (capture != null) stop("");
        apply();
        listener.changed();
    }

    public void update(
            boolean active, boolean master, boolean framed, boolean foreground, int heat) {
        if (this.active != active
                || this.master != master
                || this.framed != framed
                || this.foreground != foreground
                || this.heat != heat) recovery.inputsChanged();
        this.active = active;
        this.master = master;
        this.framed = framed;
        this.foreground = foreground;
        if (this.heat != heat && capture != null) capture.thermal(heat > DeviceHeat.NORMAL);
        this.heat = heat;
        apply();
    }

    public void connectionReset() {
        orientation.active(false);
        watchCamera(false);
        cancelRetry();
        recovery.reset();
        ask = null;
        master = false;
        framed = false;
        declined = "";
        refusal = "";
        notice = false;
        status = "相机未开启";
        uplink.reset();
        stop("");
    }

    public void clear() {
        active = enabled = false;
        connectionReset();
        status = "相机未开启";
    }

    public void asked(JSONObject frame) {
        if (!frame.has("capture")) return;
        declined = "";
        if (frame.isNull("capture")) {
            ask = null;
            stop("");
            Object reason = frame.opt("reason");
            refusal = reason instanceof String ? (String) reason : "";
            notice = !refusal.isEmpty();
            status = "相机请求已结束";
        } else if (!framed) {
            ask = null;
            stop("");
            listener.text(Json.object("type", "camera_declined", "reason", "unframed_channel"));
            refusal = "";
            notice = true;
            status = "当前房间服务未提供视频能力";
        } else {
            int bound = frame.optInt("max_frame_bytes", 0);
            if (bound <= 0) return;
            try {
                SightProtocol.Ask next = new SightProtocol.Ask(frame.optJSONObject("capture"));
                boolean same = next.equals(ask) && maxBytes == bound;
                if (!same) recovery.reset();
                maxBytes = bound;
                ask = next;
                refusal = "";
                notice = false;
                if (same && capture != null) capture.requestKey();
                else stop("");
            } catch (IllegalArgumentException invalid) {
                ask = null;
                stop("");
                refusal = "";
                notice = true;
                status = "服务器相机参数无效";
                listener.text(Json.object("type", "camera_declined", "reason", "no_format"));
            }
        }
        apply();
        listener.changed();
    }

    public void toggle() {
        if (enabled && recovery.exhausted()) recovery.reset();
        else {
            enabled = !enabled;
            recovery.inputsChanged();
        }
        apply();
        listener.changed();
    }

    public void flip() {
        if (!frontAvailable || !rearAvailable) return;
        front = !front;
        watchCamera(false);
        recovery.reset();
        context.getSharedPreferences(dev.openduo.pocket.app.AppIdentity.SIGHT, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("front", front)
                .apply();
        stop("");
        apply();
        listener.changed();
    }

    public void keyFrame() {
        if (capture != null) capture.requestKey();
    }

    public void busy() {
        if (capture != null) capture.busy(congested.getAsBoolean());
    }

    private void stop(String reason) {
        epoch++;
        transmitting = false;
        preview = null;
        if (capture != null) {
            ownRelease = availability != null;
            SightCapture old = capture;
            capture = null;
            stopping = true;
            old.close();
            closer.execute(
                    () -> {
                        boolean done = false;
                        try {
                            done = old.awaitStopped(4_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        boolean released = done;
                        state.execute(
                                () -> {
                                    stopping = false;
                                    if (released) apply();
                                    else {
                                        enabled = false;
                                        watchCamera(false);
                                        orientation.active(false);
                                        status = "相机关闭超时，请稍后重新开启";
                                    }
                                    listener.changed();
                                });
                    });
        }
        if (!reason.isEmpty()
                && ask != null
                && connected.getAsBoolean()
                && !reason.equals(declined)) {
            declined = reason;
            listener.text(Json.object("type", "camera_declined", "reason", reason));
        }
    }

    private void watchCamera(boolean wanted) {
        String id = front ? frontCamera : rearCamera;
        if (wanted && availability != null && observedCamera.equals(id)) return;
        availabilityEpoch++;
        if (availability != null) cameras.unregisterAvailabilityCallback(availability);
        availability = null;
        observedCamera = "";
        cameraAvailable = null;
        ownRelease = false;
        if (!wanted) {
            cancelRetry();
            return;
        }
        if (cameras == null || id == null) {
            // Let the capture report the specific missing-camera failure once.
            cameraAvailable = true;
            return;
        }
        observedCamera = id;
        int ticket = availabilityEpoch;
        availability =
                new CameraManager.AvailabilityCallback() {
                    private void changed(String cameraId, boolean available) {
                        if (!id.equals(cameraId) || ticket != availabilityEpoch) return;
                        state.execute(
                                () -> {
                                    if (ticket != availabilityEpoch) return;
                                    boolean changed =
                                            cameraAvailable == null || cameraAvailable != available;
                                    cameraAvailable = available;
                                    if (available) ownRelease = false;
                                    // An unavailable event from our own open is expected. Never
                                    // preempt a peer.
                                    if (changed && capture == null && !stopping) {
                                        apply();
                                        listener.changed();
                                    }
                                });
                    }

                    @Override
                    public void onCameraAvailable(String cameraId) {
                        changed(cameraId, true);
                    }

                    @Override
                    public void onCameraUnavailable(String cameraId) {
                        changed(cameraId, false);
                    }
                };
        try {
            cameras.registerAvailabilityCallback(availability, main);
        } catch (RuntimeException failed) {
            availability = null;
            // Native open remains authoritative when availability cannot be observed.
            cameraAvailable = true;
        }
    }

    private void cancelRetry() {
        if (retryWake == null) return;
        main.removeCallbacks(retryWake);
        retryWake = null;
    }

    private void retryLater(long delay) {
        if (retryWake != null) return;
        retryWake =
                new Runnable() {
                    @Override
                    public void run() {
                        state.execute(
                                () -> {
                                    if (retryWake != this) return;
                                    retryWake = null;
                                    apply();
                                    listener.changed();
                                });
                    }
                };
        main.postDelayed(retryWake, delay);
    }

    private String failureStatus() {
        switch (recovery.failure()) {
            case "no_camera":
                return frontAvailable && rearAvailable ? "没有这一侧的相机，可切换镜头" : "这台设备没有可用的相机";
            case "no_h264":
                return "编码器不支持服务器要求的 H.264 参数";
            case "no_format":
                return "相机不支持服务器要求的尺寸或帧率";
            default:
                if (recovery.exhausted()) return "相机多次中断，请点按重试";
                return recovery.failure().equals("encoder_failed")
                        ? "视频编码暂时中断，正在重试…"
                        : "相机暂时不可用，正在重试…";
        }
    }

    private void apply() {
        boolean eligible =
                ask != null
                        && active
                        && master
                        && framed
                        && foreground
                        && enabled
                        && heat != DeviceHeat.CRITICAL
                        && context.checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED;
        boolean held = !recovery.failure().isEmpty() && !recovery.transientFailure();
        watchCamera(eligible && !held);
        orientation.active(eligible && !held);
        if (ask == null || !active || !master || !framed) {
            stop("");
            return;
        }
        if (!foreground) {
            stop("background");
            status = "切到后台，相机已暂停";
            return;
        }
        if (heat == DeviceHeat.CRITICAL) {
            stop("thermal");
            status = "设备过热，相机暂停，冷却后自动恢复";
            return;
        }
        if (!enabled) {
            stop("user_off");
            status = "房间请求相机，点按开启";
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            stop("camera_denied");
            status = "需要允许相机权限";
            return;
        }
        if (capture != null || stopping) return;
        if (held) {
            status = failureStatus();
            return;
        }
        if (cameraAvailable == null) {
            status = "正在检查相机状态…";
            return;
        }
        if (!cameraAvailable) {
            if (!ownRelease) stop("camera_taken");
            status = ownRelease ? "正在等待相机释放…" : "相机被其他应用占用，释放后自动恢复";
            return;
        }
        if (!recovery.failure().isEmpty()) {
            status = failureStatus();
            long delay = recovery.delay(SystemClock.elapsedRealtime());
            if (delay < 0) return;
            if (delay > 0) {
                retryLater(delay);
                return;
            }
            recovery.retry();
        }
        cancelRetry();
        int ticket = ++epoch;
        uplink.expectKey();
        status = "正在打开相机…";
        capture =
                new SightCapture(
                        context,
                        ask,
                        front,
                        deviceOrientation,
                        new SightCapture.Listener() {
                            @Override
                            public void started() {
                                state.execute(
                                        () -> {
                                            if (ticket != epoch || capture == null) return;
                                            capture.thermal(heat > DeviceHeat.NORMAL);
                                        });
                            }

                            @Override
                            public boolean wantsPreview() {
                                return previewWanted;
                            }

                            @Override
                            public void preview(PreviewFrame frame) {
                                if (previewWanted && ticket == epoch) preview = frame;
                            }

                            @Override
                            public void chunk(
                                    boolean key,
                                    long at,
                                    byte[] payload,
                                    int width,
                                    int height,
                                    int fps,
                                    int bitrate) {
                                state.execute(
                                        () -> {
                                            if (ticket != epoch || capture == null) return;
                                            boolean busy = congested.getAsBoolean();
                                            capture.busy(busy);
                                            byte[] frame =
                                                    uplink.frame(
                                                            key,
                                                            at,
                                                            payload,
                                                            width,
                                                            height,
                                                            front ? "facing" : "room",
                                                            fps,
                                                            bitrate,
                                                            maxBytes);
                                            if (busy) {
                                                uplink.expectKey();
                                                capture.requestKey();
                                                return;
                                            }
                                            if (frame == null) capture.requestKey();
                                            else {
                                                if (key) {
                                                    recovery.reset();
                                                    declined = "";
                                                    if (!transmitting) {
                                                        transmitting = true;
                                                        status =
                                                                front
                                                                        ? "相机已开启 · 面向你"
                                                                        : "相机已开启 · 面向房间";
                                                        listener.changed();
                                                    }
                                                }
                                                listener.binary(frame);
                                            }
                                        });
                            }

                            @Override
                            public void failed(String reason) {
                                state.execute(
                                        () -> {
                                            if (ticket != epoch) return;
                                            recovery.failed(reason, SystemClock.elapsedRealtime());
                                            stop(reason);
                                            status = failureStatus();
                                            apply();
                                            listener.changed();
                                        });
                            }
                        });
    }
}
