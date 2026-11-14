// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.components.Sheets;

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
                scan(page, screen.getWindow().getDecorView(), null);
            }
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
                            .put("assertions", assertions)
                            .put("pages", pages)
                            .put("failures", failures)
                            .put("service", "Google source Switch Access 6.1.1")
                            .put("driver", "native uinput two-switch keyboard through InputReader");
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

    private void scan(String page, View root, AtomicInteger activation) throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        JSONArray trace = new JSONArray();
        int stale = 0;
        for (int step = 0; step < (page.equals("settings") ? 90 : 40) && stale < 8; step++) {
            key(30);
            Bitmap image = automation.takeScreenshot();
            if (image == null) throw new AssertionError("No scanner screenshot");
            try {
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
                String label = main(() -> match(root, highlight));
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
        pages.put(new JSONObject().put("page", page).put("trace", trace));
    }

    private String match(View view, Rect highlight) {
        if (!view.isShown()) return "";
        Rect bounds = new Rect();
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        bounds.set(
                position[0],
                position[1],
                position[0] + view.getWidth(),
                position[1] + view.getHeight());
        if (view.isClickable()
                && Math.abs(bounds.left - highlight.left) < 24
                && Math.abs(bounds.top - highlight.top) < 24
                && Math.abs(bounds.right - highlight.right) < 24
                && Math.abs(bounds.bottom - highlight.bottom) < 24) return label(view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                String found = match(((ViewGroup) view).getChildAt(i), highlight);
                if (!found.isEmpty()) return found;
            }
        return "";
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
