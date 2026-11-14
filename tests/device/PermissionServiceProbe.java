// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
 * Actual service input through the system camera permission dialog and the returning app sheets.
 */
final class PermissionServiceProbe {
    private final Probe probe;
    private UiAutomation automation;
    private MainActivity screen;
    private String service, keyboard;
    private File directory;
    private final JSONArray pages = new JSONArray(), failures = new JSONArray();
    private int assertions;
    private static final String[] DECISIONS = {
        "permission_allow_foreground_only_button",
        "permission_allow_one_time_button",
        "permission_deny_button"
    };

    PermissionServiceProbe(Probe probe) {
        this.probe = probe;
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        probe.runOnMainSync(
                () -> {
                    try {
                        result.set(action.get());
                    } catch (Throwable failure) {
                        problem.set(failure);
                    }
                });
        if (problem.get() != null) throw new AssertionError(problem.get());
        return result.get();
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) failures.put(label);
    }

    private void require(boolean condition, String label) {
        check(condition, label);
        if (!condition) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(30);
    }

    String run(Context context, Bundle arguments) throws Exception {
        if (!android.os.Build.HARDWARE.contains("ranchu"))
            throw new AssertionError("Emulator only");
        service = arguments.getString("accessibilityService", "");
        if (!service.equals("talkback") && !service.equals("switch"))
            throw new AssertionError("Select an actual accessibility service");
        keyboard = arguments.getString("switchKeyboard", "");
        if (service.equals("switch") && !keyboard.matches("/dev/input/event[0-9]+"))
            throw new AssertionError("Select the native QA switch keyboard");
        automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |=
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        android.view.accessibility.AccessibilityManager manager =
                context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        String component = service.equals("talkback") ? "TalkBackService" : "SwitchAccessService";
        check(
                manager.getEnabledAccessibilityServiceList(-1).stream()
                        .anyMatch(enabled -> enabled.getId().contains(component)),
                "the selected actual accessibility service remains enabled");
        check(
                context.checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED,
                "camera permission starts ungranted for the actual system dialog");
        PocketController controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured()) throw new AssertionError("No actual room may be configured");
        String language = controller.settings.language();
        String draft = controller.draft();
        controller.settings.language("en");
        directory = new File(context.getExternalCacheDir(), "permission-service");
        directory.mkdirs();
        boolean complete = false;
        try {
            screen =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(Intent.ACTION_MAIN)
                                            .setClass(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(
                    () -> {
                        screen.navigate("pairing");
                        return null;
                    });
            Thread.sleep(400);
            require(
                    activate(
                            node -> label(node).equals("Scan pairing QR"), this::permissionVisible),
                    "actual service activation opens the system camera permission dialog");
            if (!permissionVisible())
                throw new AssertionError("No actual permission controller window");
            scanPermission("camera-deny");
            require(
                    activate(
                            node -> id(node).equals(DECISIONS[2]),
                            () -> contains("Open app permissions")),
                    "actual service denial returns to the app permission explanation");
            check(
                    context.checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED,
                    "the denied decision does not grant camera permission");
            require(
                    activate(
                            node -> label(node).equals("Close panel"),
                            () -> contains("Scan pairing QR")),
                    "actual service dismisses the explanation and returns to pairing");
            require(
                    activate(
                            node -> label(node).equals("Scan pairing QR"), this::permissionVisible),
                    "pairing can request permission again after a single denial");
            if (!permissionVisible())
                throw new AssertionError("The repeated request did not open a system dialog");
            scanPermission("camera-allow");
            require(
                    activate(
                            node -> id(node).equals(DECISIONS[0]),
                            () -> contains("Room pairing QR camera view")),
                    "actual service grants foreground permission and opens the camera sheet");
            check(
                    context.checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED,
                    "the system records the actual foreground camera grant");
            require(
                    activate(
                            node -> label(node).equals("Close panel"),
                            () -> contains("Pairing link")),
                    "actual service closes the camera sheet and returns to the native form");
            check(
                    controller.draft().equals(draft)
                            && !controller.configured()
                            && !controller.snapshot().trying,
                    "the permission round trip preserves draft, identity and room isolation");
            complete = true;
        } finally {
            try {
                JSONObject report =
                        new JSONObject()
                                .put("schema", 2)
                                .put("complete", complete)
                                .put("service", service)
                                .put("assertions", assertions)
                                .put("pages", pages)
                                .put("failures", failures)
                                .put(
                                        "driver",
                                        "actual service raw input; native permission controller");
                Files.write(
                        new File(directory, "result.json").toPath(),
                        report.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally {
                controller.settings.language(language);
                if (screen != null)
                    main(
                            () -> {
                                screen.finish();
                                return null;
                            });
            }
        }
        if (failures.length() > 0) throw new AssertionError(failures.toString());
        return "PASS actual permission service; " + assertions + " checks";
    }

    private boolean permissionVisible() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try {
            return root.getPackageName() != null
                    && root.getPackageName().toString().endsWith("permissioncontroller");
        } finally {
            root.recycle();
        }
    }

    private boolean contains(String text) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try {
            return contains(root, text, 0);
        } finally {
            root.recycle();
        }
    }

    private boolean contains(AccessibilityNodeInfo node, String text, int depth) {
        for (CharSequence value :
                new CharSequence[] {
                    node.getText(), node.getContentDescription(), node.getHintText()
                }) if (value != null && value.toString().contains(text)) return true;
        if (depth >= 32) return false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (contains(child, text, depth + 1)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private static String label(AccessibilityNodeInfo node) {
        return ScreenReaderProbe.spokenLabel(node, 0);
    }

    private static String id(AccessibilityNodeInfo node) {
        String value = node.getViewIdResourceName();
        return value == null ? "" : value.substring(value.lastIndexOf('/') + 1);
    }

    private static String decision(AccessibilityNodeInfo node) {
        String resource = id(node);
        return resource.equals("permission_deny_and_dont_ask_again_button")
                ? DECISIONS[2]
                : resource;
    }

    private void raw(String arguments) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor =
                        automation.executeShellCommand(
                                "su 0 /data/local/tmp/pocket-a11y-input " + arguments);
                FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (stream.read(buffer) != -1) {}
        }
    }

    private AccessibilityNodeInfo next() throws Exception {
        if (service.equals("talkback")) raw("/dev/input/event1 right");
        else raw(keyboard + " key 30");
        Thread.sleep(200);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return null;
        try {
            if (service.equals("talkback"))
                return root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            Bitmap image = automation.takeScreenshot();
            if (image == null) throw new AssertionError("No actual scan screenshot");
            try {
                return matching(root, SwitchUiProbe.highlight(image));
            } finally {
                image.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo matching(AccessibilityNodeInfo node, Rect highlight) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser()
                && node.isEnabled()
                && node.isClickable()
                && Math.abs(bounds.left - highlight.left) < 24
                && Math.abs(bounds.top - highlight.top) < 24
                && Math.abs(bounds.right - highlight.right) < 24
                && Math.abs(bounds.bottom - highlight.bottom) < 24)
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo found = matching(child, highlight);
                if (found != null) return found;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private boolean activate(Predicate<AccessibilityNodeInfo> selected, BooleanSupplier completed)
            throws Exception {
        for (int step = 0; step < 100; step++) {
            AccessibilityNodeInfo node = next();
            if (node == null) continue;
            try {
                if (!node.isEnabled() || !selected.test(node)) continue;
                if (service.equals("talkback")) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    Rect display =
                            main(
                                    () ->
                                            screen.getWindowManager()
                                                    .getMaximumWindowMetrics()
                                                    .getBounds());
                    raw(
                            "/dev/input/event1 activate "
                                    + (bounds.centerX() * 32767 / display.width())
                                    + " "
                                    + (bounds.centerY() * 32767 / display.height()));
                } else raw(keyboard + " key 48");
                await(completed, 1500);
                if (service.equals("switch") && !completed.getAsBoolean()) {
                    raw(keyboard + " key 48");
                    await(completed, 3000);
                }
                return completed.getAsBoolean();
            } finally {
                node.recycle();
            }
        }
        return false;
    }

    private void scanPermission(String name) throws Exception {
        JSONArray trace = new JSONArray();
        Set<String> reached = new LinkedHashSet<>(), unique = new LinkedHashSet<>();
        List<Integer> order = new ArrayList<>();
        int stale = 0;
        for (int step = 0; step < 70 && stale < 8; step++) {
            AccessibilityNodeInfo node = next();
            if (node == null) {
                stale++;
                continue;
            }
            try {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                String resource = decision(node), text = label(node);
                boolean system =
                        node.getPackageName() != null
                                && node.getPackageName()
                                        .toString()
                                        .endsWith("permissioncontroller");
                check(system, name + " service stays in the system permission window");
                JSONObject stop =
                        new JSONObject()
                                .put("label", text)
                                .put("id", id(node))
                                .put("decision", resource)
                                .put("package", String.valueOf(node.getPackageName()))
                                .put("bounds", bounds.toShortString());
                if (unique.add(resource + ":" + bounds.toShortString() + ":" + text)) {
                    stale = 0;
                    trace.put(stop);
                    Bitmap image = automation.takeScreenshot();
                    if (image != null) {
                        try (FileOutputStream output =
                                new FileOutputStream(
                                        new File(directory, name + "-" + unique.size() + ".png"))) {
                            image.compress(Bitmap.CompressFormat.PNG, 100, output);
                        } finally {
                            image.recycle();
                        }
                    }
                } else stale++;
                for (int n = 0; n < DECISIONS.length; n++)
                    if (resource.equals(DECISIONS[n])) {
                        reached.add(resource);
                        if (order.isEmpty() || order.get(order.size() - 1) != n) order.add(n);
                    }
            } finally {
                node.recycle();
            }
        }
        for (String decision : DECISIONS)
            check(reached.contains(decision), name + " reaches " + decision);
        boolean ordered = order.size() >= DECISIONS.length;
        for (int i = 1; i < Math.min(order.size(), DECISIONS.length); i++)
            if (order.get(i) != (order.get(i - 1) + 1) % DECISIONS.length) ordered = false;
        check(ordered, name + " actual decision focus follows the visible order");
        JSONObject page =
                new JSONObject()
                        .put("page", name)
                        .put("trace", trace)
                        .put("order", new JSONArray(order));
        pages.put(page);
        Files.write(
                new File(directory, name + ".json").toPath(),
                page.toString(2).getBytes(StandardCharsets.UTF_8));
        if (reached.size() != DECISIONS.length || !ordered)
            throw new AssertionError(
                    "The actual system permission decisions were not all scanned in order");
    }
}
