// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Instrumentation.ActivityMonitor;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

import dev.openduo.pocket.R;
import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Real configuration recreation, with state observed on the replacement shipping Activity. */
final class WindowUiProbe {
    private final Probe probe;
    private final JSONArray failures = new JSONArray();
    private final JSONArray windows = new JSONArray();
    private MainActivity screen;
    private int assertions;
    private File directory;

    WindowUiProbe(Probe probe) {
        this.probe = probe;
    }

    private <T> T main(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        probe.runOnMainSync(
                () -> {
                    try {
                        result.set(work.get());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) failures.put(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(25);
    }

    String run(Context context, android.os.Bundle arguments) throws Exception {
        if (!android.os.Build.HARDWARE.contains("ranchu"))
            throw new AssertionError("Emulator only");
        PocketController controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured())
            throw new AssertionError("An actual room must not be configured");
        directory = new File(context.getExternalCacheDir(), "window-ui");
        directory.mkdirs();
        String language = controller.settings.language();
        boolean voice = controller.settings.voiceInput(),
                output = controller.settings.ambientOutput();
        controller.settings.language("en");
        controller.setReplyAudio(false);
        try {
            boolean freeform = "1".equals(arguments.getString("freeform"));
            if (freeform) {
                ActivityMonitor monitor =
                        probe.addMonitor(MainActivity.class.getName(), null, false);
                try {
                    shell("am start --windowingMode 5 -n dev.openduo.pocket/.app.MainActivity");
                    screen = (MainActivity) monitor.waitForActivityWithTimeout(8000);
                    check(screen != null, "freeform creates the shipping Activity");
                    if (screen == null) throw new AssertionError("No freeform Activity");
                } finally {
                    probe.removeMonitor(monitor);
                }
            } else
                screen =
                        (MainActivity)
                                probe.startActivitySync(
                                        new Intent(Intent.ACTION_MAIN)
                                                .setClass(context, MainActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            controller.startTrying();
            await(() -> controller.snapshot().trying, 5000);
            controller.setVoiceInput(false);
            if ("1".equals(arguments.getString("split"))) {
                split(controller);
                JSONObject result =
                        new JSONObject()
                                .put("assertions", assertions)
                                .put("windows", windows)
                                .put("failures", failures);
                Files.write(
                        new File(directory, "split.json").toPath(),
                        result.toString(2).getBytes(StandardCharsets.UTF_8));
                if (failures.length() > 0) throw new AssertionError(failures.toString());
                return "PASS " + result;
            }
            if (freeform) {
                freeform(controller);
                JSONObject result =
                        new JSONObject()
                                .put("assertions", assertions)
                                .put("windows", windows)
                                .put("failures", failures);
                Files.write(
                        new File(directory, "freeform.json").toPath(),
                        result.toString(2).getBytes(StandardCharsets.UTF_8));
                if (failures.length() > 0) throw new AssertionError(failures.toString());
                return "PASS " + result;
            }
            navigate("pairing");
            main(
                    () -> {
                        field(R.id.pair_link)
                                .setText(
                                        "duoduopocket://pair?host=example.invalid&code=QA-rotation");
                        field(R.id.pair_host).setText("example.invalid");
                        field(R.id.pair_code).setText("QA-rotation");
                        field(R.id.pair_link).requestFocus();
                        field(R.id.pair_link).setSelection(3, 10);
                        return null;
                    });
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "pair-landscape");
            main(
                    () -> {
                        check(
                                field(R.id.pair_host)
                                        .getText()
                                        .toString()
                                        .equals("example.invalid"),
                                "rotation preserves the unsent pairing host");
                        check(
                                field(R.id.pair_code).getText().toString().equals("QA-rotation"),
                                "rotation preserves the unsent pairing code");
                        check(
                                field(R.id.pair_link).getSelectionStart() == 3
                                        && field(R.id.pair_link).getSelectionEnd() == 10,
                                "rotation preserves the pairing selection");
                        return null;
                    });
            navigate("conversation");
            controller.draft("QA durable window draft with a selection");
            await(
                    () ->
                            main(
                                    () ->
                                            field(R.id.composer)
                                                    .getText()
                                                    .toString()
                                                    .equals(
                                                            "QA durable window draft with a"
                                                                    + " selection")),
                    3000);
            main(
                    () -> {
                        field(R.id.composer).requestFocus();
                        field(R.id.composer).setSelection(4, 12);
                        return null;
                    });
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "conversation-portrait");
            main(
                    () -> {
                        check(
                                field(R.id.composer)
                                        .getText()
                                        .toString()
                                        .equals("QA durable window draft with a selection"),
                                "rotation preserves the controller draft");
                        check(
                                field(R.id.composer).getSelectionStart() == 4
                                        && field(R.id.composer).getSelectionEnd() == 12,
                                "rotation preserves composer selection");
                        return null;
                    });
            navigate("pairing");
            main(
                    () -> {
                        check(
                                field(R.id.pair_code).getText().toString().equals("QA-rotation"),
                                "a page that was hidden at rotation retains its form");
                        return null;
                    });
            navigate("settings");
            main(
                    () -> {
                        visibleScroll(screen.getWindow().getDecorView())
                                .fullScroll(View.FOCUS_DOWN);
                        return null;
                    });
            Thread.sleep(400);
            check(
                    main(() -> visibleScroll(screen.getWindow().getDecorView()).getScrollY()) > 0,
                    "settings has a nonzero reading position before recreation");
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "settings-landscape");
            check(
                    main(() -> visibleScroll(screen.getWindow().getDecorView()).getScrollY()) > 0,
                    "settings reading position survives recreation");
            JSONObject result =
                    new JSONObject()
                            .put("assertions", assertions)
                            .put("windows", windows)
                            .put("failures", failures);
            Files.write(
                    new File(directory, "result.json").toPath(),
                    result.toString(2).getBytes(StandardCharsets.UTF_8));
            if (failures.length() > 0) throw new AssertionError(failures.toString());
            return "PASS " + result;
        } finally {
            controller.stopTrying();
            controller.settings.language(language);
            controller.setVoiceInput(voice);
            controller.setReplyAudio(output);
            if (screen != null)
                main(
                        () -> {
                            screen.finish();
                            return null;
                        });
        }
    }

    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor output =
                        probe.getUiAutomation().executeShellCommand(command);
                java.io.FileInputStream input =
                        new java.io.FileInputStream(output.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) {}
        }
    }

    private void freeform(PocketController controller) throws Exception {
        check(
                main(() -> screen.isInMultiWindowMode()),
                "Activity is in actual freeform multi-window mode");
        navigate("conversation");
        controller.draft("QA freeform resize preserves this draft");
        await(
                () ->
                        main(
                                () ->
                                        field(R.id.composer)
                                                .getText()
                                                .toString()
                                                .equals("QA freeform resize preserves this draft")),
                3000);
        for (int[] bounds :
                new int[][] {{40, 60, 1240, 1260}, {80, 80, 1920, 1480}, {120, 100, 840, 1420}}) {
            ActivityMonitor monitor = probe.addMonitor(MainActivity.class.getName(), null, false);
            try {
                shell(
                        "am task resize "
                                + screen.getTaskId()
                                + " "
                                + bounds[0]
                                + " "
                                + bounds[1]
                                + " "
                                + bounds[2]
                                + " "
                                + bounds[3]);
                android.app.Activity replacement = monitor.waitForActivityWithTimeout(2000);
                if (replacement != null) screen = (MainActivity) replacement;
                probe.waitForIdleSync();
                Thread.sleep(300);
                android.content.res.Configuration config = screen.getResources().getConfiguration();
                check(
                        main(() -> screen.isInMultiWindowMode()),
                        "resized Activity stays in multi-window mode");
                check(
                        main(
                                () ->
                                        field(R.id.composer)
                                                .getText()
                                                .toString()
                                                .equals("QA freeform resize preserves this draft")),
                        "resizing preserves the draft");
                windows.put(
                        new JSONObject()
                                .put("widthDp", config.screenWidthDp)
                                .put("heightDp", config.screenHeightDp)
                                .put("multiWindow", screen.isInMultiWindowMode()));
                android.graphics.Bitmap image = probe.getUiAutomation().takeScreenshot();
                if (image != null) {
                    try (java.io.FileOutputStream out =
                            new java.io.FileOutputStream(
                                    new File(
                                            directory,
                                            "freeform-" + config.screenWidthDp + ".png"))) {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        image.recycle();
                    }
                }
            } finally {
                probe.removeMonitor(monitor);
            }
        }
    }

    private void split(PocketController controller) throws Exception {
        navigate("conversation");
        controller.draft("QA actual split window draft");
        await(
                () ->
                        main(
                                () ->
                                        field(R.id.composer)
                                                .getText()
                                                .toString()
                                                .equals("QA actual split window draft")),
                3000);
        ActivityMonitor monitor = probe.addMonitor(MainActivity.class.getName(), null, false);
        try {
            shell("am start -n com.android.settings/.Settings");
            Thread.sleep(700);
            shell("input keyevent 187");
            Thread.sleep(700);
            check(systemAction("Split", false), "the system Recents Split action is available");
            Thread.sleep(500);
            check(
                    systemAction("DuoDuo Pocket", true),
                    "the system app selector chooses DuoDuo Pocket as the second pane");
            android.app.Activity replacement = monitor.waitForActivityWithTimeout(4000);
            if (replacement != null) screen = (MainActivity) replacement;
            Thread.sleep(500);
            check(
                    main(() -> screen.isInMultiWindowMode()),
                    "Activity reports actual system split-screen mode");
            navigate("conversation");
            check(
                    main(
                            () ->
                                    field(R.id.composer)
                                            .getText()
                                            .toString()
                                            .equals("QA actual split window draft")),
                    "entering split screen preserves the draft");
            for (String page :
                    new String[] {
                        "conversation",
                        "ambient",
                        "settings",
                        "sessions",
                        "location",
                        "pairing",
                        "onboarding",
                        "diagnostics"
                    }) {
                navigate(page);
                android.content.res.Configuration config = screen.getResources().getConfiguration();
                windows.put(
                        new JSONObject()
                                .put("page", page)
                                .put("widthDp", config.screenWidthDp)
                                .put("heightDp", config.screenHeightDp)
                                .put("multiWindow", screen.isInMultiWindowMode()));
                check(
                        screen.isInMultiWindowMode() && config.screenWidthDp < 1000,
                        page + " uses the split pane's configuration");
                android.graphics.Bitmap image = probe.getUiAutomation().takeScreenshot();
                if (image != null) {
                    try (java.io.FileOutputStream out =
                            new java.io.FileOutputStream(
                                    new File(directory, "split-" + page + ".png"))) {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        image.recycle();
                    }
                }
            }
        } finally {
            probe.removeMonitor(monitor);
        }
    }

    private boolean systemAction(String label, boolean description) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            android.view.accessibility.AccessibilityNodeInfo root =
                    probe.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    if (clickNode(root, label, description)) return true;
                } finally {
                    root.recycle();
                }
            }
            Thread.sleep(200);
        }
        return false;
    }

    private boolean clickNode(
            android.view.accessibility.AccessibilityNodeInfo node,
            String label,
            boolean description) {
        CharSequence text = description ? node.getContentDescription() : node.getText();
        if (node.isVisibleToUser() && text != null && label.contentEquals(text)) {
            android.view.accessibility.AccessibilityNodeInfo action =
                    android.view.accessibility.AccessibilityNodeInfo.obtain(node);
            try {
                for (int depth = 0; action != null && depth < 6; depth++) {
                    if (action.isClickable())
                        return action.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    android.view.accessibility.AccessibilityNodeInfo parent = action.getParent();
                    action.recycle();
                    action = parent;
                }
            } finally {
                if (action != null) action.recycle();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    if (clickNode(child, label, description)) return true;
                } finally {
                    child.recycle();
                }
            }
        }
        return false;
    }

    private EditText field(int id) {
        return screen.findViewById(id);
    }

    private ScrollView visibleScroll(View view) {
        if (!view.isShown()) return null;
        if (view instanceof ScrollView && view.getId() == R.id.page_scroll)
            return (ScrollView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = visibleScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void navigate(String page) throws Exception {
        main(
                () -> {
                    screen.ui().hideKeyboard(screen.getWindow().getDecorView());
                    screen.navigate(page);
                    return null;
                });
        probe.waitForIdleSync();
        Thread.sleep(250);
    }

    private void rotate(int orientation, String name) throws Exception {
        ActivityMonitor monitor = probe.addMonitor(MainActivity.class.getName(), null, false);
        MainActivity previous = screen;
        try {
            main(
                    () -> {
                        screen.setRequestedOrientation(orientation);
                        return null;
                    });
            android.app.Activity replacement = monitor.waitForActivityWithTimeout(7000);
            check(
                    replacement != null && replacement != previous,
                    name + " creates a replacement Activity");
            if (replacement != null) screen = (MainActivity) replacement;
            probe.waitForIdleSync();
            Thread.sleep(600);
            android.content.res.Configuration config = screen.getResources().getConfiguration();
            check(
                    (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                            == (config.orientation
                                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE),
                    name + " uses the requested orientation");
            android.graphics.Rect display =
                    screen.getWindowManager().getMaximumWindowMetrics().getBounds();
            android.graphics.Rect window =
                    screen.getWindowManager().getCurrentWindowMetrics().getBounds();
            check(
                    (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                            == (display.width() > display.height()),
                    name + " rotates the actual display instead of a letterboxed Activity");
            check(
                    window.width() == display.width() && window.height() == display.height(),
                    name + " fills the rotated display");
            windows.put(
                    new JSONObject()
                            .put("name", name)
                            .put("widthDp", config.screenWidthDp)
                            .put("heightDp", config.screenHeightDp)
                            .put("displayBounds", display.toShortString())
                            .put("windowBounds", window.toShortString())
                            .put("multiWindow", screen.isInMultiWindowMode()));
            android.graphics.Bitmap image = probe.getUiAutomation().takeScreenshot();
            if (image != null) {
                try (java.io.FileOutputStream out =
                        new java.io.FileOutputStream(new File(directory, name + ".png"))) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally {
                    image.recycle();
                }
            }
        } finally {
            probe.removeMonitor(monitor);
        }
    }
}
