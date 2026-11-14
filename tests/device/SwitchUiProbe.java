// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.components.Sheets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Actual Switch Access key scanning, observed through its native highlight and action results. */
final class SwitchUiProbe {
    private final Probe probe;
    private UiAutomation automation;
    private MainActivity screen;
    private String keyboard;
    private File directory;
    private final JSONArray failures = new JSONArray(), pages = new JSONArray();
    private int assertions;

    SwitchUiProbe(Probe probe) {
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

    String run(Context context, Bundle arguments) throws Exception {
        if (!android.os.Build.HARDWARE.contains("ranchu"))
            throw new AssertionError("Emulator only");
        keyboard = arguments.getString("switchKeyboard", "");
        if (!keyboard.matches("/dev/input/event[0-9]+"))
            throw new AssertionError("Select the QA keyboard device");
        automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.view.accessibility.AccessibilityManager manager =
                context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        check(
                manager.getEnabledAccessibilityServiceList(-1).stream()
                        .anyMatch(service -> service.getId().contains("SwitchAccessService")),
                "Actual Switch Access is enabled");
        PocketController controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured())
            throw new AssertionError("An actual room must not be configured");
        String language = controller.settings.language();
        boolean voice = controller.settings.voiceInput(),
                output = controller.settings.ambientOutput();
        controller.settings.language("en");
        controller.setReplyAudio(false);
        directory = new File(context.getExternalCacheDir(), "switch-ui");
        directory.mkdirs();
        try {
            screen =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(Intent.ACTION_MAIN)
                                            .setClass(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            controller.startTrying();
            await(() -> controller.snapshot().trying, 5000);
            controller.setVoiceInput(false);
            for (int turn = 0; turn < 4; turn++) {
                int before = controller.snapshot().rows.size();
                controller.draft("QA Switch Access prepared turn " + turn);
                controller.sendDraft();
                await(
                        () ->
                                controller.snapshot().rows.size() >= before + 2
                                        && controller.snapshot().outbox.isEmpty(),
                        8000);
                check(
                        controller.snapshot().rows.size() >= before + 2,
                        "prepared long-content turn settles before switch scanning");
            }
            controller.draft("QA switch draft");
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
                View root = screen.getWindow().getDecorView();
                ScrollView scroll = main(() -> scrolling(root));
                Set<String> reached = scan(page, root, null);
                if (scroll != null) {
                    for (int pass = 1;
                            pass <= 8 && main(() -> scroll.canScrollVertically(1));
                            pass++) {
                        check(
                                advanceScroll(root, scroll),
                                page + " scrolls through the actual Switch Access service");
                        reached.addAll(scan(page + "-scroll-" + pass, root, null));
                    }
                    check(
                            !main(() -> scroll.canScrollVertically(1)),
                            page + " reaches the end of its native scroll content");
                }
                if (page.equals("settings"))
                    check(
                            reached.contains("Exit try-it mode"),
                            "actual Switch Access reaches settings below the initial viewport");
                verifyReachable(page, reached);
            }
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
            check(
                    main(
                            () ->
                                    visible(
                                                    screen.getWindow().getDecorView(),
                                                    dev.openduo.pocket.R.id.composer)
                                            != null),
                    "the loaded session has rendered its native notification editor");
            main(
                    () -> {
                        ((EditText)
                                        visible(
                                                screen.getWindow().getDecorView(),
                                                dev.openduo.pocket.R.id.composer))
                                .setText("QA Switch Access notification");
                        return null;
                    });
            Thread.sleep(400);
            verifyReachable(
                    "session-detail",
                    scan("session-detail", screen.getWindow().getDecorView(), null));
            check(
                    select(
                            "Send notification",
                            screen.getWindow().getDecorView(),
                            () -> controller.snapshot().sessions.outcome.equals("sent")),
                    "actual Switch Access selects a prepared session notification once");
            java.util.concurrent.atomic.AtomicReference<String> edited =
                    new java.util.concurrent.atomic.AtomicReference<>();
            android.app.Dialog editor =
                    main(
                            () ->
                                    Sheets.edit(
                                            screen.ui(),
                                            "QA switch editor",
                                            "QA switch value",
                                            edited::set));
            Thread.sleep(400);
            verifyReachable(
                    "edit-sheet", scan("edit-sheet", editor.getWindow().getDecorView(), null));
            check(
                    select("OK", editor.getWindow().getDecorView(), () -> edited.get() != null),
                    "actual Switch Access activates the edit-sheet confirmation");
            check(
                    "QA switch value".equals(edited.get()),
                    "the selected edit action retains the exact text");
            main(
                    () -> {
                        editor.dismiss();
                        return null;
                    });
            AtomicInteger activated = new AtomicInteger();
            android.app.Dialog sheet =
                    main(
                            () ->
                                    Sheets.actions(
                                            screen.ui(),
                                            "QA Switch Access",
                                            new Sheets.Action(
                                                    "QA scan and activate",
                                                    activated::incrementAndGet)));
            Thread.sleep(400);
            scan("sheet", sheet.getWindow().getDecorView(), activated);
            check(
                    activated.get() == 1,
                    "a real Switch Access selection activates the sheet action exactly once");
            main(
                    () -> {
                        sheet.dismiss();
                        return null;
                    });
            JSONObject result =
                    new JSONObject()
                            .put("schema", 2)
                            .put("assertions", assertions)
                            .put("pages", pages)
                            .put("failures", failures)
                            .put("service", "Google source Switch Access 6.1.1")
                            .put(
                                    "driver",
                                    "native uinput next/select plus service scroll switches through"
                                            + " InputReader");
            Files.write(
                    new File(directory, "result.json").toPath(),
                    result.toString(2).getBytes(StandardCharsets.UTF_8));
            if (failures.length() > 0) throw new AssertionError(failures.toString());
            return "PASS " + result;
        } finally {
            try {
                if (!new File(directory, "result.json").exists()) {
                    JSONObject partial =
                            new JSONObject()
                                    .put("schema", 2)
                                    .put("complete", false)
                                    .put("assertions", assertions)
                                    .put("pages", pages)
                                    .put("failures", failures);
                    Files.write(
                            new File(directory, "result.json").toPath(),
                            partial.toString(2).getBytes(StandardCharsets.UTF_8));
                }
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
    }

    private void key(int code) throws Exception {
        try (android.os.ParcelFileDescriptor output =
                        automation.executeShellCommand(
                                "su 0 /data/local/tmp/pocket-a11y-input "
                                        + keyboard
                                        + " key "
                                        + code);
                java.io.FileInputStream input =
                        new java.io.FileInputStream(output.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) {}
        }
        Thread.sleep(180);
    }

    private Set<String> scan(String page, View root, AtomicInteger activation) throws Exception {
        Set<String> seen = new LinkedHashSet<>(), labels = new LinkedHashSet<>();
        JSONArray trace = new JSONArray();
        int stale = 0;
        int limit = page.startsWith("conversation") ? 180 : page.startsWith("settings") ? 90 : 40;
        for (int step = 0; step < limit && stale < 8; step++) {
            key(30);
            Bitmap image = automation.takeScreenshot();
            if (image == null) throw new AssertionError("No scanner screenshot");
            try {
                Rect highlight = highlight(image);
                String label = main(() -> match(root, highlight));
                if (!label.isEmpty()) labels.add(label);
                String identity = highlight.toShortString() + ":" + label;
                if (seen.add(identity)) {
                    stale = 0;
                    trace.put(
                            new JSONObject()
                                    .put("bounds", highlight.toShortString())
                                    .put("label", label));
                    try (java.io.FileOutputStream out =
                            new java.io.FileOutputStream(
                                    new File(directory, page + "-" + seen.size() + ".png"))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                } else stale++;
                if (activation != null && label.equals("QA scan and activate")) {
                    key(48);
                    if (activation.get() == 0) key(48);
                    break;
                }
            } finally {
                image.recycle();
            }
        }
        check(seen.size() > 1, page + " has multiple actual Switch Access scan stops");
        verifyOrder(page, trace);
        JSONObject recorded = new JSONObject().put("page", page).put("trace", trace);
        pages.put(recorded);
        Files.write(
                new File(directory, page + ".json").toPath(),
                recorded.toString(2).getBytes(StandardCharsets.UTF_8));
        return labels;
    }

    private String[] landmarks(String page) {
        if (page.startsWith("settings"))
            return new String[] {
                "Room and host",
                "Tailscale",
                "Reconnect",
                "Log out of Tailscale",
                "Use headset microphone",
                "Play spoken replies",
                "Keep listening outside the app",
                "Keep screen on",
                "Haptic feedback",
                "Show answer notifications",
                "Notifications and background sync",
                "Let the room know where I arrived",
                "Arrival detection",
                "Session viewer",
                "Language",
                "Theme",
                "Record network log",
                "Export diagnostics",
                "About DuoDuo Pocket",
                "Forget room",
                "Exit try-it mode"
            };
        else if (page.startsWith("conversation"))
            return new String[] {
                "Switch to hold to talk",
                "QA switch draft",
                "Attach a photo, take a photo, add a file or location",
                "Send message",
                "Chat",
                "Ambient",
                "Settings"
            };
        else
            switch (page) {
                case "onboarding":
                    return new String[] {
                        "Log In to Tailscale", "Connect room", "Try it · Prepared conversation"
                    };
                case "pairing":
                    return new String[] {
                        "Back",
                        "Pairing link",
                        "Scan pairing QR",
                        "Read link",
                        "Host address",
                        "443",
                        "HTTPS",
                        "Code",
                        "Pair and connect",
                        "Log In to Tailscale"
                    };
                case "ambient":
                    return new String[] {
                        "Expand full conversation",
                        "Turn on ambient mode",
                        "Chat",
                        "Ambient",
                        "Settings"
                    };
                case "sessions":
                    return new String[] {
                        "Back",
                        "Refresh sessions",
                        "Try-it room",
                        "Sample: weekly report",
                        "Sample: daily news digest"
                    };
                case "location":
                    return new String[] {
                        "Back",
                        "Search a place or enter latitude, longitude",
                        "Search",
                        "Map. Drag to choose the centre. Zoom buttons are below the map.",
                        "Current location",
                        "Zoom out",
                        "Zoom in"
                    };
                case "diagnostics":
                    return new String[] {"Back", "Share diagnostics"};
                case "session-detail":
                    return new String[] {
                        "Back",
                        "Refresh sessions",
                        "QA Switch Access notification",
                        "Send notification"
                    };
                case "edit-sheet":
                    return new String[] {"Close panel", "QA switch value", "OK"};
                default:
                    return new String[0];
            }
    }

    private void verifyReachable(String page, Set<String> reached) {
        for (String expected : landmarks(page))
            check(
                    reached.stream()
                            .anyMatch(
                                    label ->
                                            label.equals(expected)
                                                    || label.startsWith(expected + ", ")),
                    page + " actual Switch Access reaches control: " + expected);
    }

    private void verifyOrder(String page, JSONArray trace) throws Exception {
        String[] expected = landmarks(page);
        if (expected.length == 0) return;
        int previous = -1, wraps = 0, matched = 0;
        JSONArray sequence = new JSONArray();
        for (int i = 0; i < trace.length(); i++) {
            String label = trace.getJSONObject(i).getString("label");
            for (int n = 0; n < expected.length; n++) {
                if (!(label.equals(expected[n]) || label.startsWith(expected[n] + ", "))) continue;
                if (n != previous) {
                    if (n < previous) wraps++;
                    previous = n;
                    matched++;
                    sequence.put(n);
                }
                break;
            }
        }
        check(
                matched > 0 && wraps <= 1,
                page + " actual scan follows the visible control order: " + sequence);
    }

    static Rect highlight(Bitmap image) {
        Rect highlight = new Rect(image.getWidth(), image.getHeight(), 0, 0);
        java.util.Map<Integer, Integer> colors = new java.util.HashMap<>();
        for (int y = 0; y < image.getHeight(); y += 2)
            for (int x = 0; x < image.getWidth(); x += 2) {
                int color = image.getPixel(x, y),
                        r = (color >> 16) & 255,
                        g = (color >> 8) & 255,
                        b = color & 255;
                if (b > 180 && g > 80 && g < 185 && r < 80) {
                    colors.put(color, colors.getOrDefault(color, 0) + 1);
                }
            }
        int scannerColor = 0, most = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : colors.entrySet())
            if (entry.getValue() > most) {
                scannerColor = entry.getKey();
                most = entry.getValue();
            }
        for (int y = 0; y < image.getHeight(); y += 2)
            for (int x = 0; x < image.getWidth(); x += 2) {
                if (image.getPixel(x, y) == scannerColor && most > 20) {
                    highlight.left = Math.min(highlight.left, x);
                    highlight.top = Math.min(highlight.top, y);
                    highlight.right = Math.max(highlight.right, x);
                    highlight.bottom = Math.max(highlight.bottom, y);
                }
            }
        return highlight;
    }

    private boolean advanceScroll(View root, ScrollView scroll) throws Exception {
        for (int step = 0; step < 50; step++) {
            key(30);
            Bitmap image = automation.takeScreenshot();
            if (image == null) throw new AssertionError("No service scan image");
            Rect bounds;
            try {
                bounds = highlight(image);
            } finally {
                image.recycle();
            }
            boolean inside =
                    main(
                            () -> {
                                View target = matched(root, bounds);
                                for (android.view.ViewParent parent =
                                                target == null ? null : target.getParent();
                                        parent != null;
                                        parent = parent.getParent())
                                    if (parent == scroll) return true;
                                return false;
                            });
            if (!inside) continue;
            int before = main(scroll::getScrollY);
            key(46);
            await(() -> main(scroll::getScrollY) != before, 1500);
            return main(scroll::getScrollY) != before;
        }
        return false;
    }

    private boolean select(String label, View root, BooleanSupplier completed) throws Exception {
        for (int step = 0; step < 90; step++) {
            key(30);
            Bitmap image = automation.takeScreenshot();
            if (image == null) throw new AssertionError("No service scan image");
            String selected;
            try {
                Rect bounds = highlight(image);
                selected = main(() -> match(root, bounds));
            } finally {
                image.recycle();
            }
            if (!label.equals(selected)) continue;
            key(48);
            await(completed, 1200);
            if (!completed.getAsBoolean()) key(48);
            await(completed, 3000);
            return completed.getAsBoolean();
        }
        return false;
    }

    private static ScrollView scrolling(View view) {
        if (!view.isShown()) return null;
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                ScrollView found = scrolling(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        return null;
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

    private String match(View view, Rect highlight) {
        View target = matched(view, highlight);
        return target == null ? "" : label(target);
    }

    private View matched(View view, Rect highlight) {
        if (!view.isShown()) return null;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds)) return null;
        int[] windowPosition = new int[2];
        view.getRootView().getLocationOnScreen(windowPosition);
        bounds.offset(windowPosition[0], windowPosition[1]);
        if ((view.isClickable() || view.isLongClickable())
                && Math.abs(bounds.left - highlight.left) < 24
                && Math.abs(bounds.top - highlight.top) < 24
                && Math.abs(bounds.right - highlight.right) < 24
                && Math.abs(bounds.bottom - highlight.bottom) < 24) return view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = matched(((ViewGroup) view).getChildAt(i), highlight);
                if (found != null) return found;
            }
        return null;
    }

    private String label(View view) {
        if (view.getContentDescription() != null) return view.getContentDescription().toString();
        if (view instanceof android.widget.EditText
                && ((TextView) view).length() == 0
                && ((TextView) view).getHint() != null)
            return ((TextView) view).getHint().toString();
        if (view instanceof TextView) return ((TextView) view).getText().toString();
        StringBuilder text = new StringBuilder();
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                String child = label(((ViewGroup) view).getChildAt(i));
                if (!child.isEmpty()) text.append(text.length() == 0 ? "" : ", ").append(child);
            }
        return text.toString();
    }
}
