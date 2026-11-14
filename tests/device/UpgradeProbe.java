// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelConnection;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual controller/storage/compact UI with incompatible replies confined to the Try room. */
final class UpgradeProbe {
    private final Probe probe;
    private final PocketController controller;
    private final Executor state;
    private final List<String> ids = new ArrayList<>();
    private int assertions;

    UpgradeProbe(Probe probe, PocketController controller) throws Exception {
        this.probe = probe;
        this.controller = controller;
        state = (Executor) field("state");
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void channel(ChannelConnection channel) throws Exception {
        Field field = PocketController.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(controller, channel);
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

    private String upgrade() {
        return controller.snapshot().upgrade.name();
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private String text(RoomRepository repository, String body) throws Exception {
        controller.setVoiceInput(false);
        controller.draft(body);
        controller.sendDraft();
        onState(() -> null);
        for (Outbox.Item item : onState(repository::pending))
            if (item.text.equals(body)) {
                ids.add(item.id);
                return item.id;
            }
        throw new AssertionError("Prepared text did not enter the ordinary outbox");
    }

    private void ready(Source source, int api) throws Exception {
        source.api = api;
        int before = source.opens;
        onState(
                () -> {
                    invoke("checkCompatibility");
                    return null;
                });
        await(
                () ->
                        source.opens > before
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                5000);
        check(
                source.opens == before + 1
                        && controller.snapshot().connection == PocketController.Connection.ONLINE,
                "explicit compatibility recheck opens one fresh live generation");
    }

    int run(Context context, Activity screen) throws Exception {
        check(
                controller.snapshot().trying && controller.snapshot().outbox.isEmpty(),
                "upgrade faults require an idle disposable room");
        RoomRepository repository = onState(() -> (RoomRepository) field("repo"));
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        Source source = new Source(original);
        String draft = controller.draft();
        boolean voiceInput = controller.settings.voiceInput();
        try {
            onState(
                    () -> {
                        channel(source);
                        invoke("reconnect");
                        return null;
                    });
            await(
                    () ->
                            source.opens == 1
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    5000);
            source.status = 404;
            String missing = text(repository, "QA missing messages route");
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.id.equals(missing)
                                                            && item.state == Message.FAILED),
                    3000);
            probe.screenshot(context, "upgrade-channel");
            check(
                    upgrade().equals("CHANNEL_UPGRADE") && source.bodies.size() == 1,
                    "messages-route 404 must identify a channel upgrade rather than a generic send"
                            + " failure");
            byte[] originalBody = source.bodies.get(0).clone();
            String originalHeaders = source.headers.get(0).toString();
            controller.clearNotice();
            String waiting = text(repository, "QA queued during channel upgrade");
            onState(
                    () -> {
                        invoke("pump");
                        return null;
                    });
            check(
                    upgrade().equals("CHANNEL_UPGRADE")
                            && source.bodies.size() == 1
                            && onState(() -> repository.findPending(waiting).state)
                                    == Message.QUEUED,
                    "clearing a transient notice cannot bypass compatibility or send the queued"
                            + " successor");

            source.status = 202;
            ChannelConnection.LiveListener retired = source.listeners.get(0);
            ready(source, 2);
            await(() -> source.bodies.size() == 2, 3000);
            check(
                    upgrade().equals("NONE")
                            && source.bodies.size() == 2
                            && onState(() -> repository.findPending(missing).state)
                                    == Message.FAILED,
                    "a compatible ready clears only the channel notice and sends the queued"
                            + " successor");
            retired.text(Json.object("type", "ready", "seq", 0, "api", 1).toString());
            onState(() -> null);
            check(
                    upgrade().equals("NONE"),
                    "a retired ready cannot restore an obsolete upgrade gate");
            controller.retry(missing);
            await(() -> source.bodies.size() == 3, 3000);
            check(
                    source.bodies.size() == 3
                            && Arrays.equals(source.bodies.get(2), originalBody)
                            && source.headers.get(2).toString().equals(originalHeaders),
                    "manual retry after a channel upgrade retains the original text"
                            + " key/body/surface");
            for (String id : new ArrayList<>(ids)) controller.discard(id);
            onState(() -> null);

            ready(source, 1);
            controller.clearNotice();
            check(
                    upgrade().equals("CHANNEL_UPGRADE"),
                    "old ready API is typed and survives clear-notice");
            ready(source, 3);
            check(upgrade().equals("NONE"), "a newer compatible API also clears a channel notice");
            source.status = 410;
            source.reason = "gone";
            String gone = text(repository, "QA unrelated gone response");
            await(
                    () ->
                            controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.id.equals(gone)
                                                            && item.state == Message.FAILED),
                    3000);
            check(upgrade().equals("NONE"), "an unrelated 410 is not an app-update requirement");
            controller.discard(gone);
            onState(() -> null);

            source.reason = "update_required";
            source.hold = new CountDownLatch(1);
            String discarded = text(repository, "QA discarded late update response");
            check(
                    source.held.await(2, TimeUnit.SECONDS),
                    "a messages response is held before cancellation");
            controller.discard(discarded);
            onState(() -> null);
            source.hold.countDown();
            await(() -> source.returned.getCount() == 0, 3000);
            ThreadPoolExecutor sender = (ThreadPoolExecutor) field("sendNetwork");
            await(() -> sender.getActiveCount() == 0, 3000);
            onState(() -> null);
            check(
                    upgrade().equals("NONE")
                            && onState(() -> repository.findPending(discarded)) == null,
                    "a discarded send's late update_required cannot impose an obsolete app update");
            source.hold = null;

            File directory = new File(context.getCacheDir(), "try-voice");
            directory.mkdirs();
            String voice = UUID.randomUUID().toString();
            ids.add(voice);
            File file = new File(directory, voice + ".packets");
            try (OpusEncoder encoder = new OpusEncoder();
                    FileOutputStream output = new FileOutputStream(file)) {
                for (int i = 0; i < 25; i++) {
                    byte[] packet = encoder.encode(new short[320]);
                    output.write(packet.length & 255);
                    output.write(packet.length >>> 8);
                    output.write(packet);
                }
            }
            byte[] voiceBytes = Files.readAllBytes(file.toPath());
            source.reason = "update_required";
            onState(
                    () -> {
                        Outbox.Item item =
                                new Outbox.Item(
                                        voice,
                                        "",
                                        true,
                                        file.getPath(),
                                        "phone",
                                        500,
                                        Collections.emptyList(),
                                        Message.QUEUED,
                                        "",
                                        "",
                                        System.currentTimeMillis(),
                                        0);
                        repository.enqueue(
                                Message.mine(
                                        voice,
                                        "",
                                        "phone",
                                        500,
                                        Message.QUEUED,
                                        Collections.emptyList()),
                                item);
                        invoke("reload");
                        invoke("publish");
                        invoke("pump");
                        return null;
                    });
            await(() -> upgrade().equals("APP_UPDATE"), 3000);
            check(
                    upgrade().equals("APP_UPDATE")
                            && file.isFile()
                            && Arrays.equals(
                                    voiceBytes, source.bodies.get(source.bodies.size() - 1))
                            && onState(() -> repository.findPending(voice).state) == Message.FAILED,
                    "voice 410 update_required retains retryable audio and identifies the app to"
                            + " update");
            controller.draft("QA upgrade sheet retains composer");
            controller.clearNotice();
            ready(source, 2);
            check(
                    upgrade().equals("APP_UPDATE"),
                    "a compatible ready cannot dismiss an app-update requirement");
            ready(source, 1);
            check(upgrade().equals("APP_UPDATE"), "app update outranks a later old-channel ready");
            controls(screen, source);
            check(
                    controller.draft().equals("QA upgrade sheet retains composer") && file.isFile(),
                    "opening/rechecking the compact upgrade explanation preserves draft and"
                            + " recorded bytes");
        } finally {
            // The disposable namespace is cleared by Probe; no compatibility state may escape it.
            if (source.hold != null) source.hold.countDown();
            onState(
                    () -> {
                        channel(original);
                        return null;
                    });
            for (String id : ids) controller.discard(id);
            controller.draft(draft);
            controller.setVoiceInput(voiceInput);
            onState(() -> null);
        }
        return assertions;
    }

