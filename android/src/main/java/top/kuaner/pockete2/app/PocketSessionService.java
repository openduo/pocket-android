// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import top.kuaner.pockete2.core.PocketController;

/** Explicit ambient capture and an in-flight voice upload have distinct service types. */
public final class PocketSessionService extends Service implements PocketController.Listener {
    private PocketController controller;
    private boolean promoted;
    private int lastStartId;

    private top.kuaner.pockete2.text.TextCatalog words() {
        return top.kuaner.pockete2.text.AppText.get(this);
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
        Notification.Builder builder =
                new Notification.Builder(this, "ambient")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle(
                                listening
                                        ? words().t("Pocket E2 · 环境模式")
                                        : s != null && s.uploadingVoice
                                                ? words().t("Pocket E2 · 正在发送语音")
                                                : "Pocket E2")
                        .setContentText(
                                listening
                                        ? words().explain(s.status)
                                        : s != null && s.uploadingVoice
                                                ? words().t("发送完成后自动结束上传任务")
                                                : words().t("保持 Pocket E2 的后台任务"))
                        .setContentIntent(open)
                        .setOngoing(true);
        if (listening)
            builder.addAction(
                    new Notification.Action.Builder(null, words().t("停止收音"), stop).build());
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
            if (types == 0)
                types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(27, notification(), types);
        } else startForeground(27, notification());
        lastStartId = id;
        promoted = true;
        if (intent != null && "stop".equals(intent.getAction())) {
            controller.stopSessionCapture();
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
        if (s != null
                && (s.trying || !s.ambientActive && !s.uploadingVoice && !s.accessoryActive)) {
            // A newer foreground start may already be queued in Android but not delivered here.
            stopSelfResult(lastStartId);
            return;
        }
        getSystemService(NotificationManager.class).notify(27, notification());
    }

    @Override
    public void onDestroy() {
        controller.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
