// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

    private boolean awake() {
        return context.getSystemService(android.os.PowerManager.class).isInteractive();
    }

    private boolean foreground() {
        try {
            return onState(() -> (Boolean) field("foreground"));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void shell(String command) throws Exception {
        try (java.io.InputStream input =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        probe.getUiAutomation().executeShellCommand(command))) {
            byte[] discard = new byte[256];
            while (input.read(discard) != -1) {}
        }
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
            ordinaryAccess(settings);
            discardedRejection(settings);
            checkpoint("ordinary-request-access");
        } finally {
            for (Exchange exchange : source.exchanges.values()) exchange.release.countDown();
            for (Exchange exchange : source.routes.values()) exchange.release.countDown();
            // Restoration models a user returning; a background idle room should not redial itself.
            shell("input keyevent 223");
            await(() -> !awake() && !foreground(), 3000);
            boolean stoppedForRestore = !awake() && !foreground();
            onState(
                    () -> {
                        invoke("finishSend");
                        invoke("retireRoomAccess");
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
                        invoke("restoreDraft");
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            repository.close();
            context.deleteDatabase(DATABASE);
            new File(context.getCacheDir(), "access-qa/ordinary.txt").delete();
            settings.clearConnection();
            isolated.getSharedPreferences("pocket-e2.settings", 0).edit().clear().commit();
            isolated.getSharedPreferences("pocket-secrets", 0).edit().clear().commit();
            Bundle restoration = new Bundle();
            restoration.putString("checkpoint", "access-restore-before-wake");
            restoration.putString("connection", controller.snapshot().connection.name());
            restoration.putBoolean("foreground", foreground());
            restoration.putBoolean("awake", awake());
            probe.sendStatus(0, restoration);
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            await(this::online, 35_000);
            PocketController.Snapshot restored = controller.snapshot();
            check(
                    stoppedForRestore,
                    "access restoration begins after an actual screen-off/Activity stop");
            check(
                    online()
                            && controller.settings == originalSettings
                            && !originalSettings.unauthorized()
                            && controller.snapshot().importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && controller.snapshot().quote.equals(initial.quote)
                            && controller.snapshot().chips.size() == initial.chips.size()
                            && controller.snapshot().outbox.isEmpty()
                            && restored.upgrade == initial.upgrade,
                    "actual pairing, Online and composer survive isolated access failures:"
                            + " connection="
                            + restored.connection
                            + ", awake="
                            + awake()
                            + ", foreground="
                            + foreground()
                            + ", settingsMatch="
                            + (controller.settings == originalSettings)
                            + ", unauthorized="
                            + originalSettings.unauthorized()
                            + ", targetMatches="
                            + restored.importTarget.equals(initial.importTarget)
                            + ", draftMatches="
                            + controller.draft().equals(initial.draft)
                            + ", quoteMatches="
                            + restored.quote.equals(initial.quote)
                            + ", chipCountMatches="
                            + (restored.chips.size() == initial.chips.size())
                            + ", outbox="
                            + restored.outbox.size()
                            + ", upgradeMatches="
                            + (restored.upgrade == initial.upgrade));
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
        onState(() -> null);
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

    private void recover(Settings settings) throws Exception {
        source.routes.clear();
        onState(
                () -> {
                    settings.saveConnection(
                            "example.test",
                            443,
                            true,
                            "qa",
                            "Access QA",
                            "qa",
                            "room",
                            "qa-renewed-" + UUID.randomUUID());
                    field("tailnetState", "Running");
                    field("retryNotBefore", 0L);
                    invoke("connect");
                    return null;
                });
        await(this::online, 3000);
        check(
                online() && !settings.unauthorized(),
                "replacement credentials restore ordinary access");
    }

    private void history() throws Exception {
        onState(
                () -> {
                    invoke("historyToday");
                    return null;
                });
    }

    private Attachment chip() throws Exception {
        File file = new File(context.getCacheDir(), "access-qa/ordinary.txt");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "Prepared local attachment".getBytes(StandardCharsets.UTF_8));
        Attachment attachment =
                new Attachment(
                        "access-qa-ordinary.txt",
                        "text/plain",
                        file.getPath(),
                        "",
                        "",
                        file.length(),
                        Attachment.FAILED);
        onState(
                () -> {
                    @SuppressWarnings("unchecked")
                    List<Attachment> chips = (List<Attachment>) field("chips");
                    chips.clear();
                    chips.add(attachment);
                    field("draft", "Prepared retained attachment draft");
                    invoke("saveDraft");
                    invoke("publish");
                    return null;
                });
        return attachment;
    }

    private Attachment remote() throws Exception {
        byte[] bytes = "Prepared cached attachment".getBytes(StandardCharsets.UTF_8);
        StringBuilder digest = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            digest.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new Attachment(
                "access-qa-remote.txt",
                "text/plain",
                "",
                digest.toString(),
                "/qa/ordinary.txt",
                bytes.length,
                Attachment.UPLOADED);
    }

    private static final class FileReceipt implements PocketController.FileResult {
        final AtomicInteger ready = new AtomicInteger(), failed = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);

        public void ready(File file, String mime) {
            ready.incrementAndGet();
            done.countDown();
        }

        public void failed() {
            failed.incrementAndGet();
            done.countDown();
        }
    }

    private void rejected(Settings settings, Exchange exchange, String label) throws Exception {
        check(
                exchange.started.await(2, TimeUnit.SECONDS),
                label + " reaches the ordinary transport");
        await(settings::unauthorized, 2500);
        onState(() -> null);
        check(
                settings.unauthorized()
                        && controller.snapshot().connection
                                == PocketController.Connection.NEEDS_PAIRING
                        && controller.snapshot().lastError.equals("设备凭据已失效，请重新配对")
                        && source.groups.isEmpty(),
                label + " revokes current credentials and keeps the shared authorization notice");
    }

    private void ordinaryAccess(Settings settings) throws Exception {
        for (String operation :
                new String[] {"history", "older", "upload", "download", "background", "speech"}) {
            Exchange exchange = new Exchange(401);
            exchange.release.countDown();
            String route =
                    operation.equals("upload")
                            ? "/api/upload?"
                            : operation.equals("download")
                                    ? "/api/attachment?"
                                    : operation.equals("speech") ? "/api/speech?" : "/api/imlog?";
            source.routes.put(route, exchange);
            FileReceipt receipt = new FileReceipt();
            AtomicInteger completed = new AtomicInteger();
            Attachment attachment = operation.equals("upload") ? chip() : null;
            switch (operation) {
                case "history":
                    history();
                    break;
                case "older":
                    controller.loadOlder();
                    break;
                case "upload":
                    controller.retryAttachment(attachment.localPath);
                    break;
                case "download":
                    controller.download(remote(), receipt);
                    break;
                case "background":
                    controller.backgroundFetch(completed::incrementAndGet);
                    break;
                case "speech":
                    controller.readAloud(
                            Message.mine(
                                    "qa-read",
                                    "Prepared speech",
                                    "phone",
                                    0,
                                    Message.DELIVERED,
                                    Collections.emptyList()));
                    break;
            }
            rejected(settings, exchange, operation);
            if (operation.equals("upload"))
                check(
                        controller.snapshot().chips.size() == 1
                                && controller.snapshot().chips.get(0).state == Attachment.FAILED
                                && new File(attachment.localPath).isFile()
                                && controller.draft().equals("Prepared retained attachment draft"),
                        "unauthorized upload retains a retryable chip, local bytes and draft");
            if (operation.equals("download"))
                check(
                        receipt.done.await(2, TimeUnit.SECONDS)
                                && receipt.failed.get() == 1
                                && receipt.ready.get() == 0,
                        "unauthorized download completes once without opening a file");
            if (operation.equals("background")) {
                await(() -> completed.get() > 0, 2000);
                check(completed.get() == 1, "unauthorized catch-up finishes its job exactly once");
            }
            if (operation.equals("speech"))
                check(
                        controller.snapshot().readingId.isEmpty()
                                && !(Boolean) onState(() -> field("readInFlight")),
                        "unauthorized speech releases native read state");
            recover(settings);
        }
        for (int status : new int[] {401, 403}) {
            Exchange exchange = new Exchange(status);
            exchange.response =
                    Source.json(
                            status, Json.object("error", status == 401 ? "other" : "unauthorized"));
            exchange.release.countDown();
            source.routes.put("/api/imlog?", exchange);
            history();
            check(exchange.started.await(2, TimeUnit.SECONDS), "ordinary error history dispatches");
            await(() -> controller.snapshot().lastError.equals("暂时无法加载历史，可稍后重试"), 2000);
            check(
                    !settings.unauthorized() && online(),
                    "ordinary 401/403 cannot revoke the room token");
            source.routes.clear();
        }
        Exchange anonymous = new Exchange(401);
        anonymous.release.countDown();
        source.routes.put("/healthz", anonymous);
        try {
            top.kuaner.pockete2.net.ChannelClient client =
                    onState(
                            () -> {
                                Method method = PocketController.class.getDeclaredMethod("client");
                                method.setAccessible(true);
                                return (top.kuaner.pockete2.net.ChannelClient)
                                        method.invoke(controller);
                            });
            client.health();
        } catch (top.kuaner.pockete2.net.ChannelClient.Failure expected) {
        }
        check(
                !settings.unauthorized() && online(),
                "anonymous health rejection cannot revoke room credentials");
        source.routes.clear();
        cancelledOrdinary(settings);
        lateOrdinary(settings);
        offlineFiles(settings);
        onState(
                () -> {
                    @SuppressWarnings("unchecked")
                    List<Attachment> chips = (List<Attachment>) field("chips");
                    for (Attachment value : chips) new File(value.localPath).delete();
                    chips.clear();
                    field("draft", "");
                    invoke("saveDraft");
                    invoke("publish");
                    return null;
                });
    }

    private void cancelledOrdinary(Settings settings) throws Exception {
        Attachment attachment = chip();
        Exchange history = new Exchange(200),
                upload = new Exchange(200),
                download = new Exchange(200);
        source.routes.put("/api/imlog?", history);
        source.routes.put("/api/upload?", upload);
        source.routes.put("/api/attachment?", download);
        FileReceipt receipt = new FileReceipt();
        history();
        controller.retryAttachment(attachment.localPath);
        controller.download(remote(), receipt);
        check(
                history.started.await(2, TimeUnit.SECONDS)
                        && upload.started.await(2, TimeUnit.SECONDS)
                        && download.started.await(2, TimeUnit.SECONDS),
                "all ordinary workers hold actual controller requests before cancellation");
        int calls = history.calls.get();
        AtomicInteger completed = new AtomicInteger();
        controller.backgroundFetch(completed::incrementAndGet);
        controller.loadOlder();
        onState(() -> null);
        status("NeedsMachineAuth");
        check(
                history.cancelled.await(1500, TimeUnit.MILLISECONDS)
                        && upload.cancelled.await(1500, TimeUnit.MILLISECONDS)
                        && download.cancelled.await(1500, TimeUnit.MILLISECONDS),
                "node authorization loss cancels history, upload and download registrations");
        await(() -> completed.get() == 1 && receipt.failed.get() == 1, 2500);
        check(
                completed.get() == 1
                        && receipt.failed.get() == 1
                        && history.calls.get() == calls
                        && !settings.unauthorized()
                        && source.groups.isEmpty()
                        && controller.snapshot().lastError.equals("请完成 Tailscale 授权")
                        && controller.snapshot().chips.get(0).state == Attachment.FAILED,
                "retired queued requests never dispatch, jobs finish and late errors preserve"
                        + " authorization feedback");
        recover(settings);
    }

    private void lateOrdinary(Settings settings) throws Exception {
        for (int status : new int[] {200, 401}) {
            Exchange delayed = new Exchange(status);
            delayed.ignoreCancellation = true;
            delayed.response =
                    status == 200
                            ? Source.json(200, Json.object("entries", new JSONArray(), "seq", 919))
                            : null;
            source.routes.put("/api/imlog?", delayed);
            AtomicInteger completed = new AtomicInteger();
            controller.backgroundFetch(completed::incrementAndGet);
            check(delayed.started.await(2, TimeUnit.SECONDS), "old-token catch-up is in flight");
            status("NeedsMachineAuth");
            recover(settings);
            long cursor = controller.snapshot().cursor;
            delayed.release.countDown();
            await(() -> completed.get() == 1, 2000);
            check(
                    completed.get() == 1
                            && online()
                            && !settings.unauthorized()
                            && controller.snapshot().cursor == cursor,
                    "same-room replacement ignores a late old-token success or unauthorized"
                            + " response");
        }
        Attachment attachment = chip();
        Exchange delayed = new Exchange(200);
        delayed.response = Source.json(200, Json.object("path", "/qa/late-upload.txt"));
        source.routes.put("/api/upload?", delayed);
        controller.retryAttachment(attachment.localPath);
        check(
                delayed.started.await(2, TimeUnit.SECONDS),
                "old upload starts before owner admission is held");
        onState(
                () -> {
                    delayed.release.countDown();
                    java.util.concurrent.ThreadPoolExecutor network =
                            (java.util.concurrent.ThreadPoolExecutor) field("network");
                    long until = SystemClock.elapsedRealtime() + 2000;
                    while (network.getActiveCount() != 0 && SystemClock.elapsedRealtime() < until)
                        Thread.sleep(10);
                    if (network.getActiveCount() != 0)
                        throw new AssertionError("upload did not finish while owner was held");
                    invoke("retireRoomAccess");
                    settings.saveConnection(
                            "example.test",
                            443,
                            true,
                            "qa",
                            "Access QA",
                            "qa",
                            "room",
                            "qa-after-return");
                    invoke("publish");
                    return null;
                });
        onState(() -> null);
        check(
                controller.snapshot().chips.get(0).state == Attachment.FAILED
                        && controller.snapshot().chips.get(0).remotePath.isEmpty(),
                "a successful upload already queued on the owner cannot update a new credential"
                        + " generation");
        source.routes.clear();
    }

    private void offlineFiles(Settings settings) throws Exception {
        Attachment local = chip(), cached = remote();
        AttachmentStore store = new AttachmentStore(context);
        File cache =
                store.cache(cached, "Prepared cached attachment".getBytes(StandardCharsets.UTF_8));
        try {
            status("NeedsMachineAuth");
            FileReceipt first = new FileReceipt(), second = new FileReceipt();
            controller.previewAttachment(local, first);
            controller.download(cached, second);
            check(
                    first.done.await(2, TimeUnit.SECONDS)
                            && second.done.await(2, TimeUnit.SECONDS)
                            && first.ready.get() == 1
                            && second.ready.get() == 1,
                    "local originals and validated cached files remain available without node"
                            + " authorization");
            recover(settings);
        } finally {
            cache.delete();
        }
    }

    private void discardedRejection(Settings settings) throws Exception {
        Exchange discarded = new Exchange(401);
        discarded.ignoreCancellation = true;
        String id = queue(discarded, false);
        check(
                discarded.started.await(2, TimeUnit.SECONDS),
                "a discard race begins under current credentials");
        controller.discard(id);
        onState(() -> null);
        discarded.release.countDown();
        Executor network = (Executor) field("sendNetwork");
        CountDownLatch returned = new CountDownLatch(1);
        network.execute(returned::countDown);
        check(
                returned.await(2, TimeUnit.SECONDS),
                "the retired sender returns before inspecting access");
        onState(() -> null);
        check(
                !settings.unauthorized() && online() && pending(id) == null,
                "a late unauthorized response from a discarded operation cannot reject current"
                        + " access");
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
        volatile boolean ignoreCancellation;
        volatile ChannelConnection.Response response;

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
            return response != null
                    ? response
                    : Source.json(
                            status,
                            Json.object(
                                    "message_id",
                                    headers.optString("Idempotency-Key"),
                                    "error",
                                    status == 401 ? "unauthorized" : ""));
        }
    }

    /** Prepared HTTP/speech statuses only; no real network delegate or microphone path. */
    private static final class Source implements ChannelConnection {
        final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();
        final Map<String, Exchange> routes = new ConcurrentHashMap<>();
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
                if (!exchange.ignoreCancellation) exchange.release.countDown();
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
            if (exchange == null)
                for (Map.Entry<String, Exchange> route : routes.entrySet())
                    if (path.startsWith(route.getKey())) {
                        exchange = route.getValue();
                        break;
                    }
            if (exchange == null)
                return request(endpoint, token, method, path, headers, body, timeout);
            groups.put(id, exchange);
            Response response = exchange.request(token, headers, body);
            if (!groups.containsKey(id) && !exchange.ignoreCancellation)
                throw new IOException("Prepared cancelled request");
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
                return json(200, Json.object("room", "qa", "date", "2026-11-14"));
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
                BooleanSupplier current)
                throws Exception {
            Exchange exchange = routes.get("/api/speech?");
            if (exchange == null) throw new AssertionError("Unregistered prepared speech status");
            return exchange.request(token, Json.object(), body);
        }

        public void cancelStream(long id) {}
    }
}
