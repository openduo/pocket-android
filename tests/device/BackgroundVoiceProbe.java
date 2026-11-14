// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.audio.input.RecordingInput;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelConnection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Activity/service lifecycle with an isolated SQLite room and no-network voice fixture. */
final class BackgroundVoiceProbe {
    private static final String DATABASE = "pocket-e2-background-voice-qa.db";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private RoomRepository repository;
    private Context context;
    private byte[] body;
    private int assertions;

    BackgroundVoiceProbe(Probe probe) {
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

    private boolean service() {
        for (ActivityManager.RunningServiceInfo info :
                context.getSystemService(ActivityManager.class).getRunningServices(40))
            if (info.foreground
                    && info.service
                            .getClassName()
                            .equals("top.kuaner.pockete2.app.PocketSessionService")) return true;
        return false;
    }

    private boolean background() {
        try {
            return onState(() -> !(Boolean) field("foreground"));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Outbox.Item pending(String id) throws Exception {
        return onState(() -> repository.findPending(id));
    }

    private String queue(Source source, Exchange exchange) throws Exception {
        String id = UUID.randomUUID().toString();
        File dir = new File(context.getCacheDir(), "qa/background-voice");
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
                    repository.enqueue(
                            Message.mine(
                                    id, "", "phone", 400, Message.QUEUED, Collections.emptyList()),
                            item);
                    invoke("reload");
                    invoke("pump");
                    return null;
                });
        return id;
    }

    private void logged(Source source, String id) throws Exception {
        source.logged(id);
        await(() -> controller.snapshot().outbox.isEmpty() && !service(), 5000);
        check(
                pending(id) == null
                        && !service()
                        && !controller.snapshot().uploadingVoice
                        && onState(() -> repository.row(id).durationMs) == 400,
                "logged acceptance retains duration and releases both voice bytes and the upload"
                    + " service");
    }

    String run(Context context) throws Exception {
        this.context = context;
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
                        && !initial.sessions.watching
                        && !service(),
                "background upload acceptance requires the actual room idle");
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        RoomRepository originalRepo = onState(() -> (RoomRepository) field("repo"));
        Object originalState = onState(() -> field("roomState"));
        String originalError = initial.lastError;
        Object originalContext = field("context");
        Source source = new Source();
        context.deleteDatabase(DATABASE);
        repository = new RoomRepository(context, DATABASE);
        repository.select("qa:background-voice");
        repository.metadata("cursor", "0");
        repository.metadata("history_loaded", "1");
        try (OpusEncoder encoder = new OpusEncoder();
                ByteArrayOutputStream packets = new ByteArrayOutputStream()) {
            for (int i = 0; i < 20; i++) {
                byte[] packet = encoder.encode(new short[320]);
                packets.write(packet.length & 255);
                packets.write(packet.length >>> 8);
                packets.write(packet);
            }
            body = packets.toByteArray();
            onState(
                    () -> {
                        field("connectionEpoch", (Integer) field("connectionEpoch") + 1);
                        original.closeLive();
                        field("repo", repository);
                        field("channel", source);
                        field("roomState", null);
                        field("opening", false);
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    5000);
            check(
                    onState(() -> field("repo") != field("realRepo") && field("channel") == source),
                    "all fixture messages use isolated storage and a transport incapable of network"
                        + " I/O");
            Exchange failed = new Exchange(-1);
            long began = SystemClock.elapsedRealtime();
            String id = queue(source, failed);
            check(
                    failed.started.await(3, TimeUnit.SECONDS),
                    "the first prepared upload starts in the foreground");
            await(this::service, 3000);
            check(
                    service() && controller.snapshot().uploadingVoice && !RecordingInput.isActive(),
                    "native foreground upload runs without a microphone lease");
            probe.runOnMainSync(() -> screen.moveTaskToBack(true));
            await(this::background, 5000);
            check(background(), "the actual Activity has stopped before transport failure");
            failed.release.countDown();
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.id.equals(id)
                                                            && item.state == Message.QUEUED),
                    27_000);
            check(
                    pending(id).state == Message.QUEUED
                            && failed.bodies.size() >= 2
                            && SystemClock.elapsedRealtime() - began >= 24_500
                            && SystemClock.elapsedRealtime() - began < 27_000,
                    "the real retry budget exhausts in the background and retains the note queued");
            Exchange recovered = new Exchange(202);
            source.exchanges.put(id, recovered);
            check(
                    recovered.started.await(5, TimeUnit.SECONDS) && background(),
                    "live reconnection automatically starts the retained upload while the Activity"
                        + " stays backgrounded");
            await(this::service, 3000);
            check(
                    service()
                            && controller.snapshot().uploadingVoice
                            && source.opens.get() >= 2
                            && recovered.bodies.size() == 1
                            && Arrays.equals(recovered.bodies.get(0), body)
                            && recovered
                                    .headers
                                    .get(0)
                                    .toString()
                                    .equals(failed.headers.get(0).toString()),
                    "background retry reacquires the actual foreground service with the original"
                        + " key, body and surface");
            recovered.release.countDown();
            logged(source, id);

            AtomicInteger denied = new AtomicInteger();
            Context restricted =
                    new ContextWrapper((Context) originalContext) {
                        @Override
                        public ComponentName startForegroundService(Intent intent) {
                            denied.incrementAndGet();
                            throw new IllegalStateException(
                                    "Prepared background start restriction");
                        }
                    };
            onState(
                    () -> {
                        field("context", restricted);
                        return null;
                    });
            Exchange deferred = new Exchange(202);
            String waiting = queue(source, deferred);
            Outbox.Item held = pending(waiting);
            check(
                    held != null
                            && held.state == Message.QUEUED
                            && new File(held.voiceFile).isFile()
                            && !controller.snapshot().uploadingVoice
                            && !service()
                            && deferred.bodies.isEmpty()
                            && denied.get() == 1,
                    "a prepared service-start refusal retains queued voice without pretending to"
                        + " upload or making HTTP requests");
            for (int i = 0; i < 3; i++)
                onState(
                        () -> {
                            invoke("pump");
                            return null;
                        });
            check(
                    denied.get() == 1 && deferred.bodies.isEmpty(),
                    "passive pumps cannot repeatedly request a denied background service");
            onState(
                    () -> {
                        field("context", originalContext);
                        return null;
                    });
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            check(
                    deferred.started.await(5, TimeUnit.SECONDS),
                    "actual foreground return resumes deferred voice on the already-open live"
                        + " connection");
            await(this::service, 3000);
            check(
                    !background()
                            && service()
                            && deferred.bodies.size() == 1
                            && Arrays.equals(deferred.bodies.get(0), body)
                            && deferred.headers.get(0).optString("Idempotency-Key").equals(waiting),
                    "foreground recovery preserves the deferred message id and bytes");
            deferred.release.countDown();
            logged(source, waiting);
        } finally {
            for (Exchange exchange : source.exchanges.values()) exchange.release.countDown();
            onState(
                    () -> {
                        field("context", originalContext);
                        field("sendEpoch", (Long) field("sendEpoch") + 1);
                        field("sending", null);
                        field("uploadForeground", false);
                        try {
                            field("voiceUploadDeferred", false);
                        } catch (NoSuchFieldException olderBuild) {
                            // Cleanup also supports reproducing the regression on the previous APK.
                        }
                        invoke("refreshService");
                        field("connectionEpoch", (Integer) field("connectionEpoch") + 1);
                        source.closeLive();
                        repository.clear();
                        repository.close();
                        field("repo", originalRepo);
                        field("channel", original);
                        field("roomState", originalState);
                        field("lastError", originalError);
                        field("opening", false);
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("restoreDraft");
                        invoke("reload");
                        invoke("connect");
                        invoke("publish");
                        return null;
                    });
            context.deleteDatabase(DATABASE);
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        await(() -> controller.snapshot().connection == PocketController.Connection.ONLINE, 30_000);
        check(
                controller.snapshot().importTarget.equals(initial.importTarget)
                        && controller.snapshot().connection == PocketController.Connection.ONLINE
                        && controller.draft().equals(initial.draft)
                        && controller.snapshot().quote.equals(initial.quote)
                        && controller.snapshot().chips.size() == initial.chips.size()
                        && controller.snapshot().outbox.isEmpty()
                        && !service(),
                "the actual paired room, composer and service state are restored without sending a"
                    + " real message");
        return "PASS "
                + assertions
                + " native background upload assertions; HTTP outcomes are prepared";
    }

