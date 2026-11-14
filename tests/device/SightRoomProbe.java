// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Actual server camera offer and uplink, with native microphone bytes replaced by silence. */
final class SightRoomProbe {
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private int assertions;

    SightRoomProbe(Probe probe) {
        this.probe = probe;
    }

    private dev.openduo.pocket.net.RoomLink link() throws Exception {
        return (dev.openduo.pocket.net.RoomLink) field("liveLink");
    }

    private Object playbackField(String name) throws Exception {
        Object owner = field("playback");
        Field member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void connection(ChannelConnection value) throws Exception {
        Field field = PocketController.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(controller, value);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T owner(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void await(BooleanSupplier condition, long milliseconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(30);
    }

    private JSONObject remote(ChannelConnection transport) throws Exception {
        ChannelConnection.Response response =
                transport.request(
                        controller.settings.endpoint(),
                        controller.settings.token(),
                        "GET",
                        "/api/state?room=" + Endpoint.query(controller.settings.room()),
                        Json.object(),
                        new byte[0],
                        20_000);
        check(response.status == 200, "actual room state remains readable");
        return new JSONObject(new String(response.body, StandardCharsets.UTF_8));
    }

    String run(Context context) throws Exception {
        MainActivity screen =
                (MainActivity)
                        probe.startActivitySync(
                                new Intent(Intent.ACTION_MAIN)
                                        .setClass(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(() -> screen.getWindow().addFlags(128));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                35_000);
        PocketController.Snapshot initial = controller.snapshot();
        check(
                initial != null
                        && controller.configured()
                        && !initial.trying
                        && initial.connection == PocketController.Connection.ONLINE,
                "actual Sight starts in the already paired room");
        check(
                !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty(),
                "actual Sight requires idle capture, playback and outgoing work");
        ChannelConnection original = owner(() -> (ChannelConnection) field("channel"));
        JSONObject before = remote(original);
        JSONObject capture = before.optJSONObject("capture");
        check(
                capture != null && capture.has("owner") && capture.isNull("owner"),
                "actual Sight will not take over another capture owner");
        File directory = new File(context.getExternalCacheDir(), "sight-room");
        directory.mkdirs();
        JSONObject report =
                Json.object(
                        "scope",
                        "actual offer/uplink; no synthetic camera control, no microphone content",
                        "api",
                        android.os.Build.VERSION.SDK_INT,
                        "outcome",
                        "running");
        Observed observed = null;
        String result = "NOT_RUN actual Sight";
        try (OpusEncoder encoder = new OpusEncoder()) {
            observed = new Observed(original, encoder.encode(new short[320]));
            Observed source = observed;
            owner(
                    () -> {
                        connection(source);
                        invoke("reconnect");
                        return null;
                    });
            await(() -> source.meta, 12_000);
            check(source.meta, "actual server sends metadata before ambient activation");
            controller.ambientToggle();
            owner(
                    () -> {
                        invoke("ensurePlayer");
                        ((dev.openduo.pocket.audio.SpeechPlayer) playbackField("player"))
                                .silent(true);
                        return null;
                    });
            await(() -> source.role.equals("master") && source.audio.get() > 0, 15_000);
            check(
                    source.role.equals("master")
                            && source.audio.get() > 0
                            && RecordingInput.isActive(),
                    "actual service admits this capture seat and receives native-paced silence");
            report.put("mediaConfirmed", source.framed);
            if (!source.framed) {
                report.put("outcome", "not_run")
                        .put("reason", "actual server did not confirm media=1");
                result = "NOT_RUN actual Sight: server did not confirm media=1";
            } else {
                await(() -> controller.snapshot().cameraOffered || source.refused, 20_000);
                report.put("offered", controller.snapshot().cameraOffered);
                if (!controller.snapshot().cameraOffered) {
                    report.put("outcome", "not_run")
                            .put("reason", "no actual camera capture offer");
                    result = "NOT_RUN actual Sight: no actual camera capture offer";
                } else {
                    controller.toggleSight();
                    await(() -> source.video.get() >= 5 || source.refused, 20_000);
                    report.put("videoFrames", source.video.get()).put("firstKey", source.firstKey);
                    check(
                            source.video.get() >= 5
                                    && source.firstKey
                                    && controller.snapshot().cameraOn,
                            "real service offer starts native key/delta video without prepared"
                                    + " control frames");
                    check(!source.refused, "actual server does not refuse the transmitted stream");
                    int sent = source.video.get();
                    controller.toggleSight();
                    await(() -> !controller.snapshot().cameraOn, 5000);
                    Thread.sleep(700);
                    check(
                            source.video.get() <= sent + 1,
                            "explicit camera off stops the actual uplink");
                    report.put("outcome", "negotiation_and_uplink_passed")
                            .put("recognisableSceneAccepted", false);
                    result =
                            "PASS actual Sight negotiation/uplink; recognisable scene remains"
                                    + " separate";
                }
            }
        } finally {
            controller.endAmbient();
            await(() -> !controller.snapshot().ambientActive && !RecordingInput.isActive(), 12_000);
            owner(
                    () -> {
                        invoke("stopPlayback");
                        dev.openduo.pocket.audio.SpeechPlayer player =
                                (dev.openduo.pocket.audio.SpeechPlayer) playbackField("player");
                        if (player != null) player.silent(initial.ambientMuted);
                        return null;
                    });
            // Wait for the ordinary mic-restoration writer turn before retiring its connection.
            long until = SystemClock.elapsedRealtime() + 12_000;
            while (owner(() -> link().closing()) && SystemClock.elapsedRealtime() < until)
                Thread.sleep(30);
            owner(
                    () -> {
                        connection(original);
                        invoke("reconnect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    35_000);
            check(
                    !RecordingInput.isActive()
                            && !controller.snapshot().cameraOn
                            && controller.snapshot().connection
                                    == PocketController.Connection.ONLINE
                            && controller.snapshot().importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && controller.snapshot().quote.equals(initial.quote)
                            && controller.snapshot().chips.size() == initial.chips.size()
                            && controller.snapshot().outbox.isEmpty(),
                    "actual Sight cleanup releases resources and preserves"
                            + " pairing/composer/Online");
            JSONObject after = remote(original);
            report.put(
                    "roomMicRestored",
                    before.optJSONObject("controls") != null
                            && after.optJSONObject("controls") != null
                            && before.getJSONObject("controls").optBoolean("mute")
                                    == after.getJSONObject("controls").optBoolean("mute"));
            check(
                    report.getBoolean("roomMicRestored"),
                    "actual room microphone setting is restored");
            report.put("assertions", assertions);
            if (observed != null) {
                report.put("silentAudioPackets", observed.audio.get());
                report.put("videoFrames", observed.video.get());
                report.put("events", new JSONArray(observed.events));
            }
            Files.write(
                    new File(directory, "result.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
            probe.runOnMainSync(() -> screen.navigate("conversation"));
        }
        return result + "; " + assertions + " guards/restoration checks";
    }

    private static final class Observed implements ChannelConnection {
        final ChannelConnection delegate;
        final byte[] silence;
        final AtomicInteger audio = new AtomicInteger(), video = new AtomicInteger();
        final CopyOnWriteArrayList<JSONObject> events = new CopyOnWriteArrayList<>();
        volatile boolean meta, framed, refused, firstKey;
        volatile String role = "";

        Observed(ChannelConnection delegate, byte[] silence) {
            this.delegate = delegate;
            this.silence = silence;
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            return delegate.request(endpoint, token, method, path, headers, body, timeout);
        }

        public void beginRequest(long id) throws Exception {
            delegate.beginRequest(id);
        }

        public void endRequest(long id) {
            delegate.endRequest(id);
        }

        public Response requestScoped(
                long id,
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            return delegate.requestScoped(
                    id, endpoint, token, method, path, headers, body, timeout);
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
            return delegate.stream(endpoint, token, id, path, body, listener, current);
        }

        public void cancelStream(long id) {
            delegate.cancelStream(id);
        }

        public void ping(long timeout) throws Exception {
            delegate.ping(timeout);
        }

        public void rebind() {
            delegate.rebind();
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void closeLive() {
            delegate.closeLive();
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current)
                throws Exception {
            meta = framed = refused = firstKey = false;
            role = "";
            delegate.live(
                    endpoint,
                    token,
                    path,
                    headers,
                    new LiveListener() {
                        public void text(String text) {
                            try {
                                JSONObject value = new JSONObject(text);
                                String type = value.optString("type");
                                if (type.equals("meta")) {
                                    meta = true;
                                    if (value.has("media")) framed = value.optInt("media") == 1;
                                    role = value.optString("role", role);
                                }
                                if (type.equals("camera")
                                        && value.isNull("capture")
                                        && !value.optString("reason").isEmpty()) refused = true;
                                if ((type.equals("meta") || type.equals("camera"))
                                        && events.size() < 50) {
                                    // Retain capability fields only, excluding connection and room
                                    // ids.
                                    JSONObject summary = Json.object("type", type);
                                    for (String key :
                                            new String[] {
                                                "media",
                                                "capture",
                                                "max_frame_bytes",
                                                "reason",
                                                "role"
                                            }) if (value.has(key)) summary.put(key, value.get(key));
                                    events.add(summary);
                                }
                            } catch (org.json.JSONException ignored) {
                            }
                            listener.text(text);
                        }

                        public void binary(byte[] bytes) {
                            listener.binary(bytes);
                        }

                        public void closed(String reason) {
                            listener.closed(reason);
                        }
                    },
                    current);
        }

        public void send(JSONObject frame) throws Exception {
            if (frame.optString("type").equals("hello") && role.equals("peer"))
                throw new java.io.IOException("Actual Sight refuses capture takeover");
            delegate.send(frame);
        }

        public void sendBinary(byte[] bytes) throws Exception {
            if (framed && bytes.length > 1 && bytes[0] == 2) {
                delegate.sendBinary(bytes);
                if (video.getAndIncrement() == 0) firstKey = (bytes[1] & 1) != 0;
            } else {
                byte[] packet = silence;
                if (framed) {
                    packet = new byte[silence.length + 1];
                    packet[0] = 1;
                    System.arraycopy(silence, 0, packet, 1, silence.length);
                }
                delegate.sendBinary(packet);
                audio.incrementAndGet();
            }
        }
    }
}
