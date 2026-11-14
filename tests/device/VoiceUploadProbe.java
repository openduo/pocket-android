// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.Context;

import org.json.JSONObject;

import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelClient;
import top.kuaner.pockete2.net.ChannelConnection;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Ordinary controller/client/repository paths with delayed HTTP and prepared live events. */
final class VoiceUploadProbe {
    private final PocketController controller;
    private final Executor state;
    private final ChannelConnection original;
    private final Delayed source;
    private final List<String> ids = new ArrayList<>();
    private byte[] body;
    private Context context;
    private int assertions;

    VoiceUploadProbe(PocketController controller) throws Exception {
        this.controller = controller;
        state = (Executor) field("state");
        original = (ChannelConnection) field("channel");
        source = new Delayed(original);
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

    private Object invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(15);
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private RoomRepository repo() throws Exception {
        return (RoomRepository) field("repo");
    }

    private Outbox.Item pending(String id) throws Exception {
        return onState(() -> repo().findPending(id));
    }

    private void frame(JSONObject value) throws Exception {
        onState(
                () -> {
                    Method method =
                            PocketController.class.getDeclaredMethod(
                                    "frame", String.class, boolean.class);
                    method.setAccessible(true);
                    method.invoke(controller, value.toString(), true);
                    return null;
                });
    }

    private String queue(Exchange exchange) throws Exception {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        File dir = new File(context.getCacheDir(), "try-voice");
        dir.mkdirs();
        File file = new File(dir, id + ".packets");
        Files.write(file.toPath(), body);
        source.exchanges.put(id, exchange);
        onState(
                () -> {
                    Outbox.Item item =
                            new Outbox.Item(
                                            id,
                                            "",
                                            true,
                                            file.getPath(),
                                            "phone",
                                            400,
                                            Collections.emptyList(),
                                            Message.QUEUED,
                                            "",
                                            "",
                                            System.currentTimeMillis(),
                                            0)
                                    .surface("phone", false, "");
                    repo().enqueue(
                                    Message.mine(
                                            id,
                                            "",
                                            "phone",
                                            400,
                                            Message.QUEUED,
                                            Collections.emptyList()),
                                    item);
                    invoke("reload");
                    invoke("pump");
                    return null;
                });
        return id;
    }

    private void cleaned(String id) throws Exception {
        frame(
                Json.object(
                        "type", "message", "id", id, "state", "cleaned", "text", "prepared text"));
    }

    private void failed(String id, String reason) throws Exception {
        frame(Json.object("type", "message", "id", id, "state", "failed", "reason", reason));
    }

    int run(Context context, boolean capture) throws Exception {
        this.context = context;
        check(
                controller.snapshot().trying && controller.snapshot().outbox.isEmpty(),
                "voice fault injection requires an idle disposable room");
        try (OpusEncoder encoder = new OpusEncoder();
                java.io.ByteArrayOutputStream packets = new java.io.ByteArrayOutputStream()) {
            for (int i = 0; i < 20; i++) {
                byte[] packet = encoder.encode(new short[320]);
                packets.write(packet.length & 255);
                packets.write(packet.length >>> 8);
                packets.write(packet);
            }
            body = packets.toByteArray();
            onState(
                    () -> {
                        field("channel", source);
                        return null;
                    });
            lateCleaned();
            earlyCleaned();
            earlyRow();
            retryBytes();
            discardRetry();
            recognitionFailures();
            deadline(false);
            deadline(true);
            if (capture) heldNote();
            return assertions;
        } finally {
            controller.releaseHold(true);
            controller.onForeground(true);
            for (Exchange exchange : source.exchanges.values()) exchange.release.countDown();
            for (String id : ids) controller.discard(id);
            onState(
                    () -> {
                        field("channel", original);
                        return null;
                    });
            controller.clearNotice();
        }
    }

    private void deadline(boolean transport) throws Exception {
        Exchange exchange = new Exchange(transport ? -1 : 503);
        long started = android.os.SystemClock.elapsedRealtime();
        String id = queue(exchange);
        check(
                exchange.started.await(3, TimeUnit.SECONDS),
                "deadline fixture starts its first attempt");
        String path = pending(id).voiceFile;
        exchange.release.countDown();
        int terminal = transport ? Message.QUEUED : Message.FAILED;
        await(
                () ->
                        controller.snapshot().outbox.stream()
                                .anyMatch(item -> item.id.equals(id) && item.state == terminal),
                27_000);
        Outbox.Item item = pending(id);
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        check(
                item != null
                        && item.state == terminal
                        && elapsed >= 24_500
                        && elapsed < 27_000
                        && new File(path).isFile()
                        && exchange.bodies.size() >= 2
                        && exchange.bodies.size() <= 9,
                "the real elapsed-time budget ends voice retries near 25 seconds without another"
                        + " request");
        if (transport) {
            check(
                    item.error.equals("等待网络恢复"),
                    "an exhausted transport failure retains the note queued for connection"
                            + " recovery");
            Exchange recovered = new Exchange(202);
            recovered.release.countDown();
            source.exchanges.put(id, recovered);
            check(
                    recovered.started.await(5, TimeUnit.SECONDS),
                    "HTTP transport loss replaces an existing live socket so queued voice can"
                            + " reconnect");
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            current ->
                                                    current.id.equals(id)
                                                            && current.state == Outbox.ACCEPTED),
                    1500);
            check(
                    pending(id).state == Outbox.ACCEPTED
                            && recovered.bodies.size() == 1
                            && Arrays.equals(recovered.bodies.get(0), exchange.bodies.get(0))
                            && recovered
                                    .headers
                                    .get(0)
                                    .toString()
                                    .equals(exchange.headers.get(0).toString()),
                    "reconnection sends the retained note once with its original key, body and"
                            + " surface");
        } else {
            check(
                    item.error.equals("语音服务暂不可用 · 轻点重试")
                            && controller.snapshot().connection
                                    == PocketController.Connection.ONLINE,
                    "exhausted service errors remain retryable without falsely closing a healthy"
                            + " socket");
            int attempts = exchange.bodies.size();
            Thread.sleep(600);
            check(
                    exchange.bodies.size() == attempts,
                    "the final backoff timer cannot start an extra request after expiry");
        }
        controller.discard(id);
        onState(() -> null);
    }

    private void recognitionFailures() throws Exception {
        for (String reason :
                new String[] {"empty_transcript", "asr_failed", "perception_unavailable"}) {
            Exchange exchange = new Exchange(202);
            String id = queue(exchange);
            check(
                    exchange.started.await(3, TimeUnit.SECONDS),
                    "recognition-outcome fixture reaches HTTP");
            String path = pending(id).voiceFile;
            exchange.release.countDown();
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.id.equals(id)
                                                            && item.state == Outbox.ACCEPTED),
                    1000);
            failed(id, reason);
            Outbox.Item current = pending(id);
            if (reason.equals("empty_transcript")) {
                check(
                        current == null
                                && !new File(path).exists()
                                && controller.snapshot().rows.stream()
                                        .noneMatch(row -> row.id.equals(id))
                                && controller.snapshot().lastError.equals("没听清，再说一次"),
                        "empty recognition removes the pending bubble and packet file with an"
                                + " explicit notice");
            } else {
                String expected = reason.equals("asr_failed") ? "识别出错 · 轻点重试" : "语音服务暂不可用 · 轻点重试";
                check(
                        current != null
                                && current.state == Message.FAILED
                                && current.error.equals(expected)
                                && new File(path).isFile(),
                        "recognition/service failure retains the note and a specific retryable"
                                + " error");
            }
            controller.discard(id);
            controller.clearNotice();
            onState(() -> null);
        }
    }

    private void beginCapturedHold() throws Exception {
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        controller.beginHold();
        await(
                () -> controller.snapshot().holdLive && controller.snapshot().holdDurationMs >= 400,
                5000);
        check(
                controller.snapshot().holdRecording
                        && controller.snapshot().holdLive
                        && controller.snapshot().holdDurationMs >= 400,
                "the ordinary hold path captures at least 400 ms from the real E2 microphone");
    }

    private void heldNote() throws Exception {
        beginCapturedHold();
        File cancelled = onState(() -> (File) field("heldFile"));
        controller.releaseHold(true);
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        check(
                !cancelled.exists()
                        && controller.snapshot().outbox.isEmpty()
                        && !controller.snapshot().holdRecording,
                "cancelling a real hold removes its bytes and releases the microphone without"
                        + " sending");

        beginCapturedHold();
        File background = onState(() -> (File) field("heldFile"));
        controller.onForeground(false);
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        check(
                !background.exists()
                        && controller.snapshot().outbox.isEmpty()
                        && !controller.snapshot().holdRecording,
                "backgrounding during a real hold cancels the unfinished note and releases input");
        controller.onForeground(true);
        await(() -> controller.snapshot().connection == PocketController.Connection.ONLINE, 5000);

        beginCapturedHold();
        android.media.AudioManager manager =
                context.getSystemService(android.media.AudioManager.class);
        android.media.AudioFocusRequest other =
                new android.media.AudioFocusRequest.Builder(
                                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                                new android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                        .build())
                        .setOnAudioFocusChangeListener(
                                ignored -> {},
                                new android.os.Handler(android.os.Looper.getMainLooper()))
                        .build();
        String id;
        byte[] captured;
        String path;
        try {
            check(
                    manager.requestAudioFocus(other)
                            == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    "Android grants an independent interruption during the real hold");
            await(
                    () ->
                            !controller.snapshot().holdRecording
                                    && !top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                    5000);
            check(
                    controller.snapshot().outbox.size() == 1,
                    "an interrupted held note is retained exactly once");
            Outbox.Item item = controller.snapshot().outbox.get(0);
            id = item.id;
            ids.add(id);
            path = item.voiceFile;
            captured = ChannelClient.voiceBody(new File(path), null);
            check(
                    item.state == Message.FAILED
                            && item.error.equals("录音被打断 · 轻点重试")
                            && item.durationMs >= 400
                            && captured.length > 0,
                    "native focus loss preserves framed E2 Opus as a failed note without"
                            + " auto-uploading");
        } finally {
            manager.abandonAudioFocusRequest(other);
        }
        Exchange exchange = new Exchange(202);
        source.exchanges.put(id, exchange);
        controller.retry(id);
        check(
                exchange.started.await(3, TimeUnit.SECONDS)
                        && exchange.bodies.size() == 1
                        && Arrays.equals(exchange.bodies.get(0), captured)
                        && exchange.headers.get(0).optString("Idempotency-Key").equals(id),
                "retrying an interrupted recording uploads its retained real packets under the same"
                        + " id");
        exchange.release.countDown();
        await(
                () ->
                        controller.snapshot().outbox.stream()
                                .anyMatch(
                                        item ->
                                                item.id.equals(id)
                                                        && item.state == Outbox.ACCEPTED),
                1500);
        failed(id, "empty_transcript");
        check(
                pending(id) == null && !new File(path).exists(),
                "a prepared empty result also cleans a note made by the actual E2 hold path");
        controller.clearNotice();
    }

    private void lateCleaned() throws Exception {
        Exchange exchange = new Exchange(202);
        String id = queue(exchange);
        check(
                exchange.started.await(3, TimeUnit.SECONDS),
                "a framed note reaches the normal HTTP client");
        failed(id, "asr_failed");
        cleaned(id);
        Outbox.Item current = pending(id);
        check(
                current != null
                        && current.state == Message.FAILED
                        && current.error.equals("识别出错 · 轻点重试")
                        && current.cleaned.isEmpty(),
                "late cleaned cannot revive a failed voice note or replace its recognition error");
        exchange.release.countDown();
        Thread.sleep(150);
        current = pending(id);
        check(
                current != null
                        && current.state == Message.FAILED
                        && current.error.equals("识别出错 · 轻点重试"),
                "a delayed HTTP acceptance preserves an earlier live recognition failure");
        controller.discard(id);
        onState(() -> null);
    }

    private void earlyCleaned() throws Exception {
        Exchange exchange = new Exchange(-1);
        String id = queue(exchange);
        check(
                exchange.started.await(3, TimeUnit.SECONDS),
                "the next upload starts after live failure");
        int epoch = onState(() -> (Integer) field("connectionEpoch"));
        cleaned(id);
        exchange.release.countDown();
        Thread.sleep(700);
        Outbox.Item current = pending(id);
        check(
                current != null
                        && current.state == Outbox.ACCEPTED
                        && current.cleaned.equals("prepared text")
                        && exchange.bodies.size() == 1
                        && controller.snapshot().connection == PocketController.Connection.ONLINE
                        && onState(() -> (Integer) field("connectionEpoch")) == epoch,
                "live cleaned wins over a lost HTTP response without retry or a false reconnect");
        controller.discard(id);
        onState(() -> null);
    }

    private void earlyRow() throws Exception {
        Exchange exchange = new Exchange(-1);
        String id = queue(exchange);
        check(
                exchange.started.await(3, TimeUnit.SECONDS),
                "a note is in flight before its logged row");
        String path = pending(id).voiceFile;
        int epoch = onState(() -> (Integer) field("connectionEpoch"));
        long seq = controller.snapshot().cursor + 1;
        frame(
                Json.object(
                        "type",
                        "row",
                        "seq",
                        seq,
                        "row",
                        Json.object(
                                "kind",
                                "typed",
                                "utt_id",
                                id,
                                "text",
                                "prepared logged text",
                                "at",
                                java.time.Instant.now().toString())));
        exchange.release.countDown();
        Thread.sleep(700);
        check(
                pending(id) == null
                        && !new File(path).exists()
                        && controller.snapshot().rows.stream()
                                .anyMatch(
                                        row ->
                                                row.id.equals(id)
                                                        && row.seq >= 0
                                                        && row.durationMs == 400)
                        && exchange.bodies.size() == 1
                        && controller.snapshot().connection == PocketController.Connection.ONLINE
                        && onState(() -> (Integer) field("connectionEpoch")) == epoch,
                "authoritative row removes the packet file and rejects a late transport failure");
    }

    private void retryBytes() throws Exception {
        Exchange exchange = new Exchange(503, 202);
        String id = queue(exchange);
        check(
                exchange.started.await(3, TimeUnit.SECONDS),
                "retry fixture reaches its first attempt");
        Files.write(new File(pending(id).voiceFile).toPath(), new byte[] {0, 0});
        exchange.release.countDown();
        await(() -> exchange.bodies.size() >= 2, 2500);
        await(
                () ->
                        controller.snapshot().outbox.stream()
                                .anyMatch(
                                        item ->
                                                item.id.equals(id)
                                                        && item.state == Outbox.ACCEPTED),
                1000);
        check(
                exchange.bodies.size() == 2
                        && Arrays.equals(exchange.bodies.get(0), body)
                        && Arrays.equals(exchange.bodies.get(1), body)
                        && exchange.headers
                                .get(0)
                                .toString()
                                .equals(exchange.headers.get(1).toString())
                        && exchange.timeouts.get(1) > 0
                        && exchange.timeouts.get(1) < exchange.timeouts.get(0)
                        && pending(id).state == Outbox.ACCEPTED,
                "retries reuse the initial Opus body, key and surface within a shrinking total"
                        + " budget");
        controller.discard(id);
        onState(() -> null);
    }

    private void discardRetry() throws Exception {
        Exchange first = new Exchange(503, 503, 202);
        String id = queue(first);
        check(first.started.await(3, TimeUnit.SECONDS), "discard fixture starts normally");
        first.release.countDown();
        await(() -> first.bodies.size() >= 2, 2000);
        check(first.bodies.size() == 2, "a second transient error enters the longer backoff");
        Thread.sleep(120);
        Exchange next = new Exchange(202);
        String nextId = queue(next);
        check(next.bodies.isEmpty(), "queued messages preserve order while the first note retries");
        controller.discard(id);
        check(
                next.started.await(500, TimeUnit.MILLISECONDS),
                "discard during backoff releases the send slot immediately for the next message");
        next.release.countDown();
        Thread.sleep(1100);
        check(
                first.bodies.size() == 2
                        && pending(id) == null
                        && pending(nextId).state == Outbox.ACCEPTED,
                "discard cancels the scheduled retry and a late timer cannot disturb the next"
                        + " upload");
        controller.discard(nextId);
        onState(() -> null);
    }

    private static final class Exchange {
        final int[] statuses;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        final List<JSONObject> headers = new CopyOnWriteArrayList<>();
        final List<Long> timeouts = new CopyOnWriteArrayList<>();

        Exchange(int... statuses) {
            this.statuses = statuses;
        }

        ChannelConnection.Response request(JSONObject header, byte[] body, long timeout)
                throws Exception {
            headers.add(new JSONObject(header.toString()));
            timeouts.add(timeout);
            bodies.add(body.clone());
            int index = bodies.size() - 1;
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Voice HTTP release timed out");
            int status = statuses[Math.min(index, statuses.length - 1)];
            if (status < 0) throw new java.io.IOException("Prepared lost voice response");
            return new ChannelConnection.Response(
                    status,
                    Json.object(
                                    "error",
                                    status == 503 ? "perception_unavailable" : "",
                                    "message_id",
                                    header.optString("Idempotency-Key"))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Delayed implements ChannelConnection {
        final ChannelConnection delegate;
        final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();

        Delayed(ChannelConnection delegate) {
            this.delegate = delegate;
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
            Exchange exchange = exchanges.get(headers.optString("Idempotency-Key"));
            if (exchange != null && method.equals("POST") && path.startsWith("/api/messages?"))
                return exchange.request(headers, body, timeout);
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
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
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
