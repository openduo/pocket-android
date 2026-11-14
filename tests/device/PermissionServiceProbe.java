// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation.ActivityMonitor;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Actual service input through native permission dialogs and external document picker round trips.
 */
final class PermissionServiceProbe {
    private final Probe probe;
    private UiAutomation automation;
    private MainActivity screen;
    private String service, keyboard, flow;
    private Context context;
    private PocketController controller;
    private boolean recreate;
    private ActivityMonitor deferredMonitor;
    private MainActivity deferredPrevious;
    private Rect deferredBefore;
    private String deferredName;
    private final List<String> notices = java.util.Collections.synchronizedList(new ArrayList<>());
    private File directory;
    private final JSONArray pages = new JSONArray(), failures = new JSONArray();
    private int assertions;
    private static final String[] DECISIONS = {
        "permission_allow_foreground_only_button",
        "permission_allow_one_time_button",
        "permission_deny_button"
    };
    private static final String[] NOTIFICATION_DECISIONS = {
        "permission_allow_button", "permission_deny_button"
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

    private void onState(Runnable action) throws Exception {
        java.lang.reflect.Field field = PocketController.class.getDeclaredField("state");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(controller))
                .submit(action)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) failures.put(label);
    }

    private void require(boolean condition, String label) {
        check(condition, label);
        if (!condition) {
            Bitmap image = automation.takeScreenshot();
            if (image != null && directory != null) {
                try (FileOutputStream output =
                        new FileOutputStream(
                                new File(directory, "failure-" + assertions + ".png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException ignored) {
                } finally {
                    image.recycle();
                }
            }
            throw new AssertionError(label);
        }
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(30);
    }

    String run(Context context, Bundle arguments) throws Exception {
        this.context = context;
        if (!android.os.Build.HARDWARE.contains("ranchu"))
            throw new AssertionError("Emulator only");
        service = arguments.getString("accessibilityService", "");
        if (!service.equals("talkback") && !service.equals("switch"))
            throw new AssertionError("Select an actual accessibility service");
        keyboard = arguments.getString("switchKeyboard", "");
        flow = arguments.getString("permissionFlow", "camera");
        recreate = "1".equals(arguments.getString("recreateExternal"));
        if (!java.util.Arrays.asList("camera", "microphone", "location", "notifications", "picker")
                .contains(flow)) throw new AssertionError("Select a supported native flow");
        if (service.equals("switch") && !keyboard.matches("/dev/input/event[0-9]+"))
            throw new AssertionError("Select the native QA switch keyboard");
        automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |=
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        automation.setOnAccessibilityEventListener(
                event -> {
                    if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                            && event.getPackageName() != null
                            && context.getPackageName().contentEquals(event.getPackageName()))
                        for (CharSequence text : event.getText())
                            if (text != null) notices.add(text.toString());
                });
        android.view.accessibility.AccessibilityManager manager =
                context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        String component = service.equals("talkback") ? "TalkBackService" : "SwitchAccessService";
        check(
                manager.getEnabledAccessibilityServiceList(-1).stream()
                        .anyMatch(enabled -> enabled.getId().contains(component)),
                "the selected actual accessibility service remains enabled");
        controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured()) throw new AssertionError("No actual room may be configured");
        String language = controller.settings.language();
        String draft = controller.draft();
        boolean voice = controller.settings.voiceInput(),
                output = controller.settings.ambientOutput(),
                answers = controller.settings.answerNotifications();
        SharedPreferences arrivals =
                context.getSharedPreferences("place.arrivals", Context.MODE_PRIVATE);
        Map<String, ?> arrivalValues = arrivals.getAll();
        boolean trial = flow.equals("microphone") || flow.equals("picker");
        controller.settings.language("en");
        directory = new File(context.getExternalCacheDir(), "permission-service");
        directory.mkdirs();
        boolean complete = false;
        try {
            if (!flow.equals("picker"))
                check(
                        !granted(permission()),
                        flow + " permission starts ungranted for the actual system dialog");
            if (flow.equals("notifications")) controller.settings.answerNotifications(false);
            if (flow.equals("location")) {
                controller.arrivalsEnabled(false);
                await(() -> !controller.arrivalsEnabled(), 3000);
            }
            if (trial) {
                controller.setReplyAudio(false);
                controller.startTrying();
                await(
                        () ->
                                controller.snapshot().trying
                                        && controller.snapshot().connection
                                                == PocketController.Connection.ONLINE,
                        6000);
                require(
                        controller.snapshot().trying,
                        "the native flow uses an isolated prepared room");
                controller.setVoiceInput(flow.equals("microphone"));
                controller.draft("QA native flow draft");
                await(
                        () ->
                                controller.draft().equals("QA native flow draft")
                                        && controller.snapshot().voiceInput
                                                == flow.equals("microphone"),
                        3000);
            }
            screen =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(Intent.ACTION_MAIN)
                                            .setClass(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            navigate(flow.equals("camera") ? "pairing" : trial ? "conversation" : "settings");
            if (recreate && flow.equals("camera"))
                main(
                        () -> {
                            EditText code = screen.findViewById(dev.openduo.pocket.R.id.pair_code);
                            code.setText("QA recreation form");
                            EditText link = screen.findViewById(dev.openduo.pocket.R.id.pair_link);
                            link.setText("QA recreation draft");
                            link.requestFocus();
                            link.setSelection(3, 9);
                            screen.ui().hideKeyboard(link);
                            return null;
                        });
            Thread.sleep(400);
            if (flow.equals("picker")) picker();
            else permissions();
            if (trial) {
                controller.stopTrying();
                await(
                        () -> !controller.snapshot().trying && controller.draft().equals(draft),
                        5000);
            }
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
                                .put("flow", flow)
                                .put("recreateExternal", recreate)
                                .put("assertions", assertions)
                                .put("pages", pages)
                                .put("notices", new JSONArray(notices))
                                .put("failures", failures)
                                .put(
                                        "driver",
                                        "actual service raw input; native permission controller or"
                                            + " DocumentsUI; system Back for picker cancellation");
                Files.write(
                        new File(directory, "result.json").toPath(),
                        report.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally {
                automation.setOnAccessibilityEventListener(null);
                if (deferredMonitor != null) {
                    probe.removeMonitor(deferredMonitor);
                    deferredMonitor = null;
                }
                if (trial) controller.stopTrying();
                controller.settings.language(language);
                if (trial) {
                    controller.setVoiceInput(voice);
                    controller.setReplyAudio(output);
                    await(
                            () ->
                                    !controller.snapshot().trying
                                            && controller.draft().equals(draft)
                                            && controller.snapshot().voiceInput == voice
                                            && !controller.snapshot().holdLive,
                            5000);
                    if (controller.snapshot().trying
                            || !controller.draft().equals(draft)
                            || controller.snapshot().voiceInput != voice
                            || controller.snapshot().holdLive)
                        throw new AssertionError(
                                "Native flow cleanup did not restore the original room state");
                }
                if (flow.equals("notifications")) controller.settings.answerNotifications(answers);
                if (flow.equals("location")) {
                    controller.arrivalsEnabled(false);
                    onState(
                            () -> {
                                if (controller.arrivalsEnabled())
                                    throw new AssertionError(
                                            "Arrival monitoring did not stop before preference"
                                                    + " restoration");
                                restore(arrivals, arrivalValues);
                                if (!arrivals.getAll().equals(arrivalValues))
                                    throw new AssertionError(
                                            "Native flow cleanup did not restore arrival"
                                                    + " preferences");
                            });
                }
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

    private static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else throw new AssertionError("Unexpected arrival preference type");
        }
        if (!edit.commit()) throw new AssertionError("Could not restore arrival preferences");
    }

    private String permission() {
        if (flow.equals("microphone")) return Manifest.permission.RECORD_AUDIO;
        if (flow.equals("location")) return Manifest.permission.ACCESS_FINE_LOCATION;
        if (flow.equals("notifications")) return Manifest.permission.POST_NOTIFICATIONS;
        return Manifest.permission.CAMERA;
    }

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void navigate(String page) {
        main(
                () -> {
                    screen.navigate(page);
                    return null;
                });
    }

    private String entry() {
        if (flow.equals("microphone")) return "Hold to Talk";
        if (flow.equals("location")) return "Let the room know where I arrived";
        if (flow.equals("notifications")) return "Show answer notifications";
        return "Scan pairing QR";
    }

    private void permissions() throws Exception {
        String entry = entry();
        require(
                activate(node -> label(node).startsWith(entry), this::permissionVisible),
                "actual service opens the system " + flow + " permission dialog");
        if (recreate) rotateExternal(flow + "-deny-rotation");
        scanPermission(flow + "-deny");
        require(
                activate(
                        node -> decision(node).equals(DECISIONS[2]),
                        () -> contains("Open app permissions")),
                "actual service denial returns to the app permission explanation");
        check(!granted(permission()), "denial leaves the requested permission ungranted");
        require(
                activate(
                        node -> label(node).equals("Close panel"),
                        () -> !contains("Open app permissions")),
                "actual service dismisses the explanation and returns to the page");
        check(
                !controller.snapshot().holdLive,
                "denial does not leave a pending microphone capture");
        if (flow.equals("location")) {
            require(
                    activate(
                            node -> label(node).startsWith(entry),
                            () -> !controller.arrivalsEnabled()),
                    "actual service disables the arrival preference before requesting again");
        }
        String retry = flow.equals("notifications") ? "Notifications and background sync" : entry;
        require(
                activate(node -> label(node).startsWith(retry), this::permissionVisible),
                "actual service can request again after a single denial");
        if (recreate) rotateExternal(flow + "-allow-rotation");
        scanPermission(flow + "-allow");
        if (flow.equals("location")) {
            require(
                    activate(
                            node -> id(node).equals("permission_location_accuracy_radio_coarse"),
                            () -> checked("permission_location_accuracy_radio_coarse")),
                    "actual service selects approximate location in the native dialog");
        }
        String allow = flow.equals("notifications") ? NOTIFICATION_DECISIONS[0] : DECISIONS[0];
        require(
                activate(node -> id(node).equals(allow), () -> !permissionVisible()),
                "actual service grants the selected native permission");
        if (recreate) {
            await(() -> main(() -> screen.hasWindowFocus()), 3000);
            await(() -> notices.contains("Permission allowed. Try the action again."), 2000);
            require(
                    notices.contains("Permission allowed. Try the action again."),
                    "the recreated Activity exposes a retry prompt after an actual permission"
                            + " grant");
            require(
                    !contains("Permission is needed for this feature"),
                    "a real grant delivered to the replacement Activity is not reported as a"
                            + " denial");
        }
        if (flow.equals("location")) {
            if (!recreate) await(() -> contains("Open app permissions"), 3000);
            check(
                    granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                            && !granted(Manifest.permission.ACCESS_FINE_LOCATION),
                    "Android records approximate permission without a precise grant");
            check(
                    controller.arrivalsNeedPermission(),
                    "arrival remains paused because its accurate dwell rule requires precise and"
                            + " background access");
            if (!recreate)
                require(
                        activate(
                                node -> label(node).equals("Close panel"),
                                () -> !contains("Open app permissions")),
                        "the approximate-only decision returns to settings without starting"
                                + " visits");
            check(!controller.configured(), "the unpaired location test cannot send a room visit");
            recordReturn("location-approximate-return");
            require(
                    activate(
                            node -> label(node).startsWith(entry),
                            () -> !controller.arrivalsEnabled()),
                    "actual service switches off the approximate-only arrival preference");
            require(
                    activate(node -> label(node).startsWith(entry), this::permissionVisible),
                    "a precise location upgrade still opens a native dialog when coarse access is"
                            + " already granted");
            boolean upgrade = hasId("permission_upgrade_button");
            scanPermission(upgrade ? "location-upgrade" : "location-precise");
            if (!upgrade && hasId("permission_location_accuracy_radio_fine"))
                require(
                        activate(
                                node -> id(node).equals("permission_location_accuracy_radio_fine"),
                                () -> checked("permission_location_accuracy_radio_fine")),
                        "actual service selects precise location for the upgrade");
            require(
                    activate(
                            node ->
                                    id(node).equals(
                                                    upgrade
                                                            ? "permission_upgrade_button"
                                                            : DECISIONS[0]),
                            () -> !permissionVisible()),
                    "actual service authorizes the precision upgrade");
            check(
                    granted(Manifest.permission.ACCESS_FINE_LOCATION),
                    "Android records the precise location upgrade");
            await(() -> contains("Always allow location access"), 3000);
            require(
                    contains("Always allow location access"),
                    "foreground precision succeeds and the app explains the separate background"
                            + " requirement");
            require(
                    activate(
                            node -> label(node).equals("Close panel"),
                            () -> !contains("Close panel")),
                    "actual service closes the background permission guidance");
        } else {
            check(granted(permission()), "Android records the actual " + flow + " grant");
        }
        if (flow.equals("camera")) {
            if (recreate) {
                check(
                        !contains("Room pairing QR camera view"),
                        "recreation does not resume the old scanner activation");
                require(
                        activate(
                                node -> label(node).equals("Scan pairing QR"),
                                () -> contains("Room pairing QR camera view")),
                        "the actual service can retry scanning after the old Activity's permission"
                                + " intent is cancelled");
            }
            await(() -> contains("Room pairing QR camera view"), 3000);
            require(
                    contains("Room pairing QR camera view"),
                    "the granted action opens the actual QR camera sheet");
            require(
                    activate(
                            node -> label(node).equals("Close panel"),
                            () -> contains("Pairing link")),
                    "actual service closes the camera sheet and returns to the form");
        } else if (flow.equals("microphone")) {
            Thread.sleep(400);
            check(
                    !controller.snapshot().holdLive,
                    "permission window focus loss cancels the old recording activation");
            int rows = controller.snapshot().rows.size();
            require(
                    activate(
                            node -> label(node).startsWith(entry),
                            () -> controller.snapshot().holdLive),
                    "a fresh actual service activation starts microphone capture after permission");
            require(
                    activate(
                            node ->
                                    label(node)
                                            .equals(
                                                    "Attach a photo, take a photo, add a file or"
                                                            + " location"),
                            () -> contains("Close panel")),
                    "actual service opens the attachment sheet while recording");
            await(() -> !controller.snapshot().holdLive, 3000);
            check(
                    !controller.snapshot().holdLive,
                    "opening another window cancels and releases microphone capture");
            require(
                    activate(
                            node -> label(node).equals("Close panel"),
                            () -> !contains("Close panel")),
                    "actual service returns to the composer after capture cancellation");
            check(
                    controller.snapshot().rows.size() == rows
                            && controller.snapshot().outbox.isEmpty()
                            && controller.draft().equals("QA native flow draft"),
                    "cancelled permission/capture round trip sends nothing and preserves the"
                            + " draft");
        } else if (flow.equals("notifications")) {
            check(
                    controller.settings.answerNotifications(),
                    "the user's answer notification preference survives denial and later system"
                            + " authorization");
            check(
                    !contains("Open app permissions"),
                    "the successful retry does not leave a stale denial sheet");
            if (recreate)
                check(
                        !contains("System notifications are off"),
                        "settings refreshes the permission status after the replacement Activity"
                                + " receives the grant");
        }
        recordReturn(flow + "-return");
    }

    private boolean checked(String resource) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = permissionNodes(root, resource);
            boolean checked = false;
            for (AccessibilityNodeInfo node : nodes) {
                checked |= node.isChecked();
                node.recycle();
            }
            return checked;
        } finally {
            root.recycle();
        }
    }

    private boolean hasId(String resource) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = permissionNodes(root, resource);
            boolean found = !nodes.isEmpty();
            for (AccessibilityNodeInfo node : nodes) node.recycle();
            return found;
        } finally {
            root.recycle();
        }
    }

    private static List<AccessibilityNodeInfo> permissionNodes(
            AccessibilityNodeInfo root, String resource) {
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(
                        "com.android.permissioncontroller:id/" + resource);
        if (nodes.isEmpty())
            nodes =
                    root.findAccessibilityNodeInfosByViewId(
                            "com.google.android.permissioncontroller:id/" + resource);
        return nodes;
    }

    private void picker() throws Exception {
        EditText editor = main(() -> screen.findViewById(dev.openduo.pocket.R.id.composer));
        main(
                () -> {
                    editor.requestFocus();
                    editor.setSelection(3, 9);
                    screen.ui().hideKeyboard(editor);
                    return null;
                });
        int rows = controller.snapshot().rows.size();
        for (int round = 0; round < 2; round++) {
            require(
                    activate(
                            node ->
                                    label(node)
                                            .equals(
                                                    "Attach a photo, take a photo, add a file or"
                                                            + " location"),
                            () -> contains("File")),
                    "actual service opens the attachment menu before native file selection");
            require(
                    activate(node -> label(node).equals("File"), this::documentsVisible),
                    "actual service launches the real external DocumentsUI picker");
            if (recreate) rotateExternal("picker-rotation-" + round);
            recordReturn("picker-open-" + round);
            check(
                    controller.draft().equals("QA native flow draft"),
                    "opening the external picker preserves the business draft");
            // A platform Back key cancels the real external Activity; no ActivityResult is
            // fabricated.
            try (android.os.ParcelFileDescriptor output =
                            automation.executeShellCommand("input keyevent 4");
                    FileInputStream input = new FileInputStream(output.getFileDescriptor())) {
                while (input.read() != -1) {}
            }
            await(() -> !documentsVisible() && contains("QA native flow draft"), 5000);
            finishDeferredRotation();
            require(
                    !documentsVisible() && contains("QA native flow draft"),
                    "system Back returns from DocumentsUI to the existing composer");
            check(
                    main(
                            () -> {
                                EditText current =
                                        screen.findViewById(dev.openduo.pocket.R.id.composer);
                                return current.getSelectionStart() == 3
                                        && current.getSelectionEnd() == 9;
                            }),
                    "external cancellation preserves the native editor selection");
            check(
                    controller.draft().equals("QA native flow draft")
                            && controller.snapshot().rows.size() == rows
                            && controller.snapshot().chips.isEmpty()
                            && controller.snapshot().imports.isEmpty()
                            && controller.snapshot().outbox.isEmpty(),
                    "picker cancellation adds no messages, imports, attachments or pending sends");
            recordReturn("picker-cancel-" + round);
        }
        require(
                activate(
                        node ->
                                label(node)
                                        .equals(
                                                "Attach a photo, take a photo, add a file or"
                                                        + " location"),
                        () -> contains("File")),
                "the actual service can reach and reopen attachments after two cancellations");
        require(
                activate(node -> label(node).equals("Close panel"), () -> !contains("Close panel")),
                "the actual service closes the restored attachment sheet");
    }

    private boolean documentsVisible() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try {
            return root.getPackageName() != null
                    && root.getPackageName().toString().endsWith("documentsui");
        } finally {
            root.recycle();
        }
    }

    private void rotateExternal(String name) throws Exception {
        ActivityMonitor monitor = probe.addMonitor(MainActivity.class.getName(), null, false);
        MainActivity previous = screen;
        Rect before = main(() -> previous.getWindowManager().getMaximumWindowMetrics().getBounds());
        int rotation = main(() -> previous.getWindowManager().getDefaultDisplay().getRotation());
        try {
            try (android.os.ParcelFileDescriptor output =
                            automation.executeShellCommand(
                                    "settings put system user_rotation " + ((rotation + 1) % 4));
                    FileInputStream input = new FileInputStream(output.getFileDescriptor())) {
                while (input.read() != -1) {}
            }
            MainActivity replacement =
                    (MainActivity)
                            monitor.waitForActivityWithTimeout(flow.equals("picker") ? 1500 : 7000);
            if (replacement == null && flow.equals("picker")) {
                Bitmap image = automation.takeScreenshot();
                require(image != null, name + " has an actual external display screenshot");
                try {
                    require(
                            (before.width() > before.height())
                                    != (image.getWidth() > image.getHeight()),
                            name + " rotates the actual external display before returning");
                } finally {
                    image.recycle();
                }
                require(documentsVisible(), name + " keeps the real document picker visible");
                deferredMonitor = monitor;
                deferredPrevious = previous;
                deferredBefore = before;
                deferredName = name;
                pages.put(new JSONObject().put("page", name).put("deferredUntilReturn", true));
                return;
            }
            require(
                    replacement != null && replacement != previous,
                    name + " creates a replacement shipping Activity behind the external window");
            screen = replacement;
            probe.waitForIdleSync();
            Thread.sleep(400);
            Rect display =
                    main(() -> screen.getWindowManager().getMaximumWindowMetrics().getBounds());
            Rect window =
                    main(() -> screen.getWindowManager().getCurrentWindowMetrics().getBounds());
            check(main(previous::isDestroyed), name + " destroys the previous Activity");
            require(
                    (before.width() > before.height()) != (display.width() > display.height()),
                    name + " rotates the actual display");
            check(
                    display.width() == window.width() && display.height() == window.height(),
                    name + " fills the rotated display without letterboxing");
            require(
                    flow.equals("picker") ? documentsVisible() : permissionVisible(),
                    name + " preserves the actual unanswered external window");
            if (flow.equals("camera"))
                check(
                        main(
                                () -> {
                                    EditText code =
                                            screen.findViewById(dev.openduo.pocket.R.id.pair_code);
                                    EditText link =
                                            screen.findViewById(dev.openduo.pocket.R.id.pair_link);
                                    return code.getText().toString().equals("QA recreation form")
                                            && link.getText()
                                                    .toString()
                                                    .equals("QA recreation draft")
                                            && link.getSelectionStart() == 3
                                            && link.getSelectionEnd() == 9;
                                }),
                        name + " retains the native unsent pairing value and selection");
            pages.put(
                    new JSONObject()
                            .put("page", name)
                            .put("display", display.toShortString())
                            .put("window", window.toShortString())
                            .put("rotation", (rotation + 1) % 4));
        } finally {
            if (monitor != deferredMonitor) probe.removeMonitor(monitor);
        }
    }

    private void finishDeferredRotation() throws Exception {
        if (deferredMonitor == null) return;
        try {
            MainActivity replacement =
                    (MainActivity) deferredMonitor.waitForActivityWithTimeout(7000);
            require(
                    replacement != null && replacement != deferredPrevious,
                    deferredName
                            + " recreates the background Activity when the external picker"
                            + " returns");
            screen = replacement;
            probe.waitForIdleSync();
            Rect display =
                    main(() -> screen.getWindowManager().getMaximumWindowMetrics().getBounds());
            Rect window =
                    main(() -> screen.getWindowManager().getCurrentWindowMetrics().getBounds());
            check(
                    main(deferredPrevious::isDestroyed),
                    deferredName + " destroys the prior Activity");
            check(
                    (deferredBefore.width() > deferredBefore.height())
                            != (display.width() > display.height()),
                    deferredName + " returns in the rotated display orientation");
            check(
                    display.width() == window.width() && display.height() == window.height(),
                    deferredName + " fills the actual returned display");
            pages.put(
                    new JSONObject()
                            .put("page", deferredName + "-returned")
                            .put("display", display.toShortString())
                            .put("window", window.toShortString()));
        } finally {
            probe.removeMonitor(deferredMonitor);
            deferredMonitor = null;
        }
    }

    private void recordReturn(String name) throws Exception {
        AccessibilityNodeInfo node = null;
        for (int step = 0; step < 16 && node == null; step++) node = next();
        require(
                node != null,
                name + " actual service has a reachable focus after the window transition");
        if (node == null) return;
        try {
            String pkg = String.valueOf(node.getPackageName());
            check(
                    name.startsWith("picker-open")
                            ? pkg.endsWith("documentsui")
                            : pkg.equals(screen.getPackageName()),
                    name + " actual service focus belongs to the returned window");
            pages.put(
                    new JSONObject()
                            .put("page", name)
                            .put("package", pkg)
                            .put("label", label(node))
                            .put("id", id(node)));
            Bitmap image = automation.takeScreenshot();
            if (image != null) {
                try (FileOutputStream output =
                        new FileOutputStream(new File(directory, name + ".png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, output);
                } finally {
                    image.recycle();
                }
            }
        } finally {
            node.recycle();
        }
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
        return next(false);
    }

    private AccessibilityNodeInfo next(boolean backwards) throws Exception {
        if (service.equals("talkback"))
            raw("/dev/input/event1 " + (backwards ? "left " : "right ") + rotation());
        else raw(keyboard + " key 30");
        Thread.sleep(200);
        return focused();
    }

    private int rotation() {
        return main(() -> screen.getWindowManager().getDefaultDisplay().getRotation());
    }

    private AccessibilityNodeInfo focused() {
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
        JSONArray trace = new JSONArray();
        Set<String> visited = new LinkedHashSet<>();
        pages.put(new JSONObject().put("page", "activation-" + assertions).put("trace", trace));
        for (int step = 0; step < 100; step++) {
            if (service.equals("switch")
                    && flow.equals("camera")
                    && (step == 30 || step == 60)
                    && !permissionVisible()
                    && !contains("Close panel")) {
                raw(keyboard + " key 32");
                Thread.sleep(200);
            }
            if (service.equals("switch")
                    && (flow.equals("location") || flow.equals("notifications"))
                    && (step == 30 || step == 60)
                    && !permissionVisible()
                    && !contains("Close panel")) {
                raw(keyboard + " key 46");
                Thread.sleep(200);
            }
            AccessibilityNodeInfo node =
                    step == 0 ? focused() : next(step >= 50 && !permissionVisible());
            if (node == null) continue;
            try {
                if (visited.add(id(node) + ":" + label(node)))
                    trace.put(
                            new JSONObject()
                                    .put("label", label(node))
                                    .put("id", id(node))
                                    .put("package", String.valueOf(node.getPackageName())));
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
                                    + (bounds.centerY() * 32767 / display.height())
                                    + " "
                                    + rotation());
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
        String[] decisions =
                flow.equals("notifications")
                        ? NOTIFICATION_DECISIONS
                        : name.equals("location-upgrade")
                                ? new String[] {
                                    "permission_upgrade_button", "permission_no_upgrade_button"
                                }
                                : DECISIONS;
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
                for (int n = 0; n < decisions.length; n++)
                    if (resource.equals(decisions[n])) {
                        reached.add(resource);
                        if (order.isEmpty() || order.get(order.size() - 1) != n) order.add(n);
                    }
            } finally {
                node.recycle();
            }
        }
        for (String decision : decisions)
            check(reached.contains(decision), name + " reaches " + decision);
        boolean ordered = order.size() >= decisions.length;
        for (int i = 1; i < Math.min(order.size(), decisions.length); i++)
            if (order.get(i) != (order.get(i - 1) + 1) % decisions.length) ordered = false;
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
        if (reached.size() != decisions.length || !ordered)
            throw new AssertionError(
                    "The actual system permission decisions were not all scanned in order");
    }
}
