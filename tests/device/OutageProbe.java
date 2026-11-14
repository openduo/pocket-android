// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import dev.openduo.pocket.audio.CaptureSession;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.core.RedialBackoff;
import dev.openduo.pocket.core.VoiceNotes;
import dev.openduo.pocket.net.ChannelConnection;
import dev.openduo.pocket.text.AppText;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Actual retry deadlines and native capture; all transport/lifecycle faults stay in try mode. */
final class OutageProbe {
    private final Probe probe;
    private final PocketController controller;
    private final Executor state;
    private final ChannelConnection original;
    private final Observed source;
    private int assertions;

    OutageProbe(Probe probe, PocketController controller) throws Exception {
        this.probe = probe;
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

    private void set(String name, Object value) throws Exception {
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

    private boolean online() {
        return controller.snapshot().connection == PocketController.Connection.ONLINE;
    }

    private boolean capturing() {
        return RecordingInput.isActive();
    }

    int run(Context context, Activity screen) throws Exception {
        await(() -> online() && !capturing(), 6000);
        check(
                controller.snapshot().trying && online() && !capturing(),
                "outage faults require an idle disposable room");
        String draft = controller.draft();
        int pending = controller.snapshot().outbox.size();
        boolean transcript = controller.settings.ambientTranscript();
        int heat = onState(() -> (Integer) field("heatLevel"));
        try {
            onState(
                    () -> {
                        set("channel", source);
                        invoke("reconnect");
                        return null;
                    });
            await(() -> source.listener != null && online(), 5000);
            controller.ambientToggle();
            controller.ambientMute();
            await(() -> capturing() && source.hellos.get() > 0 && source.binary.get() > 0, 7000);
            check(
                    capturing() && source.hellos.get() > 0 && source.binary.get() > 0,
                    "ordinary ambient hello/uplink use the actual native microphone");
            CaptureSession capture = onState(() -> (CaptureSession) field("capture"));
            int captureEpoch = onState(() -> (Integer) field("captureEpoch"));

            source.unreachable.set(true);
            long closedAt = SystemClock.elapsedRealtime();
            source.listener.closed("Prepared sustained transport outage");
            await(() -> !online(), 1000);
            Thread.sleep(100);
            onState(
                    () -> {
                        invoke("connect");
                        return null;
                    });
            check(
                    source.failedDials.isEmpty(),
                    "a passive connection check cannot bypass the pending retry deadline");
            int uplink = source.binary.get();
            long previous = closedAt;
            long[] delays = {1000, 2000, 4000, 8000, 16_000};
            for (int i = 0; i < delays.length; i++) {
                final int attempt = i + 1;
                await(() -> source.failedDials.size() >= attempt, delays[i] + 3500);
                check(
                        source.failedDials.size() == attempt,
                        "the scheduled retry reaches failed live attempt " + attempt);
                long at = source.failedDials.get(i);
                long delay = at - previous;
                check(
                        delay >= delays[i] - 150 && delay <= delays[i] + 2500,
                        "retry " + attempt + " honors its real deadline: " + delay + " ms");
                previous = at;
                if (i < delays.length - 1) {
                    check(
                            capturing()
                                    && onState(
                                            () ->
                                                    field("capture") == capture
                                                            && (Integer) field("captureEpoch")
                                                                    == captureEpoch)
                                    && !controller.snapshot().ambientLinkPaused
                                    && controller.snapshot().ambient
                                            == PocketController.Ambient.STARTING,
                            "short outage keeps the same native capture lease at attempt "
                                    + attempt);
                }
            }
            // The input lease is released before CaptureSession posts stopped to the state owner.
            // Observe both halves within the original deadline before checking the final state.
            long releaseUntil = SystemClock.elapsedRealtime() + 3500;
            boolean released = false;
            boolean callbackPending = false;
            while (SystemClock.elapsedRealtime() < releaseUntil) {
                boolean[] observed =
                        onState(
                                () ->
                                        new boolean[] {
                                            controller.snapshot().ambientLinkPaused,
                                            capturing(),
                                            field("capture") == null,
                                            (Boolean) field("captureStopping")
                                        });
                if (observed[0] && !observed[1] && !observed[2]) callbackPending = true;
                if (observed[0] && !observed[1] && observed[2] && !observed[3]) {
                    released = true;
                    break;
                }
                Thread.sleep(20);
            }
            long outage = previous - closedAt;
            check(
                    released
                            && outage >= 30_850
                            && outage <= 41_000
                            && controller.snapshot().ambientActive
                            && controller.snapshot().ambientLinkPaused
                            && controller.snapshot().ambient == PocketController.Ambient.OFFLINE
                            && !capturing()
                            && onState(() -> field("capture") == null),
                    "after about 31 real seconds the sustained outage releases the native mic: "
                            + outage
                            + " ms; nativeActive="
                            + capturing()
                            + ", callbackPendingObserved="
                            + callbackPending
                            + ", captureNull="
                            + onState(() -> field("capture") == null)
                            + ", captureStopping="
                            + onState(() -> field("captureStopping"))
                            + ", ambient="
                            + controller.snapshot().ambient
                            + ", paused="
                            + controller.snapshot().ambientLinkPaused);
            android.os.Bundle releaseReport = new android.os.Bundle();
            releaseReport.putString(
                    "checkpoint",
                    "outage-native-release; callbackPendingObserved=" + callbackPending);
            probe.sendStatus(0, releaseReport);
            check(
                    source.binary.get() == uplink,
                    "no captured packets enter the dead transport during retry backoff");
            retryScreen(context, screen, "ambient-offline");

            int hellos = source.hellos.get();
            // Try mode intentionally ends on the real background callback. Prepare only the
            // foreground flag to exercise production ready handling without touching real rooms.
            onState(
                    () -> {
                        set("foreground", false);
                        source.unreachable.set(false);
                        return null;
                    });
            await(this::online, 35_000);
            long cappedDelay = SystemClock.elapsedRealtime() - previous;
            check(
                    cappedDelay >= 29_850 && cappedDelay <= 34_000,
                    "the capped retry honors its actual 30-second deadline: "
                            + cappedDelay
                            + " ms");
            Thread.sleep(350);
            check(
                    online()
                            && controller.snapshot().ambientLinkPaused
                            && controller.snapshot().ambient == PocketController.Ambient.OFFLINE
                            && !capturing()
                            && source.hellos.get() == hellos,
                    "ready/meta in prepared background leave the recovered native mic paused");
            controller.onForeground(true);
            await(() -> capturing() && source.hellos.get() > hellos, 5000);
            check(
                    capturing()
                            && source.hellos.get() == hellos + 1
                            && !controller.snapshot().ambientLinkPaused
                            && onState(() -> field("capture") != capture),
                    "foreground acquires a fresh native mic and sends exactly one fresh hello");

            holdDuringPause();
            refusal(context, screen);
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().outbox.size() == pending,
                    "outage, refusal and reopening preserve the draft and create no messages");
            return assertions;
        } finally {
            source.unreachable.set(false);
            controller.releaseHold(true);
            controller.endAmbient();
            controller.onForeground(true);
            controller.settings.ambientTranscript(transcript);
            onState(
                    () -> {
                        set("heatLevel", heat);
                        set("channel", original);
                        invoke("reconnect");
                        return null;
                    });
            await(() -> online() && !capturing(), 6000);
        }
    }

    private void holdDuringPause() throws Exception {
        controller.beginHold();
        await(() -> controller.snapshot().holdLive, 3000);
        check(
                capturing() && controller.snapshot().holdLive,
                "the overlap case starts an actual native held note");
        CaptureSession capture = onState(() -> (CaptureSession) field("capture"));
        long packets = onState(() -> ((VoiceNotes) field("voiceNotes")).packets());
        // The real 31-second boundary was exercised above. Prime only this separate overlap case.
        onState(
                () -> {
                    RedialBackoff redial = (RedialBackoff) field("redial");
                    redial.reset();
                    for (int i = 0; i < 5; i++) redial.failedMs();
                    source.unreachable.set(true);
                    return null;
                });
        source.listener.closed("Prepared sustained outage during held note");
        await(() -> controller.snapshot().ambientLinkPaused, 1500);
        Thread.sleep(250);
        check(
                capturing()
                        && controller.snapshot().ambientLinkPaused
                        && controller.snapshot().holdLive
                        && onState(
                                () ->
                                        field("capture") == capture
                                                && ((VoiceNotes) field("voiceNotes")).packets()
                                                        > packets),
                "sustained room pause does not truncate the independent local held note");
        controller.releaseHold(true);
        await(() -> !capturing(), 3500);
        check(
                !capturing()
                        && controller.snapshot().ambientLinkPaused
                        && !controller.snapshot().holdRecording,
                "cancelling the held note releases capture while ambient stays link-paused");
        source.unreachable.set(false);
        long pressedAt = SystemClock.elapsedRealtime();
        controller.beginHold();
        await(
                () ->
                        online()
                                && capturing()
                                && controller.snapshot().holdLive
                                && !controller.snapshot().ambientLinkPaused,
                5000);
        check(
                online() && capturing() && !controller.snapshot().ambientLinkPaused,
                "a new local press recovers immediately during the pending 30-second retry");
        check(
                SystemClock.elapsedRealtime() - pressedAt < 3500 && controller.snapshot().holdLive,
                "explicit press resets backoff and renews the native held capture");
        controller.releaseHold(true);
        onState(() -> null);
    }

    private void refusal(Context context, Activity screen) throws Exception {
        boolean unauthorized = controller.settings.unauthorized();
        int opens = source.opens.get();
        source.listener.closed("PolicyViolation: 1008 prepared room refusal");
        await(() -> controller.snapshot().ambient == PocketController.Ambient.FAILED, 1500);
        check(
                controller.snapshot().ambient == PocketController.Ambient.FAILED
                        && controller.snapshot().ambientError.equals("频道拒绝了这个房间"),
                "1008 publishes a persistent room refusal independently of connection status");
        await(() -> source.opens.get() > opens && online(), 5000);
        source.listener.text(
                Json.object("type", "meta", "role", "master", "state", "owned").toString());
        source.listener.text(
                Json.object("type", "transcript", "row", Json.object("text", "prepared old speech"))
                        .toString());
        onState(
                () -> {
                    set("heatLevel", 1);
                    invoke("publish");
                    return null;
                });
        check(
                online()
                        && controller.snapshot().ambient == PocketController.Ambient.FAILED
                        && controller.settings.unauthorized() == unauthorized,
                "ready and seat metadata cannot clear refusal or invalidate device credentials");

        Object focus = onState(() -> field("audioFocus"));
        Field callback = focus.getClass().getDeclaredField("change");
        callback.setAccessible(true);
        IntConsumer change = (IntConsumer) callback.get(focus);
        onState(
                () -> {
                    change.accept(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                    return null;
                });
        await(() -> !capturing() && online(), 5000);
        check(
                !capturing() && controller.snapshot().ambient == PocketController.Ambient.FAILED,
                "prepared audio interruption retains refusal as the visible failure");
        onState(
                () -> {
                    change.accept(android.media.AudioManager.AUDIOFOCUS_GAIN);
                    return null;
                });
        controller.onForeground(true);
        await(() -> online() && capturing(), 5000);
        check(
                online()
                        && capturing()
                        && controller.snapshot().ambient == PocketController.Ambient.FAILED,
                "prepared focus gain and foreground do not erase the room refusal");

        View retry = retryScreen(context, screen, "ambient-refused");
        int hellos = source.hellos.get();
        probe.runOnMainSync(retry::performClick);
        await(
                () ->
                        online()
                                && capturing()
                                && source.hellos.get() > hellos
                                && controller.snapshot().ambientError.isEmpty(),
                6000);
        check(
                online()
                        && capturing()
                        && source.hellos.get() == hellos + 1
                        && controller.snapshot().ambientError.isEmpty()
                        && controller.snapshot().ambient != PocketController.Ambient.FAILED,
                "the reachable small-screen reopen action clears refusal and renews capture/hello");

        source.listener.closed("PolicyViolation: 1008 prepared exit while refused");
        await(() -> controller.snapshot().ambient == PocketController.Ambient.FAILED, 1500);
        probe.waitForIdleSync();
        Thread.sleep(150);
        View[] off = {null};
        Rect bounds = new Rect(), viewport = new Rect();
        probe.runOnMainSync(
                () -> {
                    View root = screen.getWindow().getDecorView();
                    root.getWindowVisibleDisplayFrame(viewport);
                    off[0] = named(root, AppText.get(context).t("关闭环境模式"));
                    if (off[0] != null) off[0].getGlobalVisibleRect(bounds);
                });
        check(
                off[0] != null && bounds.height() >= 48 && viewport.contains(bounds),
                "the refused compact screen keeps an independent reachable ambient-off control");
        probe.runOnMainSync(off[0]::performClick);
        await(() -> !controller.snapshot().ambientActive && !capturing() && online(), 6000);
        check(
                !controller.snapshot().ambientActive
                        && controller.snapshot().ambientError.isEmpty()
                        && !capturing()
                        && online(),
                "the separate off control exits refusal and releases the actual microphone");
    }

    private View retryScreen(Context context, Activity screen, String name) throws Exception {
        controller.settings.ambientTranscript(false);
        onState(
                () -> {
                    invoke("publish");
                    return null;
                });
        probe.runOnMainSync(
                () -> ((dev.openduo.pocket.app.MainActivity) screen).navigate("ambient"));
        probe.waitForIdleSync();
        Thread.sleep(200);
        View[] action = {null};
        Rect bounds = new Rect(), viewport = new Rect();
        probe.runOnMainSync(
                () -> {
                    View root = screen.getWindow().getDecorView();
                    root.getWindowVisibleDisplayFrame(viewport);
                    action[0] = named(root, AppText.get(context).t("重新打开"));
                    if (action[0] != null) action[0].getGlobalVisibleRect(bounds);
                });
        check(
                action[0] != null
                        && action[0].isEnabled()
                        && bounds.height() >= 48
                        && viewport.contains(bounds),
                name + " reopen action is fully reachable in the actual compact viewport");
        if (!controller.snapshot().ambientError.isEmpty()) {
            int[] visible = {0};
            probe.runOnMainSync(
                    () ->
                            visible[0] =
                                    visibleTextCount(
                                            screen.getWindow().getDecorView(),
                                            AppText.get(context)
                                                    .t(controller.snapshot().ambientError)));
            check(
                    visible[0] == 2,
                    "both status and detail retain refusal despite old transcript and heat");
        }
        probe.screenshot(context, name);
        return action[0];
    }

    private View named(View view, String label) {
        if (!view.isShown()) return null;
        if (label.contentEquals(
                        view.getContentDescription() == null ? "" : view.getContentDescription())
                || view instanceof TextView && ((TextView) view).getText().toString().equals(label))
            return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = named(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int visibleTextCount(View view, String label) {
        if (!view.isShown()) return 0;
        int count =
                view instanceof TextView
                                && ((TextView) view).getText().toString().equals(label)
                                && view.getGlobalVisibleRect(new Rect())
                        ? 1
                        : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                count += visibleTextCount(group.getChildAt(i), label);
        }
        return count;
    }

    private static final class Observed implements ChannelConnection {
        final ChannelConnection delegate;
        final AtomicBoolean unreachable = new AtomicBoolean();
        final AtomicInteger opens = new AtomicInteger(), hellos = new AtomicInteger();
        final AtomicInteger binary = new AtomicInteger();
        final List<Long> failedDials = new CopyOnWriteArrayList<>();
        volatile LiveListener listener;

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
            if (unreachable.get()) {
                failedDials.add(SystemClock.elapsedRealtime());
                throw new java.io.IOException("Prepared unreachable live endpoint");
            }
            opens.incrementAndGet();
            this.listener = listener;
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
            delegate.send(frame);
            if (frame.optString("type").equals("hello")) hellos.incrementAndGet();
        }

        public void sendBinary(byte[] bytes) throws Exception {
            delegate.sendBinary(bytes);
            binary.incrementAndGet();
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void closeLive() {
            delegate.closeLive();
        }
    }
}
