// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.qa;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;

import dev.openduo.pocket.app.AppIdentity;
import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.RoomPlayback;
import dev.openduo.pocket.audio.SpeechPlayer;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * One actual screen-off reply, with isolated storage, no-network frames and silent native audio.
 */
final class LockscreenPlaybackProbe {
    private static final String DATABASE = "duoduo-pocket-lockscreen-qa.db";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private Context context;
    private int assertions;

    LockscreenPlaybackProbe(Probe probe) {
        this.probe = probe;
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void field(String name, Object value) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private RoomPlayback playback() throws Exception {
        return (RoomPlayback) field("playback");
    }

    private Object playbackField(String name) throws Exception {
        Field member = RoomPlayback.class.getDeclaredField(name);
        member.setAccessible(true);
        return member.get(playback());
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(Callable<Boolean> condition, long timeout, String label) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
        check(condition.call(), label);
    }

    private boolean awake() {
        return context.getSystemService(PowerManager.class).isInteractive();
    }

    private String shell(String command) throws Exception {
        try (InputStream input =
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                probe.getUiAutomation().executeShellCommand(command));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) != -1; ) output.write(buffer, 0, n);
            return output.toString("UTF-8");
        }
    }

    private boolean service() {
        for (ActivityManager.RunningServiceInfo info :
                context.getSystemService(ActivityManager.class).getRunningServices(40))
            if (info.foreground
                    && info.service
                            .getClassName()
                            .equals("dev.openduo.pocket.app.PocketSessionService")) return true;
        return false;
    }

    private boolean wakeHeld() throws Exception {
        String power = shell("dumpsys power");
        int start = power.indexOf("Wake Locks:"), end = power.indexOf("Suspend Blockers:", start);
        if (start < 0 || end < 0)
            throw new AssertionError("Native wake-lock inventory unavailable");
        return power.substring(start, end).contains("DuoDuo Pocket:playback");
    }

    private void wake() throws Exception {
        shell("input keyevent 224");
        shell("wm dismiss-keyguard");
        context.startActivity(
                new Intent(context, MainActivity.class)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    String run(Context context) throws Exception {
        this.context = context;
        probe.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(() -> controller.snapshot() != null, 5000, "initial state is available");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.readingId.isEmpty()
                        && initial.working == null
                        && !service(),
                "screen-off probe requires idle existing work");
        Settings originalSettings = controller.settings;
        Object originalRepo = field("repo"), originalState = field("roomState");
        ChannelConnection originalChannel = (ChannelConnection) field("channel");
        RoomLink link = (RoomLink) field("liveLink");
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return super.getSharedPreferences(name + ".lockscreen-qa", mode);
                    }
                };
        Settings fixture = new Settings(isolated);
        fixture.saveConnection(
                "example.test",
                443,
                true,
                "qa",
                "Prepared lockscreen reply",
                "fixture",
                "room",
                "fixture");
        fixture.ambientOutput(true);
        context.deleteDatabase(DATABASE);
        RoomRepository repository = new RoomRepository(context, DATABASE);
        repository.select("qa:lockscreen");
        repository.metadata("history_loaded", "1");
        Source source = new Source();
        try (OpusEncoder encoder = new OpusEncoder()) {
            byte[] silence = encoder.encode(new short[320]);
            onState(
                    () -> {
                        link.invalidate();
                        originalChannel.closeLive();
                        invoke("retireRoomAccess");
                        field("settings", fixture);
                        field("repo", repository);
                        field("channel", source);
                        Object tailnet = field("tailnet");
                        Field backend = tailnet.getClass().getDeclaredField("backend");
                        backend.setAccessible(true);
                        backend.set(tailnet, "Running");
                        field("roomState", null);
                        field("retryNotBefore", 0L);
                        field("ambientMuted", false);
                        playback().mute(false);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    5000,
                    "isolated fixture is Online");
            source.text(Json.object("type", "speech", "speech_id", "c-lockscreen"));
            source.packets(silence, 200);
            await(
                    () -> onState(() -> playback().active()),
                    2000,
                    "prepared reply reaches native AudioTrack before screen-off");
            int generation = onState(() -> ((SpeechPlayer) playbackField("player")).generation());
            shell("input keyevent 223");
            await(
                    () -> !awake() && onState(() -> !(Boolean) field("foreground")),
                    3000,
                    "actual display is off and Activity has stopped");
            check(
                    onState(
                                    () ->
                                            playback().pending()
                                                    && ((SpeechPlayer) playbackField("player"))
                                                                    .generation()
                                                            == generation)
                            && source.isOpen(),
                    "screen-off preserves the existing playback generation and room connection");
            await(
                    this::service,
                    2000,
                    "a native foreground service protects playback without microphone capture");
            check(
                    !RecordingInput.isActive() && source.spoken,
                    "screen-off retains the spoken surface without opening the microphone");
            String services = shell("dumpsys activity services " + context.getPackageName());
            java.io.File evidence =
                    new java.io.File(context.getExternalCacheDir(), "lockscreen-playback");
            evidence.mkdirs();
            java.nio.file.Files.write(
                    new java.io.File(evidence, "service.txt").toPath(),
                    services.getBytes(StandardCharsets.UTF_8));
            check(
                    android.os.Build.VERSION.SDK_INT < 29
                            || services.matches(
                                    "(?s).*isForeground=true[^\\n"
                                        + "]*(?:types|foregroundServiceType)=(?:0x0*2|2)\\b.*"),
                    "native foreground service uses mediaPlayback type");
            check(wakeHeld(), "playback holds its own CPU wake lock while the screen stays off");
            // Completion may outrun the last queued audio on the server; it must not cut the tail.
            source.text(
                    Json.object(
                            "type",
                            "playback",
                            "speech_id",
                            "c-lockscreen",
                            "state",
                            "done",
                            "played_ms",
                            6000));
            source.packets(silence, 100);
            await(
                    () ->
                            onState(
                                    () ->
                                            ((AmbientOutput) playbackField("output")).playedMs()
                                                    >= 5000),
                    7000,
                    "audio received after screen-off continues on the same native timeline");
            await(
                    () -> !service() && !source.isOpen() && onState(() -> !playback().pending()),
                    4000,
                    "draining the complete reply releases the service and background connection");
            check(
                    onState(() -> ((AmbientOutput) playbackField("output")).playedMs()) == 6000
                            && !awake()
                            && !wakeHeld(),
                    "all six seconds finish with the screen off and no retained wake lock");
        } finally {
            onState(
                    () -> {
                        invoke("stopPlayback");
                        link.invalidate();
                        source.closeLive();
                        invoke("retireRoomAccess");
                        field("settings", originalSettings);
                        field("repo", originalRepo);
                        field("channel", originalChannel);
                        field("roomState", originalState);
                        field("lastError", initial.lastError);
                        field("ambientMuted", initial.ambientMuted);
                        playback().mute(initial.ambientMuted);
                        invoke("reload");
                        invoke("refreshService");
                        invoke("publish");
                        return null;
                    });
            repository.clear();
            repository.close();
            context.deleteDatabase(DATABASE);
            isolated.getSharedPreferences(AppIdentity.SETTINGS, 0).edit().clear().commit();
            isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
            wake();
        }
        await(
                () -> awake() && onState(() -> (Boolean) field("foreground")),
                3000,
                "original app returns to the foreground");
        check(
                controller.snapshot().draft.equals(initial.draft)
                        && controller.snapshot().quote.equals(initial.quote)
                        && controller.snapshot().chips.size() == initial.chips.size()
                        && controller.settings == originalSettings,
                "original settings, pairing and composer are preserved");
        return "PASS "
                + assertions
                + " bounded lockscreen playback assertions; silent native output; no microphone or"
                + " real-room sends";
    }

    private static final class Source implements ChannelConnection {
        volatile LiveListener listener;
        volatile boolean open, spoken;

        void text(JSONObject frame) {
            listener.text(frame.toString());
        }

        void packets(byte[] packet, int count) {
            for (int i = 0; i < count; i++) listener.binary(packet);
        }

        public Response request(
                Endpoint e,
                String t,
                String method,
                String path,
                JSONObject h,
                byte[] b,
                long timeout) {
            JSONObject result =
                    path.equals("/healthz")
                            ? Json.object("ok", true)
                            : path.startsWith("/api/state?")
                                    ? Json.object(
                                            "room",
                                            "qa",
                                            "date",
                                            Json.day(System.currentTimeMillis()),
                                            "daemon_ok",
                                            true,
                                            "cerebellum_ok",
                                            true)
                                    : Json.object("entries", new JSONArray(), "seq", 0);
            if (!method.equals("GET")) throw new AssertionError("No sending in lockscreen probe");
            return new Response(200, result.toString().getBytes(StandardCharsets.UTF_8));
        }

        public void live(
                Endpoint e,
                String t,
                String path,
                JSONObject h,
                LiveListener listener,
                BooleanSupplier current) {
            this.listener = listener;
            open = true;
            text(Json.object("type", "meta", "conn", "qa-lockscreen"));
            text(Json.object("type", "ready", "api", 2, "seq", 0, "messages", new JSONArray()));
        }

        public void send(JSONObject frame) {
            if (frame.optString("type").equals("surface")) spoken = frame.optBoolean("spoken");
        }

        public void sendBinary(byte[] packet) {
            throw new AssertionError("No capture in lockscreen probe");
        }

        public boolean isOpen() {
            return open;
        }

        public void closeLive() {
            open = false;
        }

        public Response stream(
                Endpoint e,
                String t,
                long id,
                String p,
                byte[] b,
                StreamListener l,
                BooleanSupplier c) {
            throw new AssertionError("No synthesis in lockscreen probe");
        }

        public void cancelStream(long id) {}
    }
}
