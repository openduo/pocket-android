// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelConnection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Prepared access loss through the actual controller, isolated settings/SQLite and native service.
 */
final class AccessRecoveryProbe {
    private static final String DATABASE = "pocket-e2-access-qa.db";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private Context context;
    private RoomRepository repository;
    private final Source source = new Source();
    private byte[] body;
    private int assertions;

    AccessRecoveryProbe(Probe probe) {
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

    private void status(String value) throws Exception {
        onState(
                () -> {
                    Method method =
                            PocketController.class.getDeclaredMethod(
                                    "networkStatus", JSONObject.class);
                    method.setAccessible(true);
                    method.invoke(controller, Json.object("backend_state", value));
                    return null;
                });
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void checkpoint(String value) {
        android.os.Bundle report = new android.os.Bundle();
        report.putString("checkpoint", value);
        probe.sendStatus(0, report);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private boolean online() {
        return controller.snapshot() != null
                && controller.snapshot().connection == PocketController.Connection.ONLINE;
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

    private Outbox.Item pending(String id) throws Exception {
        return onState(() -> repository.findPending(id));
    }

    private String queue(Exchange exchange, boolean voice) throws Exception {
        String id = UUID.randomUUID().toString();
        File file = new File(context.getCacheDir(), "access-qa/" + id + ".packets");
        if (voice) {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), body);
        }
        source.exchanges.put(id, exchange);
        onState(
                () -> {
                    repository.enqueue(
                            Message.mine(
                                    id,
                                    "Prepared access QA",
                                    "phone",
                                    voice ? 400 : 0,
                                    Message.QUEUED,
                                    Collections.emptyList()),
                            new Outbox.Item(
                                            id,
                                            "Prepared access QA",
                                            voice,
                                            voice ? file.getPath() : "",
                                            "phone",
                                            voice ? 400 : 0,
                                            Collections.emptyList(),
                                            Message.QUEUED,
                                            "",
                                            "",
                                            System.currentTimeMillis(),
                                            0)
                                    .surface("phone", false, ""));
                    invoke("reload");
                    invoke("pump");
                    return null;
                });
        return id;
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
        await(this::online, 35_000);
        PocketController.Snapshot initial = controller.snapshot();
        check(
                initial != null
                        && online()
                        && !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.working == null
                        && !service(),
                "access acceptance requires the actual paired room idle: connection="
                        + (initial == null ? "none" : initial.connection)
                        + ", trying="
                        + (initial != null && initial.trying)
                        + ", ambient="
                        + (initial != null && initial.ambientActive)
                        + ", hold="
                        + (initial != null && initial.holdRecording)
                        + ", upload="
                        + (initial != null && initial.uploadingVoice)
                        + ", camera="
                        + (initial != null && initial.cameraOn)
                        + ", read="
                        + (initial != null && !initial.readingId.isEmpty())
                        + ", imports="
                        + (initial == null ? -1 : initial.imports.size())
                        + ", outbox="
                        + (initial == null ? -1 : initial.outbox.size())
                        + ", working="
                        + (initial != null && initial.working != null)
                        + ", service="
                        + service());
        Settings originalSettings = controller.settings;
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        RoomRepository originalRepo = onState(() -> (RoomRepository) field("repo"));
        Object originalRoom = onState(() -> field("roomState"));
        String originalTailnet = initial.tailnetState;
        String originalAuth = (String) field("authUrl");
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return super.getSharedPreferences(name + ".access-qa", mode);
                    }
                };
        isolated.getSharedPreferences("pocket-e2.settings", 0).edit().clear().commit();
        isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
        Settings settings = new Settings(isolated);
        settings.saveConnection(
                "example.test", 443, true, "qa", "Access QA", "qa", "room", "qa-original");
        context.deleteDatabase(DATABASE);
        repository = new RoomRepository(context, DATABASE);
        repository.select("qa:access");
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
                        field("settings", settings);
                        field("channel", source);
                        field("repo", repository);
                        field("roomState", null);
                        field("opening", false);
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            await(this::online, 3000);
            check(online(), "isolated access room reaches the ordinary ready path");
            rapidPause(settings);
            checkpoint("rapid-pause");
            revoked(settings);
            checkpoint("credential-rejection");
            nodeAuthorization(settings);
            checkpoint("node-authorization");
            pauseStorage();
            checkpoint("pause-storage-recovery");
            queuedLogout(settings);
            checkpoint("superseded-logout");
            tryRejection(settings);
            httpRejection(settings);
        } finally {
            for (Exchange exchange : source.exchanges.values()) exchange.release.countDown();
            onState(
                    () -> {
                        invoke("finishSend");
                        field("tailnetActionEpoch", (Long) field("tailnetActionEpoch") + 1);
                        field("tailnetLogoutRequested", false);
                        field("connectionEpoch", (Integer) field("connectionEpoch") + 1);
                        source.closeLive();
                        repository.clear();
                        field("settings", originalSettings);
                        field("channel", original);
                        field("repo", originalRepo);
                        field("roomState", originalRoom);
                        field("tailnetState", originalTailnet);
                        field("authUrl", originalAuth);
                        field("opening", false);
                        field("connection", PocketController.Connection.OFFLINE);
                        field("retryNotBefore", 0L);
                        field("lastError", initial.lastError);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            repository.close();
            context.deleteDatabase(DATABASE);
            settings.clearConnection();
            isolated.getSharedPreferences("pocket-e2.settings", 0).edit().clear().commit();
            isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
            await(this::online, 35_000);
            check(
                    online()
                            && controller.settings == originalSettings
                            && !originalSettings.unauthorized()
                            && controller.snapshot().importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && controller.snapshot().quote.equals(initial.quote)
                            && controller.snapshot().chips.size() == initial.chips.size()
                            && controller.snapshot().outbox.isEmpty(),
                    "actual pairing, Online and composer survive isolated access failures");
            probe.runOnMainSync(screen::finish);
        }
        return "PASS "
                + assertions
                + " native access recovery assertions; access outcomes are prepared";
    }

    private void revoked(Settings settings) throws Exception {
        Exchange first = new Exchange(), next = new Exchange();
        String id = queue(first, true), successor = queue(next, false);
        check(first.started.await(2, TimeUnit.SECONDS), "voice enters the cancellable sender");
        await(this::service, 3000);
        check(
                service() && next.calls.get() == 0,
                "native upload service runs while the successor waits");
        source.listener.closed("Prepared close 4401 unauthorized");
        check(
                first.cancelled.await(1500, TimeUnit.MILLISECONDS),
                "credential rejection cancels the in-flight request promptly");
        await(() -> !service(), 3000);
        Outbox.Item retained = pending(id);
        check(
                settings.unauthorized()
                        && controller.snapshot().connection
                                == PocketController.Connection.NEEDS_PAIRING
                        && retained != null
                        && retained.state == Message.QUEUED
                        && pending(successor).state == Message.QUEUED
                        && Arrays.equals(
                                Files.readAllBytes(new File(retained.voiceFile).toPath()), body)
                        && source.groups.isEmpty()
                        && !controller.snapshot().uploadingVoice
                        && !service(),
                "rejection releases native upload resources and durably retains both original"
                        + " sends");
        int opened = source.opens.get();
        first.release.countDown();
        onState(
                () -> {
                    invoke("pump");
                    invoke("connect");
                    return null;
                });
        status("Running");
        Thread.sleep(700);
        check(
                source.opens.get() == opened
                        && first.calls.get() == 1
                        && next.calls.get() == 0
                        && controller.snapshot().connection
                                == PocketController.Connection.NEEDS_PAIRING,
                "late cancellation and passive running status cannot retry rejected credentials");
        onState(
                () -> {
                    settings.saveConnection(
                            "example.test", 443, true, "qa", "Access QA", "qa", "room", "qa-new");
                    invoke("connect");
                    return null;
                });
        check(
                next.started.await(3, TimeUnit.SECONDS),
                "local replacement credentials resume the retained queue");
        check(
                first.calls.get() == 2
                        && first.keys.equals(Arrays.asList(id, id))
                        && first.tokens.equals(Arrays.asList("qa-original", "qa-new"))
                        && Arrays.equals(first.bodies.get(0), body)
                        && Arrays.equals(first.bodies.get(1), body)
                        && first.surfaces.get(0).equals(first.surfaces.get(1)),
                "replacement access preserves the original key, bytes and surface while replacing"
                        + " only the token");
        next.release.countDown();
        await(() -> !controller.snapshot().uploadingVoice, 3000);
        clear();
    }

    private void rapidPause(Settings settings) throws Exception {
        CountDownLatch mainEntered = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(
                        () -> {
                            mainEntered.countDown();
                            try {
                                releaseMain.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        Exchange exchange = new Exchange();
        String id;
        try {
            check(
                    mainEntered.await(2, TimeUnit.SECONDS),
                    "native service creation is held behind the actual main owner");
            id = queue(exchange, true);
            check(
                    exchange.started.await(1500, TimeUnit.MILLISECONDS),
                    "a voice upload requests its service before the main owner can create it");
            status("NeedsMachineAuth");
            check(
                    exchange.cancelled.await(1500, TimeUnit.MILLISECONDS)
                            && pending(id).state == Message.QUEUED,
                    "authorization loss retires upload intent before service creation");
        } finally {
            releaseMain.countDown();
        }
        probe.runOnMainSync(() -> {});
        Thread.sleep(5500);
        check(
                !service() && !settings.unauthorized() && !controller.snapshot().uploadingVoice,
                "a retired pending foreground start clears without a native foreground-service"
                        + " crash");
        status("Running");
        await(this::online, 3000);
        clear();
    }

    private void clear() throws Exception {
        onState(
                () -> {
                    repository.clear();
                    invoke("settleSend");
                    invoke("reload");
                    return null;
                });
    }

    private void nodeAuthorization(Settings settings) throws Exception {
        Exchange exchange = new Exchange();
        String id = queue(exchange, true);
        check(
                exchange.started.await(2, TimeUnit.SECONDS),
                "node authorization fixture starts normally");
        ChannelConnection.LiveListener retired = source.listener;
        int opened = source.opens.get();
        status("NeedsMachineAuth");
        check(
                exchange.cancelled.await(1500, TimeUnit.MILLISECONDS),
                "lost node authorization cancels active HTTP");
        await(() -> !service(), 3000);
        check(
                !settings.unauthorized()
                        && settings.token().equals("qa-new")
                        && pending(id).state == Message.QUEUED
                        && !service()
                        && controller.snapshot().connection == PocketController.Connection.OFFLINE
                        && controller.snapshot().status.equals("需要登录 Tailscale"),
                "node authorization pauses upload without revoking the distinct room credential");
        retired.text(Source.ready().toString());
        onState(
                () -> {
                    invoke("connect");
                    invoke("pump");
                    return null;
                });
        Thread.sleep(500);
        check(
                source.opens.get() == opened && exchange.calls.get() == 1 && !online(),
                "a late ready frame and passive pumps cannot resume before node authorization");
        status("Running");
        await(() -> exchange.calls.get() == 2, 3000);
        check(
                online()
                        && exchange.calls.get() == 2
                        && exchange.keys.equals(Arrays.asList(id, id))
                        && exchange.tokens.equals(Arrays.asList("qa-new", "qa-new"))
                        && Arrays.equals(exchange.bodies.get(1), body),
                "renewed node authorization resumes the original message without pairing again");
        clear();
    }

    private void queuedLogout(Settings settings) throws Exception {
        CountDownLatch entered = new CountDownLatch(3), releaseWorkers = new CountDownLatch(1);
        Executor network = (Executor) field("network");
        for (int i = 0; i < 3; i++)
            network.execute(
                    () -> {
                        entered.countDown();
                        try {
                            releaseWorkers.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
        try {
            check(
                    entered.await(3, TimeUnit.SECONDS),
                    "ordinary workers are held before queuing the native logout");
            Exchange exchange = new Exchange();
            String id = queue(exchange, true);
            check(
                    exchange.started.await(2, TimeUnit.SECONDS),
                    "isolated sending remains independent of held ordinary workers");
            controller.logoutTailnet();
            check(
                    exchange.cancelled.await(1500, TimeUnit.MILLISECONDS),
                    "the public logout action pauses a send before native logout can run");
            int opened = source.opens.get();
            status("Running");
            onState(
                    () -> {
                        invoke("connect");
                        invoke("pump");
                        return null;
                    });
            Thread.sleep(400);
            check(
                    source.opens.get() == opened
                            && exchange.calls.get() == 1
                            && !settings.unauthorized()
                            && pending(id).state == Message.QUEUED
                            && controller.snapshot().tailnetState.equals("NeedsLogin")
                            && !online(),
                    "a stale Running status cannot override explicit logout or send another"
                            + " request");
        } finally {
            // Invalidate the queued native action before releasing any worker. Never log out the
            // real node.
            onState(
                    () -> {
                        field("tailnetActionEpoch", (Long) field("tailnetActionEpoch") + 1);
                        field("tailnetLogoutRequested", false);
                        return null;
                    });
            releaseWorkers.countDown();
        }
        status("Running");
        await(this::online, 3000);
        check(
                online() && !settings.unauthorized(),
                "a superseded queued logout leaves replacement access usable");
        clear();
    }

    private void pauseStorage() throws Exception {
        Exchange exchange = new Exchange();
        String id = queue(exchange, true);
        check(
                exchange.started.await(2, TimeUnit.SECONDS),
                "pause storage fixture begins its original upload");
        onState(
                () -> {
                    repository
                            .getWritableDatabase()
                            .execSQL(
                                    "CREATE TEMP TRIGGER qa_pause_write BEFORE INSERT ON pending"
                                        + " BEGIN SELECT RAISE(ABORT, 'Prepared access pause write"
                                        + " failure'); END");
                    return null;
                });
        try {
            status("NeedsLogin");
            check(
                    exchange.cancelled.await(1500, TimeUnit.MILLISECONDS),
                    "a failed queue write cannot prevent request cancellation");
            await(() -> !service(), 3000);
            check(
                    pending(id).state == Message.SENDING
                            && !service()
                            && !controller.snapshot().uploadingVoice
                            && controller.snapshot().connection
                                    == PocketController.Connection.OFFLINE
                            && Arrays.equals(
                                    Files.readAllBytes(new File(pending(id).voiceFile).toPath()),
                                    body),
                    "a native rejected pause transaction keeps its original durable bytes while"
                            + " releasing upload resources");
            int opened = source.opens.get();
            status("Running");
            check(
                    source.opens.get() == opened
                            && exchange.calls.get() == 1
                            && controller.snapshot().lastError.equals("无法保存待发送消息"),
                    "reauthorization cannot redial or send until interrupted state can be durably"
                            + " recovered");
        } finally {
            onState(
                    () -> {
                        repository.getWritableDatabase().execSQL("DROP TRIGGER qa_pause_write");
                        return null;
                    });
        }
        status("Running");
        await(() -> exchange.calls.get() == 2, 3000);
        check(
                online()
                        && exchange.keys.equals(Arrays.asList(id, id))
                        && Arrays.equals(exchange.bodies.get(1), body),
                "restored storage recovers the interrupted send under its unchanged identity and"
                        + " body");
        clear();
    }

    private void tryRejection(Settings settings) throws Exception {
        Exchange exchange = new Exchange(401);
        exchange.release.countDown();
        onState(
                () -> {
                    field("trying", true);
                    return null;
                });
        try {
            String id = queue(exchange, false);
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.id.equals(id)
                                                            && item.state == Message.FAILED),
                    2000);
            check(
                    pending(id).state == Message.FAILED
                            && !settings.unauthorized()
                            && online()
                            && exchange.calls.get() == 1,
                    "a prepared try-mode rejection fails locally without invalidating pairing or"
                            + " spinning retries");
        } finally {
            onState(
                    () -> {
                        field("trying", false);
                        return null;
                    });
            clear();
        }
    }

    private void httpRejection(Settings settings) throws Exception {
        Exchange refused = new Exchange(401);
        refused.release.countDown();
        String id = queue(refused, false);
        await(settings::unauthorized, 2500);
        check(
                settings.unauthorized()
                        && pending(id).state == Message.QUEUED
                        && controller.snapshot().connection
                                == PocketController.Connection.NEEDS_PAIRING
                        && source.groups.isEmpty()
                        && refused.calls.get() == 1,
                "an HTTP unauthorized body retains the message and stops access without another"
                        + " send");
        Exchange replacement = new Exchange();
        replacement.release.countDown();
        source.exchanges.put(id, replacement);
        onState(
                () -> {
                    settings.saveConnection(
                            "example.test", 443, true, "qa", "Access QA", "qa", "room", "qa-final");
                    invoke("connect");
                    return null;
                });
        check(
                replacement.started.await(3, TimeUnit.SECONDS),
                "local replacement credentials recover an HTTP rejection");
        check(
                replacement.keys.equals(Collections.singletonList(id))
                        && replacement.tokens.equals(Collections.singletonList("qa-final")),
                "the retained rejected message keeps its key and uses the replacement credential");
        clear();
    }

    private static final class Exchange {
        final int status;
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1),
                cancelled = new CountDownLatch(1),
                release = new CountDownLatch(1);
        final List<String> keys = new CopyOnWriteArrayList<>(),
                tokens = new CopyOnWriteArrayList<>(),
                surfaces = new CopyOnWriteArrayList<>();
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();

        Exchange() {
            this(202);
        }

        Exchange(int status) {
            this.status = status;
        }

        ChannelConnection.Response request(String token, JSONObject headers, byte[] body)
                throws Exception {
            calls.incrementAndGet();
            keys.add(headers.optString("Idempotency-Key"));
            tokens.add(token);
            surfaces.add(headers.toString());
            bodies.add(body.clone());
            started.countDown();
            if (!release.await(8, TimeUnit.SECONDS))
                throw new IOException("Prepared access request expired");
            return Source.json(
                    status,
                    Json.object(
                            "message_id",
                            headers.optString("Idempotency-Key"),
                            "error",
                            status == 401 ? "unauthorized" : ""));
        }
    }

    /** No network delegate, native node, microphone or speech path exists in this source. */
    private static final class Source implements ChannelConnection {
        final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();
        final Map<Long, Exchange> groups = new ConcurrentHashMap<>();
        final AtomicInteger opens = new AtomicInteger();
        volatile LiveListener listener;
        volatile boolean open;

        static Response json(int status, JSONObject body) {
            return new Response(status, body.toString().getBytes(StandardCharsets.UTF_8));
        }

        static JSONObject ready() {
            return Json.object(
                    "type",
                    "ready",
                    "api",
                    2,
                    "seq",
                    0,
                    "turn",
                    JSONObject.NULL,
                    "messages",
                    new JSONArray());
        }

        public void beginRequest(long id) {}

        public void endRequest(long id) {
            Exchange exchange = groups.remove(id);
            if (exchange != null) {
                exchange.cancelled.countDown();
                exchange.release.countDown();
            }
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
            Exchange exchange = exchanges.get(headers.optString("Idempotency-Key"));
            if (exchange == null) throw new AssertionError("Unregistered access fixture");
            groups.put(id, exchange);
            Response response = exchange.request(token, headers, body);
            if (!groups.containsKey(id)) throw new IOException("Prepared cancelled request");
            return response;
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            if (method.equals("GET") && path.equals("/healthz"))
                return json(200, Json.object("ok", true));
            if (method.equals("GET") && path.startsWith("/api/state?"))
                return json(200, Json.object("room", "qa"));
            if (method.equals("GET") && path.startsWith("/api/imlog?"))
                return json(200, Json.object("entries", new JSONArray(), "seq", 0));
            throw new AssertionError("Unexpected unscoped access request");
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
            opens.incrementAndGet();
            listener.text(ready().toString());
        }

        public boolean isOpen() {
            return open;
        }

        public void closeLive() {
            open = false;
        }

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] bytes) {
            throw new AssertionError("No microphone in access acceptance");
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current) {
            throw new AssertionError("No synthesis in access acceptance");
        }

        public void cancelStream(long id) {}
    }
}
