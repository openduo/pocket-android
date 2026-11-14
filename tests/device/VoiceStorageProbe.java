// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelConnection;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.HoldToTalkView;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Native held capture and SQLite admission faults, entirely within the disposable room. */
final class VoiceStorageProbe {
    private final PocketController controller;
    private final Probe probe;
    private final Executor state;
    private int assertions;

    VoiceStorageProbe(Probe probe, PocketController controller) throws Exception {
        this.probe = probe;
        this.controller = controller;
        state = (Executor) field("state");
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(5, TimeUnit.SECONDS);
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

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    int run(Context context, Activity screen) throws Exception {
        check(
                controller.snapshot().trying && !RecordingInput.isActive(),
                "voice storage faults require an idle disposable room");
        RoomRepository repository = onState(() -> (RoomRepository) field("repo"));
        ChannelConnection originalChannel = onState(() -> (ChannelConnection) field("channel"));
        Source source = new Source(originalChannel);
        onState(
                () -> {
                    field("channel", source);
                    return null;
                });
        File[] original = {null};
        try {
            controller.beginHold();
            await(
                    () ->
                            controller.snapshot().holdLive
                                    && controller.snapshot().holdDurationMs >= 500,
                    5000);
            check(
                    controller.snapshot().holdLive && RecordingInput.isActive(),
                    "the ordinary hold path captures real native Opus before admission fails");
            onState(
                    () -> {
                        original[0] = (File) field("heldFile");
                        repository
                                .getWritableDatabase()
                                .execSQL(
                                        "CREATE TEMP TRIGGER qa_voice_admission BEFORE INSERT ON"
                                            + " pending BEGIN SELECT RAISE(ABORT, 'Prepared voice"
                                            + " admission failure'); END");
                        return null;
                    });
            controller.releaseHold(false);
            onState(() -> null);
            await(() -> !RecordingInput.isActive(), 3000);
            check(
                    !RecordingInput.isActive()
                            && !controller.snapshot().holdRecording
                            && controller.snapshot().outbox.stream()
                                    .anyMatch(
                                            item ->
                                                    item.voice
                                                            && item.state == Message.FAILED
                                                            && item.voiceFile.equals(
                                                                    original[0].getPath()))
                            && original[0].isFile()
                            && original[0].length() > 0,
                    "failed SQLite admission releases capture and retains a visible retryable note"
                            + " with its original bytes");
            Outbox.Item retained =
                    controller.snapshot().outbox.stream()
                            .filter(item -> item.voiceFile.equals(original[0].getPath()))
                            .findFirst()
                            .get();
            byte[] bytes = Files.readAllBytes(original[0].toPath());
            check(
                    onState(
                                    () ->
                                            repository.findPending(retained.id) == null
                                                    && repository.row(retained.id) == null)
                            && retained.id.equals(original[0].getName().replace(".packets", ""))
                            && source.calls.get() == 0,
                    "rolled-back admission keeps one stable identity outside SQLite and starts no"
                            + " upload");
            controls(screen, retained);
            probe.screenshot(context, "voice-storage-unsaved");
            controller.retry(retained.id);
            onState(() -> null);
            check(
                    source.calls.get() == 0
                            && controller.snapshot().outbox.size() == 1
                            && controller.snapshot().outbox.get(0).id.equals(retained.id),
                    "retry during continued storage failure neither duplicates nor uploads the"
                            + " note");
            onState(
                    () -> {
                        repository.getWritableDatabase().execSQL("DROP TRIGGER qa_voice_admission");
                        return null;
                    });
            controller.retry(retained.id);
            await(
                    () ->
                            source.calls.get() > 0
                                    && controller.snapshot().outbox.stream()
                                            .anyMatch(
                                                    item ->
                                                            item.id.equals(retained.id)
                                                                    && item.state
                                                                            == Outbox.ACCEPTED),
                    3000);
            check(
                    source.calls.get() == 1
                            && retained.id.equals(source.key)
                            && Arrays.equals(bytes, source.body)
                            && controller.snapshot().outbox.size() == 1
                            && onState(() -> repository.voiceReceipts().isEmpty()),
                    "after storage recovers explicit retry uploads once with the original key and"
                            + " exact captured bytes");
            controller.discard(retained.id);
            onState(() -> null);
            check(
                    !original[0].exists() && controller.snapshot().outbox.isEmpty(),
                    "discard after successful handoff cleans its recording without revival");
            receipts(repository, bytes);
        } finally {
            onState(
                    () -> {
                        repository
                                .getWritableDatabase()
                                .execSQL("DROP TRIGGER IF EXISTS qa_voice_admission");
                        return null;
                    });
            controller.releaseHold(true);
            for (Outbox.Item item : controller.snapshot().outbox)
                if (original[0] != null && item.voiceFile.equals(original[0].getPath()))
                    controller.discard(item.id);
            onState(() -> null);
            if (original[0] != null) original[0].delete();
            await(() -> !RecordingInput.isActive(), 3000);
            onState(
                    () -> {
                        field("channel", originalChannel);
                        return null;
                    });
        }
        return assertions;
    }

    private TextView label(View root, String text, boolean clickable) {
        if (root instanceof TextView
                && root.isShown()
                && text.contentEquals(((TextView) root).getText())
                && (!clickable || root.isClickable())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = label(group.getChildAt(i), text, clickable);
                if (found != null) return found;
            }
        }
        return null;
    }

