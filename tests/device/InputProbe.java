// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.qa;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.app.input.InputBindings;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.Message;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.input.InputAction;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.components.HoldToTalkView;
import dev.openduo.pocket.ui.components.Sheets;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** One bounded E2 input check. Injected Android events, real capture, Try-only outgoing notes. */
final class InputProbe {
    private final Probe probe;
    private int assertions;
    private Activity screen;
    private PocketController controller;

    InputProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void await(BooleanSupplier condition, int timeout, String label) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
        check(condition.getAsBoolean(), label);
    }

    private void barrier() throws Exception {
        java.lang.reflect.Field field = PocketController.class.getDeclaredField("state");
        field.setAccessible(true);
        FutureTask<Void> task = new FutureTask<>(() -> null);
        ((Executor) field.get(controller)).execute(task);
        task.get(3, TimeUnit.SECONDS);
    }

    private <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root) && root.isShown()) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean contains(View root, String text) {
        if (root == null) return false;
        if (text.contentEquals(
                root.getContentDescription() == null ? "" : root.getContentDescription()))
            return true;
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
            return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++)
                if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private boolean focused(String text) throws Exception {
        boolean[] result = {false};
        probe.runOnMainSync(() -> result[0] = contains(screen.getCurrentFocus(), text));
        return result[0];
    }

    private Dialog inputDialog() throws Exception {
        java.lang.reflect.Field page = MainActivity.class.getDeclaredField("inputSettings");
        page.setAccessible(true);
        Object settings = page.get(screen);
        java.lang.reflect.Field field = settings.getClass().getDeclaredField("dialog");
        field.setAccessible(true);
        return (Dialog) field.get(settings);
    }

    private boolean dialogFocused(String text) throws Exception {
        Dialog dialog = inputDialog();
        boolean[] result = {false};
        probe.runOnMainSync(() -> result[0] = contains(dialog.getCurrentFocus(), text));
        return result[0];
    }

    private String page() throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField("page");
        field.setAccessible(true);
        String[] result = {null};
        probe.runOnMainSync(() -> result[0] = (String) read(field, screen));
        return result[0];
    }

    private static Object read(java.lang.reflect.Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private void key(int code) {
        probe.sendKeyDownUpSync(code);
        probe.waitForIdleSync();
    }

    String run(Context context) throws Exception {
        screen =
                probe.startActivitySync(
                        new Intent(context, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 5000, "state is available");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.cameraOn
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.readingId.isEmpty(),
                "input acceptance starts with idle real work");
        String room = controller.settings.room(), host = controller.settings.host();
        boolean voiceInput = controller.settings.voiceInput();
        InputBindings bindings = new InputBindings(context);
        KeyEvent rec = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1);
        String previous =
                context.getSharedPreferences("foreground-input-bindings", Context.MODE_PRIVATE)
                        .getString(InputBindings.id(rec), null);
        KeyEvent learned = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F3);
        String previousLearned =
                context.getSharedPreferences("foreground-input-bindings", Context.MODE_PRIVATE)
                        .getString(InputBindings.id(learned), null);
        Dialog[] sheet = {null};
        try {
            check(
                    bindings.resolve(rec) == InputAction.TOGGLE_VOICE
                            && bindings.resolve(
                                            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F2))
                                    == InputAction.CANCEL_OR_STOP,
                    "E2 adapter supplies REC and MARK defaults");
            controller.startTrying();
            controller.setVoiceInput(true);
            await(
                    () ->
                            controller.snapshot().trying
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    5000,
                    "outgoing acceptance is isolated in Try");
            controller.ambientMute();
            barrier();
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("settings"));
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            PageHost ui = (PageHost) screen;
            for (int i = 0; i < 25 && !focused(ui.ui().t("按键与遥控器")); i++)
                key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(focused(ui.ui().t("按键与遥控器")), "D-pad reaches key settings without touch");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            check(page().equals("input"), "OK opens input settings");
            for (int i = 0; i < 8 && !focused(ui.ui().t("添加或修改按键")); i++)
                key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(focused(ui.ui().t("添加或修改按键")), "D-pad reaches key learning");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            key(KeyEvent.KEYCODE_F3);
            for (int i = 0; i < 15 && !dialogFocused(ui.ui().t("打开设置")); i++)
                key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(dialogFocused(ui.ui().t("打开设置")), "D-pad selects the learned key action");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            check(
                    new InputBindings(context).resolve(learned) == InputAction.SETTINGS,
                    "learning persists the received key and selected action");
            key(KeyEvent.KEYCODE_BACK);
            check(page().equals("conversation"), "Back returns to conversation");
            key(KeyEvent.KEYCODE_F3);
            check(page().equals("settings"), "learned key invokes the shared navigation action");
            key(KeyEvent.KEYCODE_BACK);

            long down = SystemClock.uptimeMillis();
            probe.sendKeySync(
                    new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 0));
            probe.sendKeySync(
                    new KeyEvent(
                            down,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_F1,
                            1));
            barrier();
            check(
                    !controller.snapshot().holdRecording,
                    "REC repeat does not toggle before release");
            probe.sendKeySync(
                    new KeyEvent(
                            down,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_F1,
                            0));
            await(
                    () ->
                            controller.snapshot().holdLive
                                    && controller.snapshot().holdDurationMs >= 400,
                    5000,
                    "REC starts the shared native capture");
            String owner = controller.snapshot().holdOwner;
            probe.runOnMainSync(
                    () -> find(screen.getWindow().getDecorView(), HoldToTalkView.class).cancel());
            barrier();
            check(
                    controller.snapshot().holdRecording
                            && controller.snapshot().holdOwner.equals(owner),
                    "touch cleanup cannot end a hardware-owned recording");
            key(KeyEvent.KEYCODE_F1);
            await(
                    () ->
                            !controller.snapshot().holdRecording
                                    && !RecordingInput.isActive()
                                    && controller.snapshot().outbox.isEmpty(),
                    5000,
                    "second REC sends once and releases capture");
            long sent =
                    controller.snapshot().rows.stream()
                            .filter(m -> m.kind == Message.MINE && !m.voiceSource.isEmpty())
                            .count();
            check(sent == 1, "one recorded note is admitted to Try");
            controller.sendHold();
            controller.sendHold();
            barrier();
            check(
                    !controller.snapshot().holdRecording,
                    "repeated Send actions never start another recording");
            key(KeyEvent.KEYCODE_F1);
            await(() -> controller.snapshot().holdLive, 5000, "REC starts a second note");
            probe.runOnMainSync(
                    () -> {
                        HoldToTalkView control =
                                find(screen.getWindow().getDecorView(), HoldToTalkView.class);
                        long now = SystemClock.uptimeMillis();
                        for (android.view.MotionEvent event :
                                new android.view.MotionEvent[] {
                                    android.view.MotionEvent.obtain(
                                            now,
                                            now,
                                            android.view.MotionEvent.ACTION_DOWN,
                                            10,
                                            10,
                                            0),
                                    android.view.MotionEvent.obtain(
                                            now,
                                            now + 1,
                                            android.view.MotionEvent.ACTION_MOVE,
                                            -10,
                                            -10,
                                            0),
                                    android.view.MotionEvent.obtain(
                                            now,
                                            now + 2,
                                            android.view.MotionEvent.ACTION_UP,
                                            -10,
                                            -10,
                                            0)
                                }) {
                            control.onTouchEvent(event);
                            event.recycle();
                        }
                    });
            barrier();
            check(
                    controller.snapshot().holdRecording,
                    "a cancelled touch Send gesture preserves the hardware recording");
            key(KeyEvent.KEYCODE_F2);
            await(
                    () -> !controller.snapshot().holdRecording && !RecordingInput.isActive(),
                    5000,
                    "MARK cancels and releases capture");
            check(
                    controller.snapshot().rows.stream()
                                    .filter(m -> m.kind == Message.MINE && !m.voiceSource.isEmpty())
                                    .count()
                            == sent,
                    "MARK cancellation creates no outgoing note");

            bindings.save(rec, InputAction.HOLD_TO_TALK);
            check(
                    new InputBindings(context).resolve(rec) == InputAction.HOLD_TO_TALK,
                    "binding overrides survive a new preferences owner");
            probe.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1));
            await(
                    () -> controller.snapshot().holdRecording,
                    3000,
                    "learned hold binding starts on key-down");
            probe.runOnMainSync(
                    () ->
                            sheet[0] =
                                    Sheets.actions(
                                            ui.ui(),
                                            ui.ui().t("按键与遥控器"),
                                            new Sheets.Action(ui.ui().t("取消"), () -> {})));
            await(
                    () -> !controller.snapshot().holdRecording && !RecordingInput.isActive(),
                    5000,
                    "another window cancels the held microphone instead of sending");
            probe.runOnMainSync(() -> sheet[0].dismiss());
            probe.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1));
            barrier();
            check(
                    controller.snapshot().rows.stream()
                                    .filter(m -> m.kind == Message.MINE && !m.voiceSource.isEmpty())
                                    .count()
                            == sent,
                    "a canceled key-up cannot send after window focus returns");
        } finally {
            probe.runOnMainSync(
                    () -> {
                        if (sheet[0] != null) sheet[0].dismiss();
                    });
            controller.cancelOrStop();
            barrier();
            android.content.SharedPreferences.Editor edit =
                    context.getSharedPreferences("foreground-input-bindings", Context.MODE_PRIVATE)
                            .edit();
            if (previous == null) edit.remove(InputBindings.id(rec));
            else edit.putString(InputBindings.id(rec), previous);
            if (previousLearned == null) edit.remove(InputBindings.id(learned));
            else edit.putString(InputBindings.id(learned), previousLearned);
            edit.commit();
            controller.stopTrying();
            controller.setVoiceInput(voiceInput);
            barrier();
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
        await(
                () ->
                        !controller.snapshot().trying
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                20_000,
                "existing real pairing reconnects after Try");
        check(
                controller.settings.room().equals(room) && controller.settings.host().equals(host),
                "room pairing remains unchanged");
        return "PASS "
                + assertions
                + " bounded E2 input assertions; injected events; Try-only sends; original pairing"
                + " Online";
    }
}
