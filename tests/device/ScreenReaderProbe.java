// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.components.Sheets;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Observes actual TalkBack swipe traversal without suppressing the running accessibility service.
 */
final class ScreenReaderProbe {
    private final Probe probe;
    private UiAutomation automation;
    private MainActivity screen;
    private final List<JSONObject> focusEvents = new ArrayList<>();
    private final JSONArray pages = new JSONArray(), failures = new JSONArray();
    private int assertions;

    ScreenReaderProbe(Probe probe) {
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
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(30);
    }

    String run(Context context) throws Exception {
        if (!android.os.Build.HARDWARE.contains("ranchu"))
            throw new AssertionError("Emulator only");
        automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.view.accessibility.AccessibilityManager manager =
                context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        await(manager::isTouchExplorationEnabled, 5000);
        check(
                manager.getEnabledAccessibilityServiceList(-1).stream()
                        .anyMatch(service -> service.getId().contains("TalkBackService")),
                "Actual TalkBack is enabled");
        check(manager.isTouchExplorationEnabled(), "TalkBack touch exploration is active");
        if (failures.length() > 0) throw new AssertionError(failures.toString());
        PocketController controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured())
            throw new AssertionError("An actual room must not be configured");
        String language = controller.settings.language();
        boolean voice = controller.settings.voiceInput(),
                output = controller.settings.ambientOutput();
        controller.settings.language("en");
        controller.setReplyAudio(false);
        File directory = new File(context.getExternalCacheDir(), "screen-reader");
        directory.mkdirs();
        automation.setOnAccessibilityEventListener(
                event -> {
                    if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                        return;
                    AccessibilityNodeInfo node = event.getSource();
                    try {
                        if (node == null
                                || !context.getPackageName().contentEquals(node.getPackageName()))
                            return;
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        node.getBoundsInScreen(bounds);
                        JSONObject item =
                                new JSONObject()
                                        .put("label", spokenLabel(node, 0))
                                        .put("text", String.valueOf(node.getText()))
                                        .put(
                                                "description",
                                                String.valueOf(node.getContentDescription()))
                                        .put("class", String.valueOf(node.getClassName()))
                                        .put("bounds", bounds.toShortString())
                                        .put("clickable", node.isClickable())
                                        .put("longClickable", node.isLongClickable())
                                        .put("checked", node.isChecked())
                                        .put("selected", node.isSelected());
                        synchronized (focusEvents) {
                            focusEvents.add(item);
                        }
                    } catch (org.json.JSONException ignored) {
                    } finally {
                        if (node != null) node.recycle();
                    }
                });
        try {
            screen =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(Intent.ACTION_MAIN)
                                            .setClass(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            controller.startTrying();
            await(
                    () ->
                            controller.snapshot().trying
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    6000);
            controller.setVoiceInput(false);
            controller.draft("QA accessible draft");
            for (String page :
                    new String[] {
                        "onboarding",
                        "pairing",
                        "conversation",
                        "ambient",
                        "settings",
                        "sessions",
                        "location",
                        "diagnostics"
                    }) {
                main(
                        () -> {
                            screen.ui().hideKeyboard(screen.getWindow().getDecorView());
                            screen.navigate(page);
                            return null;
                        });
                Thread.sleep(450);
                traverse(page, directory);
            }
            main(
                    () -> {
                        screen.navigate("conversation");
                        return null;
                    });
            android.app.Dialog sheet =
                    main(
                            () ->
                                    Sheets.actions(
                                            screen.ui(),
                                            screen.ui().t("添加到消息"),
                                            new Sheets.Action(screen.ui().t("照片"), () -> {}),
                                            new Sheets.Action(screen.ui().t("拍照"), () -> {}),
                                            new Sheets.Action(screen.ui().t("文件"), () -> {}),
                                            new Sheets.Action(screen.ui().t("位置"), () -> {})));
            Thread.sleep(400);
            traverse("attachment-sheet", directory);
            main(
                    () -> {
                        sheet.dismiss();
                        return null;
                    });
            JSONObject report =
                    new JSONObject()
                            .put("assertions", assertions)
                            .put("service", "TalkBackService")
                            .put("driver", "raw emulator touchscreen swipe-right gestures")
                            .put("pages", pages)
                            .put("failures", failures);
            Files.write(
                    new File(directory, "result.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
            if (failures.length() > 0) throw new AssertionError(failures.toString());
            return "PASS " + report;
        } finally {
            automation.setOnAccessibilityEventListener(null);
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

    private void traverse(String page, File directory) throws Exception {
        synchronized (focusEvents) {
            focusEvents.clear();
        }
        Set<String> seen = new LinkedHashSet<>();
        int stale = 0, previous = 0;
        for (int swipe = 0; swipe < 160 && stale < 5; swipe++) {
            try (android.os.ParcelFileDescriptor output =
                    automation.executeShellCommand(
                            "su 0 /data/local/tmp/pocket-a11y-input /dev/input/event1 right")) {
                try (java.io.FileInputStream input =
                        new java.io.FileInputStream(output.getFileDescriptor())) {
                    byte[] buffer = new byte[256];
                    while (input.read(buffer) != -1) {}
                }
            }
            Thread.sleep(200);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo node =
                            root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                    if (node != null) {
                        try {
                            if (screen.getPackageName().contentEquals(node.getPackageName())) {
                                android.graphics.Rect bounds = new android.graphics.Rect();
                                node.getBoundsInScreen(bounds);
                                JSONObject item =
                                        new JSONObject()
                                                .put("label", spokenLabel(node, 0))
                                                .put("text", String.valueOf(node.getText()))
                                                .put(
                                                        "description",
                                                        String.valueOf(
                                                                node.getContentDescription()))
                                                .put("class", String.valueOf(node.getClassName()))
                                                .put("bounds", bounds.toShortString())
                                                .put("clickable", node.isClickable())
                                                .put("longClickable", node.isLongClickable())
                                                .put("checked", node.isChecked())
                                                .put("selected", node.isSelected());
                                synchronized (focusEvents) {
                                    focusEvents.add(item);
                                }
                            }
                        } finally {
                            node.recycle();
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
            synchronized (focusEvents) {
                for (JSONObject item : focusEvents) seen.add(item.toString());
            }
            if (seen.size() == previous) stale++;
            else stale = 0;
            previous = seen.size();
        }
        JSONArray trace;
        synchronized (focusEvents) {
            trace = new JSONArray(focusEvents);
        }
        check(
                seen.size() > 1,
                page + " receives multiple actual TalkBack accessibility focus stops");
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 0; i < trace.length(); i++) {
            JSONObject item = trace.getJSONObject(i);
            String text = item.getString("text"), description = item.getString("description");
            if (item.getBoolean("clickable"))
                check(
                        !item.getString("label").isEmpty(),
                        page + " has an unlabeled clickable TalkBack stop: " + item);
            labels.add(text);
            labels.add(description);
        }
        if (page.equals("conversation"))
            check(
                    labels.contains(screen.ui().t("添加照片、拍照、文件或位置"))
                            && labels.contains("Send message"),
                    "TalkBack reaches the composer attachment and send controls");
        if (page.equals("attachment-sheet"))
            check(
                    labels.contains("Close panel") && labels.contains("File"),
                    "TalkBack reaches sheet close and file import");
        JSONObject result =
                new JSONObject()
                        .put("page", page)
                        .put("uniqueStops", seen.size())
                        .put("trace", trace);
        pages.put(result);
        Files.write(
                new File(directory, page + ".json").toPath(),
                result.toString(2).getBytes(StandardCharsets.UTF_8));
        android.graphics.Bitmap image = automation.takeScreenshot();
        if (image != null) {
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(directory, page + ".png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                image.recycle();
            }
        }
    }

    private static String spokenLabel(AccessibilityNodeInfo node, int depth) {
        if (node.getContentDescription() != null) return node.getContentDescription().toString();
        StringBuilder label = new StringBuilder();
        if (node.getText() != null) label.append(node.getText());
        if (depth < 8)
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    String text = spokenLabel(child, depth + 1);
                    if (!text.isEmpty()) label.append(label.length() == 0 ? "" : ", ").append(text);
                } finally {
                    child.recycle();
                }
            }
        return label.toString();
    }
}
