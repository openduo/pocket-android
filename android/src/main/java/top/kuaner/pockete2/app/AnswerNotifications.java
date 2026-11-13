// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.app.*;
import android.content.*;

import org.json.*;

import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.text.Markdown;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Local background notices deduplicate live/log answers and leave Passport as the audible alert.
 */
public final class AnswerNotifications {
    private final Context context;
    private final NotificationManager manager;
    private final SharedPreferences prefs;
    private final Settings settings;
    private boolean foreground;

    private top.kuaner.pockete2.text.TextCatalog words() {
        return top.kuaner.pockete2.text.AppText.get(context);
    }

    public AnswerNotifications(Context context, Settings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        manager = context.getSystemService(NotificationManager.class);
        prefs = context.getSharedPreferences("answer.notices", Context.MODE_PRIVATE);
        manager.createNotificationChannel(
                new NotificationChannel(
                        "answers", words().t("后台回答"), NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel silent =
                new NotificationChannel(
                        "answers-silent",
                        words().t("配件已提醒的回答"),
                        NotificationManager.IMPORTANCE_LOW);
        silent.setSound(null, null);
        manager.createNotificationChannel(silent);
        NotificationChannel interruptions =
                new NotificationChannel(
                        "interruptions", words().t("音频中断"), NotificationManager.IMPORTANCE_DEFAULT);
        interruptions.setSound(null, null);
        manager.createNotificationChannel(interruptions);
    }

    public void foreground(boolean value) {
        foreground = value;
        if (value) {
            for (StatusBarNotificationCompat notice : active())
                manager.cancel(notice.tag, notice.id);
        }
    }

    public void language() {
        for (String[] entry :
                new String[][] {
                    {"answers", "后台回答"}, {"answers-silent", "配件已提醒的回答"}, {"interruptions", "音频中断"}
                }) {
            NotificationChannel channel = manager.getNotificationChannel(entry[0]);
            if (channel != null) {
                channel.setName(words().t(entry[1]));
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static final class StatusBarNotificationCompat {
        final String tag;
        final int id;

        StatusBarNotificationCompat(String tag, int id) {
            this.tag = tag;
            this.id = id;
        }
    }

    private List<StatusBarNotificationCompat> active() {
        List<StatusBarNotificationCompat> result = new ArrayList<>();
        for (android.service.notification.StatusBarNotification n :
                manager.getActiveNotifications())
            if (n.getTag() != null && n.getTag().startsWith("answer:"))
                result.add(new StatusBarNotificationCompat(n.getTag(), n.getId()));
        return result;
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(
                context,
                0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void interrupted() {
        if (!foreground)
            try {
                manager.notify(
                        "interrupted",
                        29,
                        new Notification.Builder(context, "interruptions")
                                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                                .setContentTitle(words().t("环境模式被打断了"))
                                .setContentText(words().t("点这里返回 Pocket E2，继续听房间里的声音"))
                                .setContentIntent(open())
                                .setAutoCancel(true)
                                .build());
            } catch (SecurityException ignored) {
            }
    }

    public void frame(JSONObject frame, boolean passportAlert) {
        frame(frame, passportAlert, true);
    }

    public void frame(JSONObject frame, boolean passportAlert, boolean notify) {
        String type = frame.optString("type");
        JSONObject answer = type.equals("row") ? frame.optJSONObject("row") : frame;
        if (answer == null || !type.equals("answer_final") && !type.equals("row")) return;
        if (type.equals("row")
                && !answer.optString("kind").equals("answer")
                && !answer.optString("speaker").equals("多多")) return;
        String text = answer.optString("full_text", answer.optString("text")).trim();
        if (text.isEmpty()) return;
        List<String> ids = Json.strings(answer.optJSONArray("message_ids"));
        Collections.sort(ids);
        String room =
                settings.hasEndpoint()
                        ? settings.endpoint().identity() + "|" + settings.room()
                        : "";
        String key = signature(room + "\n" + String.join(",", ids) + "\n" + text);
        String stored = prefs.getString("seen", "");
        List<String> seen = new ArrayList<>(Arrays.asList(stored.split("\\|")));
        if (seen.contains(key)) return;
        seen.add(key);
        while (seen.size() > 128) seen.remove(0);
        prefs.edit().putString("seen", String.join("|", seen)).apply();
        if (foreground || !notify || !settings.answerNotifications()) return;
        try {
            String plain = Markdown.spoken(text);
            manager.notify(
                    "answer:" + key,
                    28,
                    new Notification.Builder(context, passportAlert ? "answers-silent" : "answers")
                            .setSmallIcon(android.R.drawable.ic_dialog_email)
                            .setContentTitle(words().t("Pocket E2 · 新回答"))
                            .setContentText(plain)
                            .setStyle(new Notification.BigTextStyle().bigText(plain))
                            .setGroup("pocket-e2-answers")
                            .setContentIntent(open())
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .build());
        } catch (SecurityException ignored) {
        }
    }

    private static String signature(String value) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++)
                out.append(String.format(Locale.ROOT, "%02x", hash[i] & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
