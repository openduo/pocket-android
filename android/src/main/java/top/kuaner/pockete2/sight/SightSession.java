// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.sight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Json;

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
        public final boolean offered, on, front, canFlip;
        public final String status;
        public final int rotation;

        Snapshot(SightSession session) {
            offered = session.ask != null && session.active && session.master;
            on = session.capture != null && session.transmitting;
            front = session.front;
            canFlip = session.frontAvailable && session.rearAvailable;
            status = session.status;
            rotation = session.deviceOrientation;
        }
    }

    private final Context context;
    private final Executor state, closer;
    private final Listener listener;
    private final BooleanSupplier connected, congested;
    private final SightProtocol.Uplink uplink = new SightProtocol.Uplink();
    private SightProtocol.Ask ask;
    private SightCapture capture;
    private boolean active, master, framed, foreground, enabled, front, stopping, transmitting;
    private final boolean frontAvailable, rearAvailable;
    private final DeviceOrientation orientation;
    private volatile boolean previewWanted;
    private volatile PreviewFrame preview;
    private int heat, maxBytes, deviceOrientation;
    private volatile int epoch;
    private String status = "相机未开启", declined = "";

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
        boolean hasFront = false, hasRear = false;
        try {
            android.hardware.camera2.CameraManager manager =
                    context.getSystemService(android.hardware.camera2.CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                Integer facing =
                        manager.getCameraCharacteristics(id)
                                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                hasFront |=
                        facing != null
                                && facing
                                        == android.hardware.camera2.CameraCharacteristics
                                                .LENS_FACING_FRONT;
                hasRear |=
                        facing != null
                                && facing
                                        == android.hardware.camera2.CameraCharacteristics
                                                .LENS_FACING_BACK;
            }
        } catch (android.hardware.camera2.CameraAccessException | RuntimeException ignored) {
        }
        frontAvailable = hasFront;
        rearAvailable = hasRear;
        front =
                hasFront
                        && (!hasRear
                                || context.getSharedPreferences(
                                                "pocket-e2.sight", Context.MODE_PRIVATE)
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
        if (capture != null) stop("");
        apply();
        listener.changed();
    }

    public void update(
            boolean active, boolean master, boolean framed, boolean foreground, int heat) {
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
        ask = null;
        framed = false;
        declined = "";
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
            status = frame.optString("reason", "相机请求已结束");
        } else if (!framed) {
            ask = null;
            stop("");
            listener.text(Json.object("type", "camera_declined", "reason", "unframed_channel"));
            status = "房间不支持视频，请更新 ambient";
        } else {
            int bound = frame.optInt("max_frame_bytes", 0);
            if (bound <= 0) return;
            try {
                SightProtocol.Ask next = new SightProtocol.Ask(frame.optJSONObject("capture"));
                boolean same = next.equals(ask) && maxBytes == bound;
                maxBytes = bound;
                ask = next;
                if (same && capture != null) capture.requestKey();
                else stop("");
                apply();
            } catch (IllegalArgumentException invalid) {
                ask = null;
                stop("");
                status = "服务器相机参数无效";
                listener.text(Json.object("type", "camera_declined", "reason", "no_format"));
            }
        }
        listener.changed();
    }

    public void toggle() {
        enabled = !enabled;
        apply();
        listener.changed();
    }

    public void flip() {
        if (!frontAvailable || !rearAvailable) return;
        front = !front;
        context.getSharedPreferences("pocket-e2.sight", Context.MODE_PRIVATE)
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
        orientation.active(false);
        epoch++;
        transmitting = false;
        preview = null;
        if (capture != null) {
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

    private void apply() {
        orientation.active(ask != null && active && master && framed && foreground && enabled);
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
                                            enabled = false;
                                            stop(reason);
                                            status =
                                                    reason.equals("no_camera")
                                                            ? "没有这一侧的相机，可切换镜头"
                                                            : reason.equals("no_h264")
                                                                    ? "编码器不支持服务器要求的 H.264 参数"
                                                                    : reason.equals("no_format")
                                                                            ? "相机不支持服务器要求的尺寸或帧率"
                                                                            : "相机被占用或编码已中断，可重新开启";
                                            listener.changed();
                                        });
                            }
                        });
    }
}
