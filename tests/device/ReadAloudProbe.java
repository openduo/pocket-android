// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.Context;
import android.media.AudioTrack;
import android.os.SystemClock;

import org.json.JSONObject;

import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.audio.SpeechPlayer;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelConnection;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Prepared HTTP/late data and an actual app-owned AudioTrack failure in the disposable room. */
final class ReadAloudProbe {
    private final PocketController controller;
    private final Executor state;
    private final ChannelConnection original;
    private final Source source;
    private int assertions;

    ReadAloudProbe(PocketController controller) throws Exception {
        this.controller = controller;
        state = (Executor) field("state");
        original = (ChannelConnection) field("channel");
        source = new Source(original);
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

    private <T> T onState(Callable<T> task) throws Exception {
        FutureTask<T> work = new FutureTask<>(task);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private boolean pending() {
        try {
            return onState(() -> ((SpeechPlayer) field("player")).hasPending());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Message message() {
        return new Message(
                "qa-read-" + UUID.randomUUID(),
                Message.DUODUO,
                System.currentTimeMillis(),
                "Prepared read failure",
                "",
                0,
                Message.DELIVERED,
                "",
                true,
                false,
                Collections.emptyList(),
                -1);
    }

    private void errors() throws Exception {
        Object[][] cases = {
            {422, "nothing_to_speak", "这条消息没有可朗读的内容"},
            {422, "invalid_text", "朗读失败（422）"},
            {422, "", "朗读失败（422）"},
            {413, "body_too_large", "这条消息太长，无法朗读"},
            {502, "synthesis_failed", "语音合成失败，稍后再试"},
            {503, "cerebellum_unavailable", "语音服务暂不可用"},
            {500, "unexpected", "朗读失败（500）"}
        };
        for (Object[] entry : cases) {
            source.gate = new Gate((Integer) entry[0], (String) entry[1], null);
            controller.clearNotice();
            controller.readAloud(message());
            check(
                    source.gate.returned.await(3, TimeUnit.SECONDS),
                    "prepared speech HTTP response returns");
            await(
                    () ->
                            controller.snapshot().readingId.isEmpty()
                                    && !controller.snapshot().lastError.isEmpty(),
                    2000);
            check(
                    controller.snapshot().readingId.isEmpty()
                            && controller.snapshot().lastError.equals(entry[2])
                            && onState(() -> !(Boolean) field("readInFlight"))
                            && !pending(),
                    "speech failure "
                            + entry[0]
                            + "/"
                            + entry[1]
                            + " has its upstream meaning and leaves no reading state: "
                            + controller.snapshot().lastError);
        }
    }

    private void hardware(byte[] packet) throws Exception {
        Gate gate = source.gate = new Gate(200, "", packet);
        Message message = message();
        controller.clearNotice();
        controller.readAloud(message);
        check(
                gate.started.await(3, TimeUnit.SECONDS),
                "the prepared stream supplies one native Opus block");
        await(() -> !pending(), 2500);
        AudioTrack track =
                onState(
                        () -> {
                            Field field = SpeechPlayer.class.getDeclaredField("track");
                            field.setAccessible(true);
                            return (AudioTrack) field.get(field("player"));
                        });
        check(
                track != null
                        && controller.snapshot().readingId.equals(message.id)
                        && onState(() -> (Boolean) field("readInFlight")),
                "the real hardware block drains while its speech HTTP body stays open");
        // Release only Pocket E2's track to model a native output failure; no system route changes.
        track.release();
        await(
                () -> gate.cancelled.getCount() == 0 && controller.snapshot().readingId.isEmpty(),
                2500);
        check(
                gate.cancelled.getCount() == 0
                        && controller.snapshot().readingId.isEmpty()
                        && onState(() -> !(Boolean) field("readInFlight"))
                        && controller.snapshot().lastError.equals("播放中断，请检查音频设备"),
                "native playback failure must cancel the open read and clear its spinner"
                        + " immediately");
        check(
                gate.returned.await(2, TimeUnit.SECONDS),
                "the cancelled stream returns after deliberately delivering late data");
        Thread.sleep(150);
        onState(() -> null);
        check(
                !pending()
                        && controller.snapshot().readingId.isEmpty()
                        && onState(
                                () -> {
                                    Field granted =
                                            top.kuaner.pockete2.audio.AudioFocus.class
                                                    .getDeclaredField("granted");
                                    granted.setAccessible(true);
                                    return !(Boolean) granted.get(field("audioFocus"));
                                }),
                "late data cannot resume a failed read or retain its playback focus");
        check(
                source.readReceipts() == 0,
                "local read failure emits no room played/interrupted receipts");

        Gate next = source.gate = new Gate(200, "", packet);
        controller.clearNotice();
        controller.readAloud(message());
        check(
                next.started.await(3, TimeUnit.SECONDS),
                "an explicit fresh read starts after native output failure");
        next.release.countDown();
        check(next.returned.await(3, TimeUnit.SECONDS), "the fresh read's body completes");
        await(() -> controller.snapshot().readingId.isEmpty() && !pending(), 2500);
        check(
                controller.snapshot().readingId.isEmpty()
                        && !pending()
                        && controller.snapshot().lastError.isEmpty()
                        && onState(() -> ((AmbientOutput) field("ambientOutput")).playedMs()) == 20,
                "a new native AudioTrack completes the fresh 20 ms read without inheriting the"
                        + " failure");
    }

    int run(Context context) throws Exception {
        check(
                controller.snapshot().trying && !controller.snapshot().ambientActive,
                "read failures stay inside an idle disposable room");
        String draft = controller.draft();
        int outbox = controller.snapshot().outbox.size();
        try (OpusEncoder encoder = new OpusEncoder()) {
            onState(
                    () -> {
                        field("channel", source);
                        invoke("ensurePlayer");
                        ((SpeechPlayer) field("player")).silent(true);
                        return null;
                    });
            errors();
            hardware(encoder.encode(new short[320]));
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().outbox.size() == outbox,
                    "read fault injection preserves the composer and sends no messages");
            return assertions;
        } finally {
            Gate gate = source.gate;
            if (gate != null) gate.release.countDown();
            onState(
                    () -> {
                        invoke("stopPlayback");
                        ((SpeechPlayer) field("player")).silent(controller.snapshot().ambientMuted);
                        field("channel", original);
                        return null;
                    });
            controller.clearNotice();
        }
    }

    private static final class Gate {
        final int status;
        final String reason;
        final byte[] packet;
        final CountDownLatch started = new CountDownLatch(1),
                release = new CountDownLatch(1),
                cancelled = new CountDownLatch(1),
                returned = new CountDownLatch(1);

        Gate(int status, String reason, byte[] packet) {
            this.status = status;
            this.reason = reason;
            this.packet = packet;
        }

        byte[] framed() {
            byte[] bytes = new byte[packet.length + 2];
            bytes[0] = (byte) packet.length;
            bytes[1] = (byte) (packet.length >>> 8);
            System.arraycopy(packet, 0, bytes, 2, packet.length);
            return bytes;
        }
    }

    private static final class Source implements ChannelConnection {
        final ChannelConnection delegate;
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        volatile Gate gate;

        Source(ChannelConnection delegate) {
            this.delegate = delegate;
        }

        long readReceipts() {
            return sent.stream()
                    .filter(
                            frame ->
                                    frame.optString("speech_id").startsWith("read:")
                                            && (frame.optString("type").equals("played")
                                                    || frame.optString("type")
                                                            .equals("interrupted")))
                    .count();
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
            Gate value = gate;
            try {
                if (value.packet != null) {
                    listener.data(value.framed());
                    value.started.countDown();
                    if (!value.release.await(6, TimeUnit.SECONDS))
                        throw new AssertionError("Read failure probe release timed out");
                    if (value.cancelled.getCount() == 0) listener.data(value.framed());
                }
                return new Response(
                        value.status,
                        Json.object("error", value.reason)
                                .toString()
                                .getBytes(StandardCharsets.UTF_8));
            } finally {
                value.returned.countDown();
            }
        }

        public void cancelStream(long id) {
            Gate value = gate;
            if (value != null) {
                value.cancelled.countDown();
                value.release.countDown();
            }
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current)
                throws Exception {
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
            sent.add(new JSONObject(frame.toString()));
            delegate.send(frame);
        }

        public void sendBinary(byte[] bytes) throws Exception {
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
