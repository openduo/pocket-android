// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Endpoint;
import top.kuaner.pockete2.core.Json;
import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.core.Outbox;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.RoomRepository;
import top.kuaner.pockete2.net.ChannelConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Prepared transport faults on the shipping controller, restricted to the disposable room. */
final class LiveRecoveryProbe {
    private final PocketController controller;
    private final Executor state;
    private final ChannelConnection original;
    private final Observed source;
    private int assertions;

    LiveRecoveryProbe(PocketController controller) throws Exception {
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

    private void channel(ChannelConnection value) throws Exception {
        Field field = PocketController.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(controller, value);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private void send() throws Exception {
        Method method = PocketController.class.getDeclaredMethod("send", JSONObject.class);
        method.setAccessible(true);
        method.invoke(controller, Json.object("type", "qa_live_pressure"));
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

    private boolean online() {
        return controller.snapshot().connection == PocketController.Connection.ONLINE;
    }

    int run(boolean nativeCapture) throws Exception {
        check(
                controller.snapshot().trying && online(),
                "live fault injection starts only in an online disposable room");
        String draft = controller.draft();
        int pending = controller.snapshot().outbox.size();
        try {
            onState(
                    () -> {
                        channel(source);
                        invoke("reconnect");
                        return null;
                    });
            await(() -> source.opens.get() == 1 && online(), 5000);
            check(source.opens.get() == 1 && online(), "the observed live connection is ready");
            startup();
            int opened = source.opens.get(), closed = source.closes.get();
            ChannelConnection.LiveListener stale = source.listeners.get(0);
            source.failText.set(true);
            onState(
                    () -> {
                        send();
                        return null;
                    });
            final int textOpened = opened;
            await(() -> source.opens.get() > textOpened && online(), 6000);
            check(
                    source.failedWhileOpen.get()
                            && source.closes.get() == closed + 1
                            && source.opens.get() == opened + 1
                            && online(),
                    "a text write failure retires a still-open socket and actually redials");

            stale.closed("Prepared late callback from the replaced socket");
            Thread.sleep(1200);
            check(
                    source.opens.get() == opened + 1 && online(),
                    "a replaced socket's delayed close cannot tear down the new connection");

            opened = source.opens.get();
            closed = source.closes.get();
            source.failBinary.set(true);
            onState(
                    () -> {
                        Method method =
                                PocketController.class.getDeclaredMethod(
                                        "sendBinary", byte[].class);
                        method.setAccessible(true);
                        method.invoke(controller, (Object) new byte[] {1});
                        return null;
                    });
            final int binaryOpened = opened;
            await(() -> source.opens.get() > binaryOpened && online(), 6000);
            check(
                    !source.failBinary.get()
                            && source.closes.get() == closed + 1
                            && source.opens.get() == opened + 1
                            && online(),
                    "a failed binary write uses the same recovery path without a reader close: "
                            + "opens="
                            + source.opens.get()
                            + "/"
                            + opened
                            + ", closes="
                            + source.closes.get()
                            + "/"
                            + closed
                            + ", state="
                            + controller.snapshot().connection);

            opened = source.opens.get();
            closed = source.closes.get();
            source.blockText.set(true);
            onState(
                    () -> {
                        send();
                        return null;
                    });
            check(
                    source.blocked.await(2, TimeUnit.SECONDS),
                    "a prepared blocked write occupies the normal bounded socket writer");
            int queued =
                    onState(
                            () -> {
                                for (int i = 0; i < 160; i++) send();
                                return ((ThreadPoolExecutor) field("socketWriter"))
                                        .getQueue()
                                        .size();
                            });
            final int pressureOpened = opened;
            await(() -> source.opens.get() > pressureOpened && online(), 6000);
            check(
                    queued == 128
                            && source.closes.get() == closed + 1
                            && source.opens.get() == opened + 1
                            && source.pressureDelivered.get() == 0
                            && online(),
                    "queue overflow closes once, drops old queued writes and recovers without"
                            + " leaking them onto the replacement socket");
            if (nativeCapture) ambientExit();
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().outbox.size() == pending,
                    "live transport recovery preserves the composer and sends no messages");
            int pathOpened = source.opens.get();
            int pathClosed = source.closes.get();
            int pathPings = source.pings.get();
            onState(
                    () -> {
                        invoke("networkPathChanged");
                        return null;
                    });
            await(() -> source.pings.get() > pathPings, 3000);
            check(
                    source.pings.get() > pathPings
                            && source.opens.get() == pathOpened
                            && source.closes.get() == pathClosed
                            && online(),
                    "a healthy path change pings and keeps the existing live socket");
            source.failPing.set(true);
            onState(
                    () -> {
                        invoke("networkPathChanged");
                        return null;
                    });
            await(() -> source.pings.get() > pathPings + 1, 3000);
            await(() -> source.closes.get() == pathClosed + 1 && !online(), 3000);
            check(
                    source.closes.get() == pathClosed + 1 && !online(),
                    "a path ping failure retires the stale live generation");
            source.failPing.set(false);
            await(() -> source.opens.get() > pathOpened && online(), 5000);
            check(
                    source.opens.get() == pathOpened + 1 && online(),
                    "a stale path is redialled after the ping failure");
            return assertions;
        } finally {
            source.metadataRelease.countDown();
            source.workerRelease.countDown();
            source.release.countDown();
            source.restoreRelease.countDown();
            controller.releaseHold(true);
            controller.endAmbient();
            controller.onForeground(true);
            onState(
                    () -> {
                        channel(original);
                        invoke("reconnect");
                        return null;
                    });
        }
    }

    private void startup() throws Exception {
        source.blockHealth.set(true);
        source.blockState.set(true);
        int opened = source.opens.get();
        onState(
                () -> {
                    invoke("reconnect");
                    return null;
                });
        await(() -> source.opens.get() > opened && online(), 3000);
        check(
                source.opens.get() == opened + 1 && online(),
                "slow health/state requests cannot delay the real-time connection");
        check(
                source.metadataEntered.await(2, TimeUnit.SECONDS),
                "both supplemental metadata requests are actually held at the transport boundary");
        CountDownLatch workerEntered = new CountDownLatch(1);
        ((ExecutorService) field("network"))
                .execute(
                        () -> {
                            workerEntered.countDown();
                            try {
                                source.workerRelease.await(8, TimeUnit.SECONDS);
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        });
        check(
                workerEntered.await(2, TimeUnit.SECONDS),
                "all ordinary HTTP workers are occupied before recovery");
        int beforeRecovery = source.opens.get();
        onState(
                () -> {
                    invoke("reconnect");
                    return null;
                });
        await(() -> source.opens.get() > beforeRecovery && online(), 3000);
        check(
                source.opens.get() == beforeRecovery + 1 && online(),
                "reconnection has its own worker when every HTTP worker is occupied");
        int rebound = source.rebinds.get(), pinged = source.pings.get();
        int foregroundOpened = source.opens.get();
        controller.onForeground(true);
        await(() -> source.pings.get() > pinged, 2000);
        check(
                source.rebinds.get() == rebound + 1
                        && source.pings.get() == pinged + 1
                        && source.opens.get() == foregroundOpened
                        && online(),
                "foreground rebind and live ping do not wait behind occupied HTTP workers");
        onState(
                () -> {
                    RoomRepository repo = (RoomRepository) field("repo");
                    String id = source.fixtureId;
                    repo.enqueue(
                            Message.mine(
                                    id,
                                    "Prepared isolated send",
                                    "",
                                    0,
                                    Message.QUEUED,
                                    java.util.Collections.emptyList()),
                            new Outbox.Item(
                                    id,
                                    "Prepared isolated send",
                                    false,
                                    "",
                                    "",
                                    0,
                                    java.util.Collections.emptyList(),
                                    Message.QUEUED,
                                    "",
                                    "",
                                    System.currentTimeMillis(),
                                    0));
                    invoke("reload");
                    invoke("pump");
                    return null;
                });
        check(
                source.messageEntered.await(1500, TimeUnit.MILLISECONDS),
                "a queued message reaches HTTP while all ordinary workers remain held");
        onState(
                () -> {
                    Method frame =
                            PocketController.class.getDeclaredMethod(
                                    "frame", String.class, boolean.class);
                    frame.setAccessible(true);
                    frame.invoke(
                            controller,
                            Json.object(
                                            "type",
                                            "row",
                                            "seq",
                                            controller.snapshot().cursor + 1,
                                            "row",
                                            Json.object(
                                                    "kind",
                                                    "typed",
                                                    "utt_id",
                                                    source.fixtureId,
                                                    "text",
                                                    "Prepared isolated send",
                                                    "at",
                                                    java.time.Instant.now().toString()))
                                    .toString(),
                            true);
                    return null;
                });
        check(
                controller.snapshot().outbox.isEmpty(),
                "a prepared log row settles the independent send before metadata is released");
        source.metadataRelease.countDown();
        source.workerRelease.countDown();
        await(() -> source.states.get() >= 2, 3000);
        check(
                onState(
                                () -> {
                                    top.kuaner.pockete2.net.ChannelClient.State room =
                                            (top.kuaner.pockete2.net.ChannelClient.State)
                                                    field("roomState");
                                    return room != null
                                            && !room.roomName.equals("Expired metadata");
                                })
                        && online(),
                "late metadata from the replaced connection cannot overwrite the current room");
    }

    private void ambientExit() throws Exception {
        controller.ambientToggle();
        await(
                () ->
                        controller.snapshot().ambientActive
                                && source.binary.get() > 0
                                && top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                7000);
        check(
                controller.snapshot().ambientActive
                        && source.binary.get() > 0
                        && top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                "ambient recovery uses actual native capture and ordinary hello/uplink");
        controller.beginHold();
        await(() -> controller.snapshot().holdLive && source.events.contains("mute:false"), 2000);
        check(
                controller.snapshot().holdLive && source.events.contains("mute:false"),
                "holding a native note actually turns the room microphone switch off");
        source.events.clear();
        int opened = source.opens.get();
        String releaseState =
                onState(
                        () -> {
                            top.kuaner.pockete2.core.AmbientInput input =
                                    (top.kuaner.pockete2.core.AmbientInput) field("ambientInput");
                            return "hello="
                                    + input.helloSent()
                                    + ", muted="
                                    + input.pressMuted()
                                    + ", restore="
                                    + (input.releaseMicrophone() != null);
                        });
        controller.endAmbient();
        await(
                () ->
                        !controller.snapshot().ambientActive
                                && source.opens.get() > opened
                                && online(),
                6000);
        List<String> events = new java.util.ArrayList<>(source.events);
        int restore = events.indexOf("mute:true"), close = events.indexOf("close");
        check(
                restore >= 0 && close > restore && !controller.snapshot().ambientActive && online(),
                "leaving ambient during a hold restores the room mic before releasing its socket: "
                        + releaseState
                        + "; events="
                        + events);
        controller.releaseHold(true);
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 4000);
        check(
                !controller.snapshot().holdRecording
                        && !top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                "cancelled overlap releases native capture without creating a voice message");

        controller.ambientToggle();
        await(
                () ->
                        controller.snapshot().ambientActive
                                && top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                6000);
        controller.beginHold();
        await(() -> controller.snapshot().holdLive && source.events.contains("mute:false"), 2000);
        onState(() -> null);
        source.events.clear();
        source.blockRestore.set(true);
        int previousOpened = source.opens.get();
        controller.onForeground(false);
        check(
                source.restoreEntered.await(3, TimeUnit.SECONDS),
                "background cancellation retains a final unmute on the normal socket writer");
        await(() -> !top.kuaner.pockete2.audio.input.RecordingInput.isActive(), 3000);
        check(
                !controller.snapshot().ambientActive
                        && !controller.snapshot().holdRecording
                        && !top.kuaner.pockete2.audio.input.RecordingInput.isActive()
                        && !source.events.contains("close"),
                "capture and the hold end promptly while socket teardown waits off the state"
                        + " owner");
        source.restoreRelease.countDown();
        await(() -> source.events.contains("close"), 3000);
        events = new java.util.ArrayList<>(source.events);
        check(
                events.indexOf("mute:true") >= 0
                        && events.indexOf("close") > events.indexOf("mute:true")
                        && source.opens.get() == previousOpened,
                "the background callback restores the mic before close and does not redial an idle"
                        + " room");
        controller.onForeground(true);
        await(() -> source.opens.get() > previousOpened && online(), 5000);
        check(online(), "foregrounding reconnects normally after the deferred final write");
    }

    private static final class Observed implements ChannelConnection {
        final ChannelConnection delegate;
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicInteger pings = new AtomicInteger();
        final AtomicInteger rebinds = new AtomicInteger(), states = new AtomicInteger();
        final AtomicInteger pressureDelivered = new AtomicInteger();
        final AtomicInteger binary = new AtomicInteger();
        final AtomicBoolean failText = new AtomicBoolean(), failBinary = new AtomicBoolean();
        final AtomicBoolean failPing = new AtomicBoolean();
        final AtomicBoolean blockText = new AtomicBoolean(), failedWhileOpen = new AtomicBoolean();
        final AtomicBoolean blockRestore = new AtomicBoolean();
        final AtomicBoolean blockHealth = new AtomicBoolean(), blockState = new AtomicBoolean();
        final String fixtureId = java.util.UUID.randomUUID().toString();
        final CountDownLatch messageEntered = new CountDownLatch(1);
        final CountDownLatch metadataEntered = new CountDownLatch(2);
        final CountDownLatch metadataRelease = new CountDownLatch(1);
        final CountDownLatch workerRelease = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch restoreEntered = new CountDownLatch(1),
                restoreRelease = new CountDownLatch(1);
        final List<LiveListener> listeners = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();

        Observed(ChannelConnection delegate) {
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
            if (method.equals("POST")
                    && path.startsWith("/api/messages?")
                    && headers.optString("Idempotency-Key").equals(fixtureId)) {
                messageEntered.countDown();
                return new Response(
                        202,
                        Json.object("message_id", fixtureId)
                                .toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean heldState =
                    path.startsWith("/api/state") && blockState.compareAndSet(true, false);
            if (heldState || path.equals("/healthz") && blockHealth.compareAndSet(true, false)) {
                metadataEntered.countDown();
                if (!metadataRelease.await(8, TimeUnit.SECONDS))
                    throw new java.io.IOException("Prepared metadata delay expired");
                if (heldState) {
                    states.incrementAndGet();
                    return new Response(
                            200,
                            Json.object("room", "try", "room_name", "Expired metadata")
                                    .toString()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (path.startsWith("/api/state")) states.incrementAndGet();
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
            opens.incrementAndGet();
            listeners.add(listener);
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
            if (frame.optString("type").equals("mute")
                    && frame.optBoolean("on")
                    && blockRestore.compareAndSet(true, false)) {
                restoreEntered.countDown();
                if (!restoreRelease.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Final-unmute probe was not released");
            }
            if (frame.optString("type").equals("qa_live_pressure")) {
                if (failText.compareAndSet(true, false)) {
                    failedWhileOpen.set(delegate.isOpen());
                    throw new java.io.IOException("Prepared write failure while still open");
                }
                if (blockText.compareAndSet(true, false)) {
                    blocked.countDown();
                    if (!release.await(6, TimeUnit.SECONDS))
                        throw new AssertionError("Blocked write was not closed");
                    throw new java.io.IOException("Prepared close unblocked the write");
                }
                pressureDelivered.incrementAndGet();
            }
            delegate.send(frame);
            if (frame.optString("type").equals("mute"))
                events.add("mute:" + frame.optBoolean("on"));
        }

        public void sendBinary(byte[] bytes) throws Exception {
            if (failBinary.compareAndSet(true, false))
                throw new java.io.IOException("Prepared binary write failure");
            delegate.sendBinary(bytes);
            binary.incrementAndGet();
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void ping(long timeoutMs) throws Exception {
            pings.incrementAndGet();
            if (failPing.get()) throw new java.io.IOException("Prepared stale network path");
            delegate.ping(timeoutMs);
        }

        public void rebind() {
            rebinds.incrementAndGet();
            delegate.rebind();
        }

        public void closeLive() {
            events.add("close");
            closes.incrementAndGet();
            delegate.closeLive();
            if (blocked.getCount() == 0) release.countDown();
        }
    }
}