    private void controls(Activity screen, Source source) throws Exception {
        Ui ui = ((PageHost) screen).ui();
        boolean[] visible = {false};
        View[] banner = {null};
        await(
                () -> {
                    probe.runOnMainSync(
                            () -> {
                                banner[0] =
                                        screen.getWindow()
                                                .getDecorView()
                                                .findViewWithTag("upgrade-notice");
                                if (banner[0] != null) {
                                    Rect bounds = new Rect(), viewport = new Rect();
                                    screen.getWindow()
                                            .getDecorView()
                                            .getWindowVisibleDisplayFrame(viewport);
                                    AccessibilityNodeInfo node =
                                            banner[0].createAccessibilityNodeInfo();
                                    try {
                                        visible[0] =
                                                banner[0].isShown()
                                                        && banner[0].getGlobalVisibleRect(bounds)
                                                        && bounds.height() >= ui.dp(48)
                                                        && viewport.contains(bounds)
                                                        && "android.widget.Button"
                                                                .contentEquals(node.getClassName());
                                    } finally {
                                        node.recycle();
                                    }
                                }
                            });
                    return visible[0];
                },
                3000);
        check(visible[0], "upgrade explanation has a fully visible 48 dp native button target");
        probe.runOnMainSync(() -> banner[0].performClick());
        probe.waitForIdleSync();
        probe.screenshot(screen, "upgrade-app-sheet");
        int before = source.opens;
        AccessibilityNodeInfo root = probe.getUiAutomation().getRootInActiveWindow();
        try {
            List<AccessibilityNodeInfo> matches =
                    root.findAccessibilityNodeInfosByText(ui.t("重新检查"));
            boolean clicked = false;
            for (AccessibilityNodeInfo node : matches) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (node.isClickable() && bounds.height() >= ui.dp(48))
                    clicked |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
            }
            check(clicked, "the actual upgrade sheet exposes a 48 dp recheck action");
        } finally {
            root.recycle();
        }
        await(() -> source.opens > before, 5000);
        check(
                source.opens == before + 1 && upgrade().equals("APP_UPDATE"),
                "native sheet recheck opens one connection without hiding a required app update");
    }

    private static final class Source implements ChannelConnection {
        final ChannelConnection delegate;
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        final List<JSONObject> headers = new CopyOnWriteArrayList<>();
        final List<LiveListener> listeners = new CopyOnWriteArrayList<>();
        volatile int status = 202, api = 2, opens;
        volatile String reason = "";
        volatile CountDownLatch hold;
        final CountDownLatch held = new CountDownLatch(1), returned = new CountDownLatch(1);

        Source(ChannelConnection delegate) {
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
            if (method.equals("POST") && path.startsWith("/api/messages?")) {
                this.headers.add(new JSONObject(headers.toString()));
                bodies.add(body.clone());
                int responseStatus = status;
                String responseReason = reason;
                CountDownLatch release = hold;
                if (release != null) {
                    held.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IOException("Prepared response hold expired");
                    returned.countDown();
                }
                return new Response(
                        responseStatus,
                        Json.object("error", responseReason).toString().getBytes("UTF-8"));
            }
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
            opens++;
            listeners.add(listener);
            delegate.live(
                    endpoint,
                    token,
                    path,
                    headers,
                    new LiveListener() {
                        public void text(String text) {
                            try {
                                JSONObject frame = new JSONObject(text);
                                if (frame.optString("type").equals("ready")) frame.put("api", api);
                                listener.text(frame.toString());
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
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
