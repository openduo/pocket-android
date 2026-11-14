// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.audio.input.RecordingInput;
import top.kuaner.pockete2.core.Attachment;
import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.HoldToTalkView;
import top.kuaner.pockete2.ui.components.Sheets;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Actual keyboard/accessibility actions and compact geometry without changing system accessibility.
 */
final class AccessibilityProbe {
    private final Probe probe;
    private PocketController controller;
    private Activity screen;
    private Ui ui;
    private int assertions;

    AccessibilityProbe(Probe probe) {
        this.probe = probe;
    }

    private void onMain(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        probe.runOnMainSync(
                () -> {
                    try {
                        work.run();
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                });
        Throwable problem = failure.get();
        if (problem instanceof Error) throw (Error) problem;
        if (problem instanceof RuntimeException) throw (RuntimeException) problem;
        if (problem != null) throw new AssertionError(problem);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    private View described(View root, String value) {
        if (root.isShown()
                && root.getContentDescription() != null
                && value.contentEquals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private HoldToTalkView hold(View root) {
        if (root instanceof HoldToTalkView && root.isShown()) return (HoldToTalkView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                HoldToTalkView found = hold(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private HoldToTalkView hold() {
        AtomicReference<HoldToTalkView> value = new AtomicReference<>();
        onMain(() -> value.set(hold(screen.getWindow().getDecorView())));
        return value.get();
    }

    private void geometry(View root, String description) {
        View view = described(root, ui.t(description));
        Rect bounds = new Rect(), viewport = new Rect();
        root.getWindowVisibleDisplayFrame(viewport);
        boolean visible = view != null && view.getGlobalVisibleRect(bounds);
        check(
                visible
                        && bounds.width() >= ui.dp(48)
                        && bounds.height() >= ui.dp(48)
                        && viewport.contains(bounds),
                "compact action has a full visible 48 dp target: " + description);
    }

    private boolean action(View view, int id) {
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        try {
            return node.getActionList().stream().anyMatch(item -> item.getId() == id);
        } finally {
            node.recycle();
        }
    }

    int run(Context context, Activity screen, PocketController controller, boolean capture)
            throws Exception {
        this.controller = controller;
        this.screen = screen;
        ui = ((PageHost) screen).ui();
        check(
                controller.snapshot().trying && controller.snapshot().outbox.isEmpty(),
                "accessibility acceptance stays in an idle disposable room");
        boolean voice = controller.snapshot().voiceInput,
                muted = controller.snapshot().ambientMuted;
        String draft = controller.draft(), quote = controller.snapshot().quote;
        try {
            controller.setVoiceInput(true);
            if (!muted) controller.ambientMute();
            onMain(() -> ((MainActivity) screen).navigate("conversation"));
            await(
                    () ->
                            hold() != null
                                    && controller.snapshot().voiceInput
                                    && controller.snapshot().ambientMuted,
                    3000);
            HoldToTalkView hold = hold();
            check(hold != null, "the actual conversation exposes its voice control");
            if (capture) keyboardVoice(hold);
            onMain(
                    () -> {
                        AccessibilityNodeInfo node = hold.createAccessibilityNodeInfo();
                        try {
                            check(
                                    "android.widget.Button".contentEquals(node.getClassName())
                                            && node.isClickable(),
                                    "the recording action exposes native button semantics");
                        } finally {
                            node.recycle();
                        }
                        View root = screen.getWindow().getDecorView();
                        geometry(root, "环境模式");
                        geometry(root, "切换到文字输入");
                        geometry(root, "添加照片、拍照、文件或位置");
                        View nav = described(root, ui.t("对话"));
                        AccessibilityNodeInfo selected =
                                nav == null ? null : nav.createAccessibilityNodeInfo();
                        try {
                            check(
                                    selected != null
                                            && selected.isSelected()
                                            && "android.widget.Button"
                                                    .contentEquals(selected.getClassName()),
                                    "bottom navigation exposes the selected conversation as a"
                                            + " button");
                        } finally {
                            if (selected != null) selected.recycle();
                        }
                    });
            if (capture) pendingPermission();
            sheet();
            controller.setVoiceInput(false);
            controller.draft("QA accessible retained draft");
            await(() -> !controller.snapshot().voiceInput && !controller.draft().isEmpty(), 2000);
            probe.waitForIdleSync();
            Thread.sleep(100);
            onMain(
                    () -> {
                        View root = screen.getWindow().getDecorView();
                        geometry(root, "切换到按住说话");
                        geometry(root, "添加照片、拍照、文件或位置");
                        geometry(root, "发送消息");
                    });
            probe.screenshot(context, "accessibility-conversation-controls");
        } finally {
            controller.releaseHold(true);
            controller.ambientHush();
            stateBarrier();
            controller.draft(draft);
            controller.quote(quote);
            controller.setVoiceInput(voice);
            if (!muted) controller.ambientMute();
            await(() -> !RecordingInput.isActive() && !controller.snapshot().holdRecording, 4000);
        }
        return assertions;
    }

    private void keyboardVoice(HoldToTalkView hold) throws Exception {
        Set<String> rows = new HashSet<>();
        for (Message row : controller.snapshot().rows) rows.add(row.id);
        probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        onMain(() -> check(hold.requestFocus(), "the voice action accepts actual keyboard focus"));
        probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(
                () -> controller.snapshot().holdLive && controller.snapshot().holdDurationMs >= 400,
                5000);
        check(
                controller.snapshot().holdLive && RecordingInput.isActive(),
                "a keyboard activation starts the real native microphone without touch"
                        + " exploration");
        probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(
                () ->
                        !controller.snapshot().holdRecording
                                && !RecordingInput.isActive()
                                && controller.snapshot().outbox.isEmpty()
                                && controller.snapshot().rows.stream()
                                        .anyMatch(
                                                row ->
                                                        !rows.contains(row.id)
                                                                && row.kind == Message.MINE
                                                                && row.seq >= 0
                                                                && row.durationMs >= 400),
                6000);
        check(
                !controller.snapshot().holdRecording
                        && !RecordingInput.isActive()
                        && controller.snapshot().outbox.isEmpty()
                        && controller.snapshot().rows.stream()
                                .anyMatch(
                                        row ->
                                                !rows.contains(row.id)
                                                        && row.kind == Message.MINE
                                                        && row.seq >= 0
                                                        && row.durationMs >= 400),
                "the next keyboard activation sends captured Opus only to the disposable"
                        + " transport");
        int count = controller.snapshot().rows.size();
        onMain(
                () ->
                        check(
                                hold.performAccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_CLICK, null),
                                "the native accessibility click activates recording"));
        await(
                () -> controller.snapshot().holdLive && controller.snapshot().holdDurationMs >= 400,
                5000);
        check(
                controller.snapshot().holdLive,
                "accessibility activation uses the ordinary microphone lease");
        onMain(
                () -> {
                    check(
                            action(hold, AccessibilityNodeInfo.ACTION_DISMISS),
                            "a recording exposes a named accessibility cancel action");
                    check(
                            hold.performAccessibilityAction(
                                    AccessibilityNodeInfo.ACTION_DISMISS, null),
                            "the native cancel action is handled");
                });
        await(() -> !controller.snapshot().holdRecording && !RecordingInput.isActive(), 4000);
        check(
                !controller.snapshot().holdRecording
                        && !RecordingInput.isActive()
                        && controller.snapshot().outbox.isEmpty()
                        && controller.snapshot().rows.size() == count,
                "accessible cancellation releases input without sending another note");
    }

    private void pendingPermission() throws Exception {
        AtomicReference<Runnable> grant = new AtomicReference<>();
        PageHost host = (PageHost) screen;
        PageHost pending =
                new PageHost() {
                    public Activity activity() {
                        return screen;
                    }

                    public Ui ui() {
                        return ui;
                    }

                    public void navigate(String page) {
                        host.navigate(page);
                    }

                    public void back() {
                        host.back();
                    }

                    public void toast(String text) {
                        host.toast(text);
                    }

                    public void permission(String[] names, Runnable action) {
                        grant.set(action);
                    }

                    public void pickAttachment(String mime) {
                        throw new AssertionError();
                    }

                    public void takePhoto() {
                        throw new AssertionError();
                    }

                    public void openAttachment(Attachment attachment) {
                        throw new AssertionError();
                    }

                    public void loginTailnet() {
                        throw new AssertionError();
                    }
                };
        AtomicReference<HoldToTalkView> waiting = new AtomicReference<>();
        onMain(
                () -> {
                    FrameLayout content = screen.findViewById(android.R.id.content);
                    waiting.set(new HoldToTalkView(pending, controller));
                    content.addView(waiting.get(), new FrameLayout.LayoutParams(-1, ui.dp(48)));
                });
        try {
            onMain(
                    () -> {
                        waiting.get()
                                .performAccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_CLICK, null);
                        check(
                                grant.get() != null
                                        && action(
                                                waiting.get(),
                                                AccessibilityNodeInfo.ACTION_DISMISS),
                                "a pending permission action is cancellable before microphone"
                                        + " start");
                        Runnable obsolete = grant.get();
                        waiting.get()
                                .performAccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_DISMISS, null);
                        waiting.get()
                                .performAccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_CLICK, null);
                        obsolete.run();
                    });
            stateBarrier();
            check(
                    !controller.snapshot().holdRecording && !RecordingInput.isActive(),
                    "an old permission result cannot activate the replacement recording intent");
            onMain(
                    () -> {
                        ((ViewGroup) waiting.get().getParent()).removeView(waiting.get());
                        grant.get().run();
                    });
            stateBarrier();
            check(
                    !controller.snapshot().holdRecording && !RecordingInput.isActive(),
                    "a permission result after the recording view detached cannot start capture");
        } finally {
            onMain(
                    () -> {
                        if (waiting.get().getParent() instanceof ViewGroup)
                            ((ViewGroup) waiting.get().getParent()).removeView(waiting.get());
                    });
        }
    }

    private void stateBarrier() throws Exception {
        java.lang.reflect.Field owner = PocketController.class.getDeclaredField("state");
        owner.setAccessible(true);
        FutureTask<Void> barrier = new FutureTask<>(() -> null);
        ((Executor) owner.get(controller)).execute(barrier);
        barrier.get(3, TimeUnit.SECONDS);
    }

    private void sheet() throws Exception {
        AtomicReference<Dialog> sheet = new AtomicReference<>();
        AtomicInteger selected = new AtomicInteger();
        String text = "QA accessible action with a deliberately wrapping compact-screen label";
        onMain(
                () ->
                        sheet.set(
                                Sheets.actions(
                                        ui,
                                        "QA accessibility",
                                        new Sheets.Action(text, selected::incrementAndGet))));
        probe.waitForIdleSync();
        try {
            onMain(
                    () -> {
                        View root = sheet.get().getWindow().getDecorView();
                        geometry(root, "关闭面板");
                        View button = probe.textView(root, text);
                        AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
                        try {
                            check(
                                    "android.widget.Button".contentEquals(node.getClassName())
                                            && node.isClickable(),
                                    "wrapping sheet text exposes a native button instead of a plain"
                                            + " label");
                        } finally {
                            node.recycle();
                        }
                        check(
                                button.performAccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_CLICK, null),
                                "the ordinary sheet action is reachable through native"
                                        + " accessibility");
                    });
            check(
                    selected.get() == 1 && !sheet.get().isShowing(),
                    "one accessibility activation selects once and dismisses its sheet");
        } finally {
            onMain(() -> sheet.get().dismiss());
        }
    }
}
