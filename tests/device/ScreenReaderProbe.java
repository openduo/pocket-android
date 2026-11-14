// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ScrollView;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.components.Sheets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
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

    String run(Context context, android.os.Bundle arguments) throws Exception {
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
            for (int turn = 0; turn < 4; turn++) {
                int before = controller.snapshot().rows.size();
                controller.draft("QA accessible prepared turn " + turn);
                controller.sendDraft();
                await(
                        () ->
                                controller.snapshot().rows.size() >= before + 2
                                        && controller.snapshot().outbox.isEmpty(),
                        8000);
                check(
                        controller.snapshot().rows.size() >= before + 2,
                        "prepared long-content turn completes before service traversal");
            }
            controller.draft("QA accessible draft");
            if ("message-actions".equals(arguments.getString("screenReaderScene"))) {
                messageActions(directory);
                JSONObject report = report();
                Files.write(
                        new File(directory, "result.json").toPath(),
                        report.toString(2).getBytes(StandardCharsets.UTF_8));
                if (failures.length() > 0) throw new AssertionError(failures.toString());
                return "PASS " + report;
            }
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
                            View scroll =
                                    visible(
                                            screen.getWindow().getDecorView(),
                                            dev.openduo.pocket.R.id.page_scroll);
                            if (scroll instanceof ScrollView)
                                ((ScrollView) scroll).fullScroll(View.FOCUS_UP);
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
            check(
                    activate("Close panel"),
                    "actual TalkBack double tap closes the attachment sheet");
            check(!main(sheet::isShowing), "the actual attachment window has been dismissed");
            messageActions(directory);
            main(
                    () -> {
                        screen.navigate("sessions");
                        return null;
                    });
            controller.openSession("try-chat");
            await(
                    () ->
                            controller.snapshot().sessions.openKey.equals("try-chat")
                                    && controller.snapshot().sessions.loaded
                                    && main(
                                            () ->
                                                    visible(
                                                                    screen.getWindow()
                                                                            .getDecorView(),
                                                                    dev.openduo.pocket.R.id
                                                                            .composer)
                                                            != null),
                    5000);
            main(
                    () -> {
                        EditText editor =
                                (EditText)
                                        visible(
                                                screen.getWindow().getDecorView(),
                                                dev.openduo.pocket.R.id.composer);
                        editor.setText("QA accessible session draft");
                        return null;
                    });
            Thread.sleep(400);
            traverse("session-detail", directory);
            check(
                    activate("Send notification"),
                    "actual TalkBack activates the prepared notify control");
            await(() -> controller.snapshot().sessions.outcome.equals("sent"), 5000);
            check(
                    controller.snapshot().sessions.outcome.equals("sent"),
                    "actual TalkBack activation settles one notification in the disposable"
                            + " transport");
            AtomicReference<String> edited = new AtomicReference<>();
            android.app.Dialog editor =
                    main(() -> Sheets.edit(screen.ui(), "QA editor", "QA edit value", edited::set));
            Thread.sleep(400);
            traverse("edit-sheet", directory);
            check(activate("OK"), "actual TalkBack activates the editor commit control");
            await(() -> edited.get() != null, 2000);
            check(
                    "QA edit value".equals(edited.get()),
                    "actual editor action retains the exact value");
            main(
                    () -> {
                        editor.dismiss();
                        return null;
                    });
            JSONObject report =
                    new JSONObject()
                            .put("schema", 2)
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
            try {
                if (!new File(directory, "result.json").exists())
                    Files.write(
                            new File(directory, "result.json").toPath(),
                            report().put("complete", false)
                                    .toString(2)
                                    .getBytes(StandardCharsets.UTF_8));
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
    }

    private void traverse(String page, File directory) throws Exception {
        synchronized (focusEvents) {
            focusEvents.clear();
        }
        Set<String> seen = new LinkedHashSet<>();
        int stale = 0, previous = 0;
        for (int swipe = 0; swipe < 160 && stale < 5; swipe++) {
            gesture("right");
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
        verifyOrder(page, trace);
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

    private void gesture(String name) throws Exception {
        String arguments = "";
        if (name.equals("activate") || name.equals("longpress")) {
            AccessibilityNodeInfo node = focused();
            if (node == null) throw new AssertionError("No actual service focus for activation");
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            node.recycle();
            android.graphics.Rect display =
                    main(() -> screen.getWindowManager().getMaximumWindowMetrics().getBounds());
            arguments =
                    " "
                            + (bounds.centerX() * 32767 / display.width())
                            + " "
                            + (bounds.centerY() * 32767 / display.height());
        }
        try (android.os.ParcelFileDescriptor output =
                        automation.executeShellCommand(
                                "su 0 /data/local/tmp/pocket-a11y-input /dev/input/event1 "
                                        + name
                                        + arguments);
                java.io.FileInputStream input =
                        new java.io.FileInputStream(output.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) {}
        }
    }

    private android.view.accessibility.AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return null;
        try {
            return root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } finally {
            root.recycle();
        }
    }

    private boolean seek(Predicate<AccessibilityNodeInfo> matches) throws Exception {
        for (int step = 0; step < 120; step++) {
            AccessibilityNodeInfo node = focused();
            if (node != null) {
                try {
                    if (screen.getPackageName().contentEquals(node.getPackageName())
                            && matches.test(node)) return true;
                } finally {
                    node.recycle();
                }
            }
            gesture("right");
            Thread.sleep(180);
        }
        return false;
    }

    private boolean activate(String label) throws Exception {
        if (!seek(node -> node.isEnabled() && label.equals(spokenLabel(node, 0)))) return false;
        gesture("activate");
        Thread.sleep(400);
        return true;
    }

    private void messageActions(File directory) throws Exception {
        main(
                () -> {
                    screen.navigate("conversation");
                    return null;
                });
        Thread.sleep(350);
        check(
                seek(
                        node ->
                                node.isLongClickable()
                                        && spokenLabel(node, 0)
                                                .startsWith("I put this week's notes")),
                "actual TalkBack reaches a prepared answer with message actions");
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        int previous = root.getWindowId();
        root.recycle();
        gesture("longpress");
        Thread.sleep(500);
        root = automation.getRootInActiveWindow();
        JSONObject diagnostic =
                new JSONObject()
                        .put("previousWindow", previous)
                        .put("activeWindow", root == null ? -1 : root.getWindowId())
                        .put("content", root == null ? "" : spokenLabel(root, 0));
        Files.write(
                new File(directory, "message-open.json").toPath(),
                diagnostic.toString(2).getBytes(StandardCharsets.UTF_8));
        android.graphics.Bitmap image = automation.takeScreenshot();
        if (image != null) {
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(directory, "message-open.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                image.recycle();
            }
        }
        boolean opened = root != null && root.getWindowId() != previous;
        if (root != null) root.recycle();
        check(opened, "actual TalkBack double tap and hold opens message actions");
        if (!opened)
            throw new AssertionError("Message actions did not open from the real service gesture");
        traverse("message-actions", directory);
        check(activate("Close panel"), "actual TalkBack dismisses message actions");
    }

    private static View visible(View view, int id) {
        if (!view.isShown()) return null;
        if (view.getId() == id) return view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = visible(((ViewGroup) view).getChildAt(i), id);
                if (found != null) return found;
            }
        return null;
    }

    private JSONObject report() throws Exception {
        return new JSONObject()
                .put("schema", 2)
                .put("assertions", assertions)
                .put("service", "TalkBackService")
                .put("pages", pages)
                .put("failures", failures);
    }

    private void verifyOrder(String page, JSONArray trace) throws Exception {
        String[] expected;
        switch (page) {
            case "sessions":
                expected =
                        new String[] {
                            "Refresh sessions",
                            "Try-it room",
                            "Sample: weekly report",
                            "Sample: daily news digest",
                            "Back"
                        };
                break;
            case "ambient":
                expected =
                        new String[] {
                            "Mute",
                            "Stop",
                            "Expand full conversation",
                            "Turn on ambient mode",
                            "Chat",
                            "Ambient"
                        };
                break;
            case "onboarding":
                expected =
                        new String[] {
                            "Log In to Tailscale", "Connect room", "Try it · Prepared conversation"
                        };
                break;
            case "pairing":
                expected =
                        new String[] {
                            "Scan pairing QR",
                            "Read link",
                            "Host address",
                            "HTTPS",
                            "Code",
                            "Pair and connect",
                            "Log In to Tailscale"
                        };
                break;
            case "conversation":
                expected =
                        new String[] {
                            "Switch to hold to talk",
                            "QA accessible draft",
                            "Attach a photo, take a photo, add a file or location",
                            "Send message",
                            "Chat",
                            "Ambient",
                            "Settings"
                        };
                break;
            case "settings":
                expected =
                        new String[] {
                            "Room and host",
                            "Use headset microphone",
                            "Play spoken replies",
                            "Keep listening outside the app",
                            "Keep screen on",
                            "Haptic feedback",
                            "Show answer notifications",
                            "Let the room know where I arrived",
                            "Language",
                            "Theme",
                            "Export diagnostics",
                            "Exit try-it mode"
                        };
                break;
            case "location":
                expected =
                        new String[] {
                            "Search a place or enter latitude, longitude",
                            "Search",
                            "Map. Drag to choose the centre. Zoom buttons are below the map.",
                            "Current location",
                            "Zoom out",
                            "Zoom in",
                            "Use this location"
                        };
                break;
            case "diagnostics":
                expected = new String[] {"Share diagnostics", "Back"};
                break;
            case "attachment-sheet":
                expected = new String[] {"Photo", "Camera", "File", "Location", "Close panel"};
                break;
            case "message-actions":
                expected = new String[] {"Copy", "Share", "Reply", "Read aloud", "Close panel"};
                break;
            case "session-detail":
                expected =
                        new String[] {
                            "Refresh sessions",
                            "QA accessible session draft",
                            "Send notification",
                            "Back"
                        };
                break;
            case "edit-sheet":
                expected = new String[] {"QA edit value", "OK", "Close panel"};
                break;
            default:
                return;
        }
        Set<Integer> reached = new LinkedHashSet<>();
        List<Integer> sequence = new ArrayList<>();
        int last = -1;
        for (int i = 0; i < trace.length(); i++) {
            String label = trace.getJSONObject(i).getString("label");
            for (int n = 0; n < expected.length; n++) {
                if (!(label.equals(expected[n]) || label.startsWith(expected[n] + ", "))) continue;
                reached.add(n);
                if (n != last) {
                    sequence.add(n);
                    last = n;
                }
                break;
            }
        }
        for (int n = 0; n < expected.length; n++)
            check(reached.contains(n), page + " TalkBack reaches focus landmark: " + expected[n]);
        boolean ordered = true;
        // TalkBack can wrap to the visible suffix of an already-scrolled page. Validate the
        // first complete reading pass, before that platform wrap, against the control order.
        for (int i = 1; i < Math.min(sequence.size(), expected.length); i++)
            if (sequence.get(i) != (sequence.get(i - 1) + 1) % expected.length) ordered = false;
        check(ordered, page + " focus order follows the visual control sequence: " + sequence);
    }

    static String spokenLabel(AccessibilityNodeInfo node, int depth) {
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
