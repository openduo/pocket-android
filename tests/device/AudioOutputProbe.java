// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.Context;

import org.json.JSONObject;

import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.audio.SpeechPlayer;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Prepared protocol stimuli, real Opus/AudioTrack and delayed receipts on the normal state owner.
 */
final class AudioOutputProbe {
    private final PocketController controller;
    private final Executor state;
    private final Observed source;
    private final ChannelConnection original;
    private int assertions;
    private byte[] silence;

    AudioOutputProbe(PocketController controller) throws Exception {
        this.controller = controller;
        state = (Executor) field("state");
        original = (ChannelConnection) field("channel");
        source = new Observed(original);
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private <T> T onState(Callable<T> task) throws Exception {
        FutureTask<T> work = new FutureTask<>(task);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void frame(JSONObject value) throws Exception {
        Method method =
                PocketController.class.getDeclaredMethod("frame", String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(controller, value.toString(), true);
    }

    private void speech(String id, int packets) throws Exception {
        frame(Json.object("type", "speech", "speech_id", id));
        packets(packets);
    }

    private void packets(int count) throws Exception {
        Method method = PocketController.class.getDeclaredMethod("binary", byte[].class);
        method.setAccessible(true);
        for (int i = 0; i < count; i++) method.invoke(controller, (Object) silence);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private boolean pending() {
        try {
            return onState(() -> ((SpeechPlayer) field("player")).hasPending());
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    int run(Context context) throws Exception {
        check(
                controller.snapshot().trying,
                "output fault injection is restricted to the disposable try room");
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        String draft = controller.draft();
        int outbox = controller.snapshot().outbox.size();
        try (OpusEncoder encoder = new OpusEncoder()) {
            silence = encoder.encode(new short[320]);
            onState(
                    () -> {
                        Field channel = PocketController.class.getDeclaredField("channel");
                        channel.setAccessible(true);
                        channel.set(controller, source);
                        return null;
                    });
            controller.ambientToggle();
            await(() -> controller.snapshot().ambient == PocketController.Ambient.LISTENING, 7000);
            check(
                    controller.snapshot().ambient == PocketController.Ambient.LISTENING
                            && source.binary.get() > 0,
                    "the prepared output probe keeps the real array capture and ordinary"
                            + " hello/uplink active");

            controller.ambientMute();
            onState(
                    () -> {
                        speech("c-qa-silent", 120);
                        return null;
                    });
            await(() -> source.played("c-qa-silent") == 2400, 1500);
            check(
                    source.played("c-qa-silent") == 2400 && !pending(),
                    "2.4 seconds of new muted room speech acknowledge immediately without entering"
                            + " AudioTrack");

            controller.ambientMute();
            onState(
                    () -> {
                        speech("c-qa-mixed", 60);
                        return null;
                    });
            await(() -> controller.snapshot().ambient == PocketController.Ambient.SPEAKING, 1500);
            check(pending(), "audible-mode zero PCM enters the real hardware timeline");
            controller.ambientMute();
            onState(
                    () -> {
                        packets(40);
                        return null;
                    });
            await(() -> source.played("c-qa-mixed") >= 800, 1000);
            check(
                    source.played("c-qa-mixed") >= 800,
                    "muting an existing queue immediately counts newly skipped PCM");
            await(() -> source.played("c-qa-mixed") == 2000 && !pending(), 3500);
            check(
                    source.played("c-qa-mixed") == 2000 && source.monotonic("c-qa-mixed"),
                    "queued hardware and skipped PCM finish with one monotonic exact-duration"
                            + " watermark");

            controller.ambientMute();
            onState(
                    () -> {
                        speech("c-qa-redeclared", 20);
                        speech("c-qa-redeclared", 0);
                        return null;
                    });
            await(() -> !pending(), 2000);
            check(
                    source.played("c-qa-redeclared") == 0
                            && onState(() -> ((AmbientOutput) field("ambientOutput")).playedMs())
                                    == 0,
                    "a repeated declaration with the same wire id cannot credit old buffered PCM to"
                            + " the new stream");
            onState(
                    () -> {
                        speech("s-qa-filler", 80);
                        frame(Json.object("type", "stop_audio"));
                        return null;
                    });
            check(
                    onState(
                            () ->
                                    ((AmbientOutput) field("ambientOutput"))
                                            .id()
                                            .equals("s-qa-filler")),
                    "a room stop preserves the filler owner and buffered PCM");
            controller.beginHold();
            onState(() -> null);
            await(() -> source.count("interrupted", "s-qa-filler") == 1, 1000);
            check(
                    source.count("interrupted", "s-qa-filler") == 1
                            && controller.snapshot().holdRecording,
                    "holding the real phone input interrupts a filler exactly once");
            onState(
                    () -> {
                        speech("c-qa-dropped", 30);
                        return null;
                    });
            await(() -> source.count("interrupted", "c-qa-dropped") == 1 && !pending(), 1500);
            check(
                    source.count("interrupted", "c-qa-dropped") == 1 && !pending(),
                    "speech arriving during a press is dropped and reported without playback");
            controller.releaseHold(true);
            onState(
                    () -> {
                        packets(20);
                        return null;
                    });
            check(
                    !pending(),
                    "a dropped speech's late packets stay dropped after the hold is cancelled");

            // Hold state while actual hardware callbacks queue behind it. Reuse the same speech
            // id after the stop, so identity checks alone cannot reject the old callbacks.
            onState(
                    () -> {
                        speech("c-qa-stale", 80);
                        Thread.sleep(380);
                        frame(Json.object("type", "stop_audio", "speech_id", "c-qa-stale"));
                        speech("c-qa-stale", 0);
                        return null;
                    });
            Thread.sleep(200);
            onState(() -> null);
            check(
                    source.played("c-qa-stale") == 0
                            && source.count("interrupted", "c-qa-stale") == 0
                            && onState(() -> ((AmbientOutput) field("ambientOutput")).playedMs())
                                    == 0
                            && controller.snapshot().ambient != PocketController.Ambient.SPEAKING,
                    "a remote stop emits no local interruption and rejects delayed hardware"
                            + " callbacks even when the id is reused");
            focus(context);
            readOverlap();
            controller.endAmbient();
            onState(() -> null);
            check(
                    !controller.snapshot().ambientMuted
                            && !controller.snapshot().ambientActive
                            && onState(
                                    () -> {
                                        Field silent =
                                                SpeechPlayer.class.getDeclaredField("silent");
                                        silent.setAccessible(true);
                                        return !(Boolean) silent.get(field("player"));
                                    }),
                    "ending a muted ambient session restores output for later message-mode replies"
                            + " and local reads");
            shortReadFailure();
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().outbox.size() == outbox,
                    "output/hold fault injection sends no messages and preserves the composer");
            return assertions;
        } finally {
            controller.releaseHold(true);
            if (controller.snapshot().ambientMuted) controller.ambientMute();
            controller.endAmbient();
            onState(
                    () -> {
                        Field channel = PocketController.class.getDeclaredField("channel");
                        channel.setAccessible(true);
                        channel.set(controller, original);
                        return null;
                    });
            await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        }
    }

    private void shortReadFailure() throws Exception {
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        Message proof =
                controller.snapshot().rows.stream()
                        .filter(row -> row.kind == Message.DUODUO && row.id.startsWith("ans:"))
                        .findFirst()
                        .get();
        source.shortPacket = silence;
        try {
            controller.clearNotice();
            controller.readAloud(proof);
            await(() -> source.shortStarted.getCount() == 0 && !pending(), 2500);
            boolean focusHeld =
                    onState(
                            () -> {
                                Field granted =
                                        top.kuaner.pockete2.audio.AudioFocus.class.getDeclaredField(
                                                "granted");
                                granted.setAccessible(true);
                                return (Boolean) granted.get(field("audioFocus"));
                            });
            check(
                    source.shortStarted.getCount() == 0
                            && controller.snapshot().readingId.equals(proof.id)
                            && onState(() -> ((AmbientOutput) field("ambientOutput")).playedMs())
                                    == 20
                            && focusHeld,
                    "a short read's hardware drain retains reading state and focus while the HTTP"
                        + " stream is still open");
            source.shortRelease.countDown();
            await(
                    () ->
                            controller.snapshot().readingId.isEmpty()
                                    && controller.snapshot().lastError.equals("朗读中断，已播放的不是完整回答"),
                    2000);
            check(
                    controller.snapshot().readingId.isEmpty()
                            && controller.snapshot().lastError.equals("朗读中断，已播放的不是完整回答")
                            && onState(() -> !(Boolean) field("readInFlight"))
                            && !pending(),
                    "an injected stream failure after hardware drain clears the spinner and"
                        + " explicitly reports partial playback");
        } finally {
            source.shortPacket = null;
            source.shortRelease.countDown();
            controller.clearNotice();
        }
    }

    private void readOverlap() throws Exception {
        Message proof =
                controller.snapshot().rows.stream()
                        .filter(row -> row.kind == Message.DUODUO && row.id.startsWith("ans:"))
                        .findFirst()
                        .get();
        controller.ambientMute();
        onState(
                () -> {
                    speech("c-qa-before-read", 10);
                    return null;
                });
        controller.readAloud(proof);
        await(
                () ->
                        controller.snapshot().readingId.equals(proof.id)
                                && source.count("interrupted", "c-qa-before-read") == 1,
                1500);
        check(
                controller.snapshot().readingId.equals(proof.id)
                        && source.count("interrupted", "c-qa-before-read") == 1,
                "starting a prepared local read interrupts the room speech exactly once");
        onState(
                () -> {
                    frame(Json.object("type", "stop_audio"));
                    return null;
                });
        check(
                controller.snapshot().readingId.equals(proof.id),
                "an actual local read survives the room's unrelated stop_audio");
        onState(
                () -> {
                    speech("c-qa-live-priority", 10);
                    return null;
                });
        await(() -> source.played("c-qa-live-priority") == 200 && !pending(), 1500);
        Thread.sleep(100);
        check(
                controller.snapshot().readingId.isEmpty()
                        && onState(() -> !(Boolean) field("readInFlight"))
                        && source.played("c-qa-live-priority") == 200
                        && !pending()
                        && source.count("interrupted", "read:" + proof.id) == 0,
                "a new live speech cancels the local stream and discards its late packets without"
                        + " room read receipts");
        controller.ambientHush();
        await(() -> source.count("hush", "") == 1, 1000);
        check(
                source.count("hush", "") == 1
                        && source.count("interrupted", "c-qa-live-priority") == 0
                        && onState(() -> ((AmbientOutput) field("ambientOutput")).id().isEmpty()),
                "local hush clears output and sends only hush rather than a second interruption");
    }

    private void focus(Context context) throws Exception {
        android.media.AudioManager manager =
                context.getSystemService(android.media.AudioManager.class);
        for (boolean temporary : new boolean[] {true, false}) {
            android.media.AudioFocusRequest other =
                    new android.media.AudioFocusRequest.Builder(
                                    temporary
                                            ? android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                                            : android.media.AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(
                                    new android.media.AudioAttributes.Builder()
                                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                            .setContentType(
                                                    android.media.AudioAttributes
                                                            .CONTENT_TYPE_MUSIC)
                                            .build())
                            .setOnAudioFocusChangeListener(
                                    ignored -> {},
                                    new android.os.Handler(android.os.Looper.getMainLooper()))
                            .build();
            try {
                check(
                        manager.requestAudioFocus(other)
                                == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                        "an independent native focus request is granted by Android: transient="
                                + temporary);
                await(
                        () ->
                                controller.snapshot().ambient == PocketController.Ambient.PAUSED
                                        && !top.kuaner.pockete2.audio.input.RecordingInput
                                                .isActive(),
                        4000);
                check(
                        controller.snapshot().ambient == PocketController.Ambient.PAUSED
                                && controller.snapshot().ambientActive
                                && !top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                        "a real system focus loss pauses capture while retaining ambient intent");
                await(() -> source.livePath.contains("audio=0"), 3000);
                check(
                        source.livePath.contains("audio=0"),
                        "the interrupted device releases its seat and reopens without becoming a"
                                + " room audio output");
                onState(
                        () -> {
                            frame(Json.object("type", "meta", "state", "unowned"));
                            return null;
                        });
                controller.onForeground(true);
                Thread.sleep(400);
                check(
                        controller.snapshot().ambient == PocketController.Ambient.PAUSED
                                && !top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                        "unowned seat metadata and a foreground callback cannot resume capture or"
                                + " hide an ongoing focus loss");
            } finally {
                manager.abandonAudioFocusRequest(other);
            }
            if (!temporary) {
                Thread.sleep(300);
                boolean regained =
                        onState(
                                () ->
                                        !((top.kuaner.pockete2.audio.AudioFocus)
                                                        field("audioFocus"))
                                                .interrupted());
                check(
                        regained
                                || controller.snapshot().ambient == PocketController.Ambient.PAUSED,
                        "a GAIN request's release resumes only after a native gain callback or an"
                                + " explicit user action");
                if (!regained) controller.takeover();
            }
            int before = source.binary.get();
            await(
                    () ->
                            controller.snapshot().ambient == PocketController.Ambient.LISTENING
                                    && source.binary.get() > before,
                    7000);
            check(
                    controller.snapshot().ambient == PocketController.Ambient.LISTENING
                            && top.kuaner.pockete2.audio.input.RecordingInput.isActive()
                            && source.binary.get() > before
                            && source.livePath.contains("audio=1"),
                    "focus recovery restarts native capture and its ordinary hello/uplink:"
                            + " transient="
                            + temporary);
        }
    }

    private static final class Observed implements ChannelConnection {
        final ChannelConnection delegate;
        volatile String livePath = "";
        volatile byte[] shortPacket;
        final CountDownLatch shortStarted = new CountDownLatch(1),
                shortRelease = new CountDownLatch(1);
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger binary =
                new java.util.concurrent.atomic.AtomicInteger();

        Observed(ChannelConnection delegate) {
            this.delegate = delegate;
        }

        long played(String id) {
            long result = 0;
            for (JSONObject frame : sent)
                if (frame.optString("type").equals("played")
                        && frame.optString("speech_id").equals(id))
                    result = Math.max(result, frame.optLong("ms"));
            return result;
        }

        int count(String type, String id) {
            int result = 0;
            for (JSONObject frame : sent)
                if (frame.optString("type").equals(type) && frame.optString("speech_id").equals(id))
                    result++;
            return result;
        }

        boolean monotonic(String id) {
            long previous = 0;
            for (JSONObject frame : sent)
                if (frame.optString("type").equals("played")
                        && frame.optString("speech_id").equals(id)) {
                    if (frame.optLong("ms") <= previous) return false;
                    previous = frame.optLong("ms");
                }
            return true;
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

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current)
                throws Exception {
            byte[] packet = shortPacket;
            if (packet != null) {
                byte[] framed = new byte[packet.length + 2];
                framed[0] = (byte) packet.length;
                framed[1] = (byte) (packet.length >>> 8);
                System.arraycopy(packet, 0, framed, 2, packet.length);
                listener.data(framed);
                shortStarted.countDown();
                if (!shortRelease.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Short-stream probe release timed out");
                throw new java.io.IOException("Prepared stream interruption after a short block");
            }
            return delegate.stream(endpoint, token, id, path, body, listener, current);
        }

        public void cancelStream(long id) {
            delegate.cancelStream(id);
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current)
                throws Exception {
            livePath = path;
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
            sent.add(new JSONObject(frame.toString()));
            delegate.send(frame);
        }

        public void sendBinary(byte[] bytes) throws Exception {
            binary.incrementAndGet();
            delegate.sendBinary(bytes);
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void closeLive() {
            delegate.closeLive();
        }
    }
}