    private static final class Exchange {
        final int status;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        final List<JSONObject> headers = new CopyOnWriteArrayList<>();

        Exchange(int status) {
            this.status = status;
        }

        ChannelConnection.Response request(JSONObject header, byte[] bytes, long timeout)
                throws Exception {
            headers.add(new JSONObject(header.toString()));
            bodies.add(bytes.clone());
            started.countDown();
            if (!release.await(Math.min(timeout, 10_000), TimeUnit.MILLISECONDS))
                throw new java.io.IOException("Prepared delayed response expired");
            if (status < 0) throw new java.io.IOException("Prepared transport failure");
            return Source.json(
                    status, Json.object("message_id", header.optString("Idempotency-Key")));
        }
    }

    /** Implements the small fixture API directly; there is no delegate, socket or native bridge. */
    private static final class Source implements ChannelConnection {
        final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();
        final AtomicInteger opens = new AtomicInteger();
        private volatile LiveListener listener;
        private volatile boolean open;
        private long seq;

        static Response json(int status, JSONObject value) {
            return new Response(status, value.toString().getBytes(StandardCharsets.UTF_8));
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
            if (method.equals("POST") && path.startsWith("/api/messages?")) {
                Exchange exchange = exchanges.get(headers.optString("Idempotency-Key"));
                if (exchange == null) throw new AssertionError("Unregistered fixture message");
                return exchange.request(headers, body, timeout);
            }
            if (method.equals("GET") && path.equals("/healthz"))
                return json(200, Json.object("ok", true));
            if (method.equals("GET") && path.startsWith("/api/state?"))
                return json(
                        200,
                        Json.object(
                                "room",
                                "qa",
                                "date",
                                Json.day(System.currentTimeMillis()),
                                "daemon_ok",
                                true,
                                "cerebellum_ok",
                                true));
            if (method.equals("GET") && path.startsWith("/api/imlog?"))
                return json(200, Json.object("entries", new JSONArray(), "seq", seq));
            throw new AssertionError("Unexpected fixture request");
        }

        void logged(String id) {
            listener.text(
                    Json.object(
                                    "type",
                                    "row",
                                    "seq",
                                    ++seq,
                                    "row",
                                    Json.object(
                                            "seq",
                                            seq,
                                            "kind",
                                            "typed",
                                            "utt_id",
                                            id,
                                            "at",
                                            java.time.Instant.now().toString(),
                                            "text",
                                            "Pocket E2 QA · Prepared transcript"))
                            .toString());
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
            listener.text(
                    Json.object("type", "meta", "conn", "qa-voice-" + opens.incrementAndGet())
                            .toString());
            listener.text(
                    Json.object(
                                    "type",
                                    "ready",
                                    "api",
                                    2,
                                    "seq",
                                    seq,
                                    "turn",
                                    JSONObject.NULL,
                                    "messages",
                                    new JSONArray())
                            .toString());
        }

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] bytes) {
            throw new AssertionError("No live audio in upload acceptance");
        }

        public boolean isOpen() {
            return open;
        }

        public void closeLive() {
            open = false;
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current) {
            throw new AssertionError("No speech synthesis in upload acceptance");
        }

        public void cancelStream(long id) {}
    }
}
