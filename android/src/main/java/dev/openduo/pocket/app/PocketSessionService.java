// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import dev.openduo.pocket.core.PocketController;

/** Capture, uploads, accessory work and ongoing playback have distinct service types. */
public final class PocketSessionService extends Service implements PocketController.Listener {
    private PocketController controller;
    private boolean promoted;
    private int lastStartId;
    private PowerManager.WakeLock playbackWake;

    private dev.openduo.pocket.text.TextCatalog words() {
        return dev.openduo.pocket.text.AppText.get(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        "ambient", words().t("环境模式"), NotificationManager.IMPORTANCE_LOW));
        controller = PocketController.get(this);
        controller.addListener(this);
        controller.resumeAccessory();
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel("ambient");
        if (channel != null && !words().t("环境模式").contentEquals(channel.getName())) {
            channel.setName(words().t("环境模式"));
            manager.createNotificationChannel(channel);
        }
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        1,
                        new Intent(this, PocketSessionService.class).setAction("stop"),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PocketController.Snapshot s = controller.snapshot();
        boolean listening = s != null && s.ambientActive && !s.trying;
        boolean playing = s != null && s.playingAudio && !s.trying;
        Notification.Builder builder =
                new Notification.Builder(this, "ambient")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle(
                                listening
                                        ? words().t("多多 · 环境模式")
                                        : playing
                                                ? words().t("多多 · 正在播放语音")
                                                : s != null && s.uploadingVoice
                                                        ? words().t("多多 · 正在发送语音")
                                                        : words().t("多多"))
                        .setContentText(
                                listening
                                        ? words().explain(s.status)
                                        : playing
                                                ? words().t("锁屏后继续播放，播完自动结束")
                                                : s != null && s.uploadingVoice
                                                        ? words().t("发送完成后自动结束上传任务")
                                                        : words().t("保持 多多 的后台任务"))
                        .setContentIntent(open)
                        .setOngoing(true);
        if (listening)
            builder.addAction(
                    new Notification.Action.Builder(null, words().t("停止收音"), stop).build());
        if (playing) {
            PendingIntent stopPlayback =
                    PendingIntent.getService(
                            this,
                            2,
                            new Intent(this, PocketSessionService.class).setAction("stop_playback"),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setCategory(Notification.CATEGORY_TRANSPORT)
                    .addAction(
                            new Notification.Action.Builder(null, words().t("停止播放"), stopPlayback)
                                    .build());
        }
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            int types = 0;
            if (intent != null
                    && intent.getBooleanExtra("microphone", false)
                    && (flags & START_FLAG_REDELIVERY) == 0)
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (intent != null && intent.getBooleanExtra("bluetooth", false))
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (intent != null && intent.getBooleanExtra("upload", false))
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            PocketController.Snapshot current = controller.snapshot();
            if (intent != null && intent.getBooleanExtra("playback", false)
                    || current != null && current.playingAudio)
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (types == 0)
                types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(27, notification(), types);
        } else startForeground(27, notification());
        lastStartId = id;
        promoted = true;
        if (intent != null && "stop".equals(intent.getAction())) {
            controller.stopSessionCapture();
        }
        if (intent != null && "stop_playback".equals(intent.getAction())) {
            controller.stopSessionPlayback();
        }
        changed();
        return intent != null && intent.getBooleanExtra("bluetooth", false)
                ? START_REDELIVER_INTENT
                : START_NOT_STICKY;
    }

    @Override
    public void changed() {
        if (!promoted) return;
        PocketController.Snapshot s = controller.snapshot();
        boolean playing = s != null && !s.trying && s.playingAudio;
        if (playing) {
            if (playbackWake == null) {
                playbackWake =
                        getSystemService(PowerManager.class)
                                .newWakeLock(
                                        PowerManager.PARTIAL_WAKE_LOCK, "DuoDuo Pocket:playback");
                playbackWake.setReferenceCounted(false);
            }
            // The receipt/drain owner releases it normally; the timeout bounds platform failures.
            if (!playbackWake.isHeld()) playbackWake.acquire(10 * 60_000L);
        } else releasePlaybackWake();
        if (s != null
                && (s.trying
                        || !s.ambientActive
                                && !s.uploadingVoice
                                && !s.accessoryActive
                                && !playing)) {
            // A newer foreground start may already be queued in Android but not delivered here.
            stopSelfResult(lastStartId);
            return;
        }
        getSystemService(NotificationManager.class).notify(27, notification());
    }

    @Override
    public void onDestroy() {
        releasePlaybackWake();
        controller.removeListener(this);
        super.onDestroy();
    }

    private void releasePlaybackWake() {
        if (playbackWake != null && playbackWake.isHeld()) playbackWake.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