    private double luminance(int color) {
        double result = 0;
        double[] weights = {.2126, .7152, .0722};
        for (int i = 0; i < 3; i++) {
            double channel = (color >> (16 - i * 8) & 255) / 255.0;
            result +=
                    weights[i]
                            * (channel <= .04045
                                    ? channel / 12.92
                                    : Math.pow((channel + .055) / 1.055, 2.4));
        }
        return result;
    }

    private double contrast(int a, int b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private void controls(Activity screen, Outbox.Item item) throws Exception {
        Ui ui = ((PageHost) screen).ui();
        boolean[] observed = new boolean[3];
        String[] diagnostic = {"No retry target"};
        long until = SystemClock.elapsedRealtime() + 2000;
        do {
            probe.runOnMainSync(
                    () -> {
                        View root = screen.getWindow().getDecorView();
                        View message = root.findViewWithTag("row:" + item.id);
                        TextView voice =
                                label(
                                        message,
                                        ui.t("语音")
                                                + " · "
                                                + HoldToTalkView.duration(item.durationMs),
                                        false);
                        TextView placeholder = label(message, ui.t("语音未送达"), false);
                        TextView retry = label(message, ui.explain(VoiceInbox.UNSAVED), true);
                        observed[0] =
                                voice != null
                                        && placeholder != null
                                        && contrast(voice.getCurrentTextColor(), ui.palette.mine)
                                                >= 4.5
                                        && contrast(
                                                        placeholder.getCurrentTextColor(),
                                                        ui.palette.mine)
                                                >= 4.5;
                        if (retry != null) {
                            Rect bounds = new Rect(), viewport = new Rect();
                            root.getWindowVisibleDisplayFrame(viewport);
                            observed[1] =
                                    retry.getGlobalVisibleRect(bounds)
                                            && bounds.height() >= ui.dp(48)
                                            && viewport.contains(bounds);
                            AccessibilityNodeInfo node = retry.createAccessibilityNodeInfo();
                            try {
                                observed[2] =
                                        "android.widget.Button".contentEquals(node.getClassName())
                                                && node.isClickable();
                                diagnostic[0] =
                                        "bounds="
                                                + bounds
                                                + ", viewport="
                                                + viewport
                                                + ", min="
                                                + ui.dp(48)
                                                + ", class="
                                                + node.getClassName()
                                                + ", clickable="
                                                + node.isClickable();
                            } finally {
                                node.recycle();
                            }
                        }
                    });
            if (observed[0] && observed[1] && observed[2]) break;
            Thread.sleep(20);
        } while (SystemClock.elapsedRealtime() < until);
        check(
                observed[0],
                "actual native voice type/duration and unsent text remain readable on the filled"
                        + " outgoing bubble");
        probe.screenshot(screen, "voice-storage-native-target");
        check(
                observed[1] && observed[2],
                "the failed-note action has a fully visible 48 dp native button target on the"
                        + " compact screen; "
                        + diagnostic[0]);
    }

    private void receipts(RoomRepository repository, byte[] bytes) throws Exception {
        int initialRows = onState(repository::countRows);
        VoiceInbox inbox = (VoiceInbox) onState(() -> field("tryVoiceInbox"));
        VoiceInbox.Note recording = inbox.begin(onState(repository::roomIdentity));
        Files.write(recording.file.toPath(), bytes);
        VoiceInbox.Note note = inbox.seal(recording, bytes.length, true);
        inbox.remember(note);
        try {
            onState(
                    () -> {
                        repository.enqueueVoice(note);
                        repository.transition(
                                repository
                                        .findPending(note.id)
                                        .state(Message.SENDING, "")
                                        .surface("phone_awake", false, ""));
                        repository.enqueueVoice(note);
                        return null;
                    });
            check(
                    onState(
                            () ->
                                    repository.findPending(note.id).state == Message.SENDING
                                            && repository
                                                    .findPending(note.id)
                                                    .display
                                                    .equals("phone_awake")
                                            && repository.countRows() == initialRows + 1
                                            && repository.voiceReceipts().contains(note.id)),
                    "replaying an unacknowledged handoff cannot replace a newer outbox state or"
                            + " original surface");
            onState(
                    () -> {
                        repository.discard(note.id);
                        return null;
                    });
            check(
                    !note.file.exists() && inbox.awaiting(note.id),
                    "normal SQLite discard may remove admitted bytes before the handoff manifest is"
                            + " acknowledged");
            onState(
                    () -> {
                        invoke("recoverVoiceNotes");
                        invoke("reload");
                        invoke("publish");
                        return null;
                    });
            check(
                    !inbox.awaiting(note.id)
                            && onState(repository::voiceReceipts).isEmpty()
                            && controller.snapshot().outbox.isEmpty()
                            && onState(repository::countRows) == initialRows,
                    "a committed receipt prevents resurrection after discard and is pruned after"
                            + " acknowledgment");
        } finally {
            onState(
                    () -> {
                        repository.discard(note.id);
                        repository.acknowledgeVoice(note.id);
                        return null;
                    });
            inbox.discard(note);
        }
    }

    private static final class Source implements ChannelConnection {
        final ChannelConnection delegate;
        final AtomicInteger calls = new AtomicInteger();
        volatile String key;
        volatile byte[] body;

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
                this.key = headers.optString("Idempotency-Key");
                this.body = body.clone();
                calls.incrementAndGet();
                return new Response(202, new byte[0]);
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
