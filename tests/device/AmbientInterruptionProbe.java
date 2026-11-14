// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import dev.openduo.pocket.app.AnswerNotifications;
import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Actual Activity, focus, capture and local notices; isolated storage and no network delegate. */
final class AmbientInterruptionProbe {
    private static final String DATABASE = "duoduo-pocket-interruption-qa.db";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private Context context;
    private Activity screen;
    private NotificationManager notifications;
    private final Source source = new Source();
    private int assertions;

    AmbientInterruptionProbe(Probe probe) {
        this.probe = probe;
    }

    private dev.openduo.pocket.net.RoomLink link() throws Exception {
        return (dev.openduo.pocket.net.RoomLink) field("liveLink");
    }

    private Object field(String name) throws Exception {
        Field value = PocketController.class.getDeclaredField(name);
        value.setAccessible(true);
        return value.get(controller);
    }

    private void field(String name, Object value) throws Exception {
        Field target = PocketController.class.getDeclaredField(name);
        target.setAccessible(true);
        target.set(controller, value);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private boolean foreground() {
        try {
            return onState(() -> (Boolean) field("foreground"));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private StatusBarNotification interruption() {
        for (StatusBarNotification notice : notifications.getActiveNotifications())
            if ("interrupted".equals(notice.getTag()) && notice.getId() == 29) return notice;
        return null;
    }

    private void shell(String command) throws Exception {
        try (java.io.InputStream input =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        probe.getUiAutomation().executeShellCommand(command))) {
            byte[] discard = new byte[256];
            while (input.read(discard) != -1) {}
        }
    }

    private void front() throws Exception {
        shell("input keyevent 224");
        shell("wm dismiss-keyguard");
        context.startActivity(
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(context.getPackageName(), MainActivity.class.getName())
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(this::foreground, 5000);
        check(foreground(), "the real Activity returns to the foreground");
    }

    private void back() throws Exception {
        probe.runOnMainSync(() -> screen.moveTaskToBack(true));
        await(() -> !foreground(), 5000);
        check(!foreground(), "the actual Activity is stopped before background focus competition");
    }

    private void startAmbient() throws Exception {
        controller.ambientToggle();
        await(() -> controller.snapshot().ambient == PocketController.Ambient.LISTENING, 7000);
        check(
                controller.snapshot().ambient == PocketController.Ambient.LISTENING
                        && RecordingInput.isActive()
                        && source.binary.get() > 0,
                "the isolated room runs actual native capture and ordinary hello/uplink");
    }

    private AudioFocusRequest compete() throws Exception {
        AudioFocusRequest request =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                        .setOnAudioFocusChangeListener(
                                ignored -> {},
                                new android.os.Handler(android.os.Looper.getMainLooper()))
                        .build();
        check(
                context.getSystemService(AudioManager.class).requestAudioFocus(request)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                "Android grants an independent native focus request");
        return request;
    }

    private void pausedNotice() throws Exception {
        await(
                () ->
                        controller.snapshot().ambient == PocketController.Ambient.PAUSED
                                && !RecordingInput.isActive()
                                && interruption() != null,
                4000);
        StatusBarNotification notice = interruption();
        check(
                controller.snapshot().ambientActive
                        && controller.snapshot().ambient == PocketController.Ambient.PAUSED
                        && !RecordingInput.isActive()
                        && notice != null,
                "background native focus loss pauses capture and posts the local interruption"
                        + " notice");
        check(
                notice.getNotification().getChannelId().equals("interruptions")
                        && notifications.getNotificationChannel("interruptions").getSound() == null
                        && notice.getNotification().contentIntent != null,
                "the interruption notice is silent and has a native return-to-app action");
    }

    private void recoveredNotice() throws Exception {
        int before = source.binary.get();
        await(
                () ->
                        controller.snapshot().ambient == PocketController.Ambient.LISTENING
                                && source.binary.get() > before,
                7000);
        check(
                controller.snapshot().ambient == PocketController.Ambient.LISTENING
                        && RecordingInput.isActive()
                        && source.binary.get() > before,
                "native focus gain restores capture and ordinary uplink while the Activity stays"
                        + " stopped");
        await(() -> interruption() == null, 1500);
        check(
                !foreground() && interruption() == null,
                "resumed background ambient must withdraw its obsolete interrupted notice");
    }

    String run(Context context) throws Exception {
        this.context = context;
        notifications = context.getSystemService(NotificationManager.class);
        screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(() -> controller.snapshot() != null, 5000);
        PocketController.Snapshot initial = controller.snapshot();
        check(
                initial != null
                        && !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.working == null
                        && !initial.olderLoading
                        && !initial.sessions.watching,
                "interruption acceptance requires the actual room idle");
        check(
                notifications.areNotificationsEnabled(),
                "actual native local notifications are enabled");
        Settings originalSettings = controller.settings;
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        RoomRepository originalRepo = onState(() -> (RoomRepository) field("repo"));
        Object originalRoom = onState(() -> field("roomState"));
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return super.getSharedPreferences(name + ".interruption-qa", mode);
                    }
                };
        isolated.getSharedPreferences("duoduo-pocket.settings", 0).edit().clear().commit();
        isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
        Settings settings = new Settings(isolated);
        settings.saveConnection(
                "example.test", 443, true, "qa", "Interruption QA", "qa", "room", "qa");
        settings.keepListening(true);
        context.deleteDatabase(DATABASE);
        RoomRepository repository = new RoomRepository(context, DATABASE);
        repository.select("qa:interruption");
        repository.metadata("history_loaded", "1");
        AudioFocusRequest other = null;
        AudioManager audio = context.getSystemService(AudioManager.class);
        try (OpusEncoder encoder = new OpusEncoder()) {
            source.packet = encoder.encode(new short[320]);
            onState(
                    () -> {
                        invoke("retireRoomAccess");
                        link().invalidate();
                        original.closeLive();
                        field("settings", settings);
                        field("channel", source);
                        field("repo", repository);
                        field("roomState", null);
                        link().ready();
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    5000);
            startAmbient();
            back();
            other = compete();
            pausedNotice();
            long postedAt = interruption().getPostTime();
            for (int i = 0; i < 5; i++) controller.clearNotice();
            onState(() -> null);
            check(
                    interruption() != null && interruption().getPostTime() == postedAt,
                    "state publications retain one notice without posting it again");
            audio.abandonAudioFocusRequest(other);
            other = null;
            recoveredNotice();

            other = compete();
            pausedNotice();
            front();
            await(() -> interruption() == null, 1500);
            check(
                    interruption() == null
                            && !RecordingInput.isActive()
                            && controller.snapshot().ambient == PocketController.Ambient.PAUSED,
                    "returning to the real Activity withdraws the notice while ongoing loss keeps"
                            + " the mic paused");
            back();
            pausedNotice();
            controller.reopenAmbient();
            onState(() -> null);
            check(
                    interruption() != null && !RecordingInput.isActive(),
                    "an unsuccessful explicit reopen cannot hide a still-paused background"
                            + " session");
            controller.endAmbient();
            await(() -> !controller.snapshot().ambientActive && interruption() == null, 3000);
            check(
                    !controller.snapshot().ambientActive && interruption() == null,
                    "the native session stop path withdraws the interruption notice");
            audio.abandonAudioFocusRequest(other);
            other = null;
            Thread.sleep(250);
            check(
                    !RecordingInput.isActive() && !controller.snapshot().ambientActive,
                    "late native gain after stopping cannot restart ambient capture");

            front();
            startAmbient();
            check(
                    interruption() == null,
                    "a fresh ambient session has no stale interrupted notice");
            back();
            other = compete();
            pausedNotice();
            source.listener.closed("Prepared close 4401 unauthorized");
            await(() -> !controller.snapshot().ambientActive && interruption() == null, 3000);
            check(
                    settings.unauthorized()
                            && !controller.snapshot().ambientActive
                            && interruption() == null,
                    "isolated room rejection ends ambient intent and withdraws its interrupted"
                            + " notice");
            audio.abandonAudioFocusRequest(other);
            other = null;
            settings.unauthorized(false);
            front();
            onState(
                    () -> {
                        invoke("connect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    5000);
            back();
            Message read =
                    new Message(
                            "qa-read",
                            Message.DUODUO,
                            System.currentTimeMillis(),
                            "Prepared local read",
                            "",
                            0,
                            Message.DELIVERED,
                            "",
                            true,
                            false,
                            Collections.emptyList(),
                            -1);
            controller.readAloud(read);
            check(
                    source.readStarted.await(3, TimeUnit.SECONDS),
                    "a prepared background read acquires actual playback focus without ambient"
                            + " intent");
            other = compete();
            await(() -> controller.snapshot().readingId.isEmpty(), 3000);
            Thread.sleep(150);
            check(
                    controller.snapshot().readingId.isEmpty()
                            && !controller.snapshot().ambientActive
                            && interruption() == null,
                    "ordinary read focus loss must not claim that ambient mode was interrupted");
            audio.abandonAudioFocusRequest(other);
            other = null;
            source.readRelease.countDown();
            front();
            notifications.notify(
                    "interrupted",
                    29,
                    new Notification.Builder(context, "interruptions")
                            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                            .setContentTitle("Prepared prior-process interruption")
                            .build());
            await(() -> interruption() != null, 1500);
            check(interruption() != null, "a prepared prior-process native notice exists");
            new AnswerNotifications(context, settings);
            await(() -> interruption() == null, 1500);
            check(
                    interruption() == null,
                    "recreating the notification owner withdraws a notice from a session that"
                            + " cannot survive process death");
        } finally {
            source.readRelease.countDown();
            if (other != null) audio.abandonAudioFocusRequest(other);
            controller.endAmbient();
            onState(
                    () -> {
                        invoke("stopPlayback");
                        return null;
                    });
            await(() -> !RecordingInput.isActive(), 5000);
            front();
            onState(
                    () -> {
                        invoke("retireRoomAccess");
                        link().invalidate();
                        source.closeLive();
                        field("settings", originalSettings);
                        field("channel", original);
                        field("repo", originalRepo);
                        field("roomState", originalRoom);
                        link().ready();
                        field("retryNotBefore", 0L);
                        field("lastError", initial.lastError);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("restoreDraft");
                        invoke("reload");
                        invoke("connect");
                        invoke("publish");
                        return null;
                    });
            repository.close();
            context.deleteDatabase(DATABASE);
            settings.clearConnection();
            isolated.getSharedPreferences("duoduo-pocket.settings", 0).edit().clear().commit();
            isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
            notifications.cancel("interrupted", 29);
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    35_000);
            PocketController.Snapshot restored = controller.snapshot();
            check(
                    restored.connection == PocketController.Connection.ONLINE
                            && controller.settings == originalSettings
                            && restored.importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && restored.quote.equals(initial.quote)
                            && restored.chips.size() == initial.chips.size()
                            && restored.outbox.isEmpty()
                            && !restored.ambientActive
                            && !RecordingInput.isActive(),
                    "the actual paired room, Online and composer are restored without a real send");
        }
        return "PASS "
                + assertions
                + " native ambient interruption assertions; room outcomes are prepared";
    }

    private static final class Source implements ChannelConnection {
        final AtomicInteger binary = new AtomicInteger();
        final CountDownLatch readStarted = new CountDownLatch(1),
                readRelease = new CountDownLatch(1);
        volatile boolean open;
        volatile LiveListener listener;
        byte[] packet;

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            if (!method.equals("GET"))
                throw new AssertionError("Interruption probe cannot send messages");
            JSONObject value;
            if (path.equals("/healthz")) value = Json.object("ok", true);
            else if (path.startsWith("/api/state?"))
                value = Json.object("room", "qa", "date", Json.day(System.currentTimeMillis()));
            else if (path.startsWith("/api/imlog?"))
                value = Json.object("entries", new JSONArray(), "seq", 0);
            else throw new AssertionError("Unexpected interruption probe HTTP request");
            return new Response(200, value.toString().getBytes(StandardCharsets.UTF_8));
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current)
                throws Exception {
            byte[] framed = new byte[packet.length + 2];
            framed[0] = (byte) packet.length;
            framed[1] = (byte) (packet.length >>> 8);
            System.arraycopy(packet, 0, framed, 2, packet.length);
            listener.data(framed);
            readStarted.countDown();
            while (current.getAsBoolean() && !readRelease.await(20, TimeUnit.MILLISECONDS)) {}
            return new Response(200, new byte[0]);
        }

        public void cancelStream(long id) {
            readRelease.countDown();
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current) {
            if (!current.getAsBoolean()) return;
            this.listener = listener;
            open = true;
            listener.text(Json.object("type", "meta", "conn", "qa").toString());
            listener.text(
                    Json.object("type", "ready", "api", 2, "seq", 0, "turn", JSONObject.NULL)
                            .toString());
        }

        public void send(JSONObject frame) {
            if (frame.optString("type").equals("hello") && open)
                listener.text(
                        Json.object("type", "meta", "role", "master", "state", "listening")
                                .toString());
        }

        public void sendBinary(byte[] bytes) {
            binary.incrementAndGet();
        }

        public boolean isOpen() {
            return open;
        }

        public void closeLive() {
            open = false;
        }
    }
}
