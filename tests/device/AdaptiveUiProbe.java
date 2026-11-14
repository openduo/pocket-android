// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shipping Activity geometry on emulator windows; screenshots and faults are retained per case. */
final class AdaptiveUiProbe {
    private final Probe probe;
    private final JSONArray failures = new JSONArray(), pages = new JSONArray();
    private MainActivity screen;
    private PocketController controller;
    private int assertions;

    AdaptiveUiProbe(Probe probe) {
        this.probe = probe;
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        probe.runOnMainSync(
                () -> {
                    try {
                        value.set(action.get());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
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
            throw new AssertionError("The UI matrix is restricted to an isolated Android emulator");
        controller = PocketController.get(context);
        await(() -> controller.snapshot() != null, 8000);
        if (controller.configured())
            throw new AssertionError("The emulator UI matrix must not own an actual room");
        String language = controller.settings.language(),
                appearance = controller.settings.appearance();
        boolean output = controller.settings.ambientOutput(),
                voice = controller.settings.voiceInput();
        controller.settings.language(arguments.getString("uiLanguage", "en"));
        controller.settings.appearance(arguments.getString("uiTheme", "dark"));
        controller.setReplyAudio(false);
        File directory = new File(context.getExternalCacheDir(), "ui-matrix");
        directory.mkdirs();
        JSONObject report = new JSONObject();
        try {
            screen =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(Intent.ACTION_MAIN)
                                            .setClass(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(
                    () -> {
                        screen.getWindow().addFlags(128);
                        return null;
                    });
            controller.startTrying();
            await(
                    () ->
                            controller.snapshot().trying
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    6000);
            check(controller.snapshot().trying, "UI cases use only the disposable room");
            controller.setVoiceInput(false);
            for (int turn = 0; turn < 4; turn++) {
                int before = controller.snapshot().rows.size();
                controller.draft("QA prepared UI content " + turn);
                controller.sendDraft();
                await(
                        () ->
                                controller.snapshot().rows.size() >= before + 2
                                        && controller.snapshot().outbox.isEmpty(),
                        8000);
            }
            for (String destination :
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
                navigate(destination);
                inspect(destination, screen.getWindow().getDecorView());
                capture(directory, destination);
                for (ScrollView scroll : main(() -> scrolls(screen.getWindow().getDecorView()))) {
                    main(
                            () -> {
                                scroll.fullScroll(View.FOCUS_DOWN);
                                return null;
                            });
                }
                settle();
                inspect(destination + "-end", screen.getWindow().getDecorView());
                capture(directory, destination + "-end");
            }
            navigate("sessions");
            controller.openSession("try-chat");
            await(() -> controller.snapshot().sessions.loaded, 5000);
            settle();
            inspect("session-thread", screen.getWindow().getDecorView());
            capture(directory, "session-thread");
            keyboard(directory, "session-keyboard");
            navigate("conversation");
            controller.draft("QA window and font retained draft");
            await(() -> controller.draft().equals("QA window and font retained draft"), 2000);
            keyboard(directory, "conversation-keyboard");
            check(
                    controller.draft().equals("QA window and font retained draft"),
                    "opening the keyboard preserves the draft");
            navigate("pairing");
            keyboard(directory, "pairing-keyboard");
            navigate("location");
            keyboard(directory, "location-keyboard");
            Dialog dialog =
                    main(
                            () ->
                                    Sheets.actions(
                                            screen.ui(),
                                            screen.ui().t("添加到消息"),
                                            new Sheets.Action(screen.ui().t("照片"), () -> {}),
                                            new Sheets.Action(screen.ui().t("拍照"), () -> {}),
                                            new Sheets.Action(screen.ui().t("文件"), () -> {}),
                                            new Sheets.Action(screen.ui().t("位置"), () -> {})));
            settle();
            inspect("attachment-sheet", dialog.getWindow().getDecorView());
            capture(directory, "attachment-sheet");
            main(
                    () -> {
                        dialog.dismiss();
                        return null;
                    });
            Dialog edit =
                    main(
                            () ->
                                    Sheets.edit(
                                            screen.ui(),
                                            screen.ui().t("输入文字"),
                                            "QA long edit text with a retained selection",
                                            value -> {}));
            keyboard(directory, "edit-keyboard", edit.getWindow());
            main(
                    () -> {
                        edit.dismiss();
                        return null;
                    });
            android.content.res.Configuration config = screen.getResources().getConfiguration();
            report.put("widthDp", config.screenWidthDp)
                    .put("heightDp", config.screenHeightDp)
                    .put("fontScale", config.fontScale)
                    .put("orientation", config.orientation)
                    .put("multiWindow", screen.isInMultiWindowMode())
                    .put("api", android.os.Build.VERSION.SDK_INT)
                    .put("language", arguments.getString("uiLanguage", "en"))
                    .put("theme", arguments.getString("uiTheme", "dark"))
                    .put("pages", pages)
                    .put("failures", failures)
                    .put("assertions", assertions);
            Files.write(
                    new File(directory, "result.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
            if (failures.length() != 0)
                throw new AssertionError(failures.length() + " UI faults: " + failures);
            return "PASS " + assertions + " emulator UI geometry assertions; " + report;
        } finally {
            controller.stopTrying();
            controller.settings.language(language);
            controller.settings.appearance(appearance);
            controller.setReplyAudio(output);
            controller.setVoiceInput(voice);
            if (screen != null)
                main(
                        () -> {
                            screen.finish();
                            return null;
                        });
        }
    }

    private void navigate(String page) throws Exception {
        main(
                () -> {
                    screen.ui().hideKeyboard(screen.getWindow().getDecorView());
                    screen.navigate(page);
                    return null;
                });
        settle();
    }

    private void settle() throws Exception {
        probe.waitForIdleSync();
        Thread.sleep(200);
    }

    private List<ScrollView> scrolls(View view) {
        List<ScrollView> out = new ArrayList<>();
        if (!view.isShown()) return out;
        if (view instanceof ScrollView) out.add((ScrollView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                out.addAll(scrolls(((ViewGroup) view).getChildAt(i)));
        return out;
    }

    private EditText input(View view) {
        if (!view.isShown()) return null;
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                EditText found = input(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private void keyboard(File directory, String name) throws Exception {
        keyboard(directory, name, screen.getWindow());
    }

    private void keyboard(File directory, String name, android.view.Window window)
            throws Exception {
        await(() -> main(() -> window.getDecorView().hasWindowFocus()), 3000);
        EditText editor = main(() -> input(window.getDecorView()));
        check(editor != null, name + " editor exists");
        if (editor == null) return;
        main(
                () -> {
                    editor.requestFocus();
                    screen.getSystemService(android.view.inputmethod.InputMethodManager.class)
                            .showSoftInput(editor, 0);
                    return null;
                });
        Thread.sleep(700);
        settle();
        main(
                () -> {
                    Rect bounds = new Rect();
                    check(
                            editor.getGlobalVisibleRect(bounds)
                                    && bounds.height() >= screen.ui().touchTarget(),
                            name + " editor remains visible");
                    android.view.WindowInsets insets = window.getDecorView().getRootWindowInsets();
                    int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                    check(
                            insets.isVisible(android.view.WindowInsets.Type.ime()),
                            name + " actual IME is visible");
                    Rect visibleFrame = new Rect();
                    window.getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
                    check(
                            bounds.bottom <= visibleFrame.bottom,
                            name + " editor is above IME: " + bounds + ", inset=" + ime);
                    return null;
                });
        capture(directory, name);
        main(
                () -> {
                    screen.ui().hideKeyboard(editor);
                    editor.clearFocus();
                    return null;
                });
        Thread.sleep(300);
    }

    private void inspect(String page, View root) {
        pages.put(page);
        main(
                () -> {
                    inspectTree(page, root);
                    return null;
                });
    }

    private void inspectTree(String page, View view) {
        if (!view.isShown()) return;
        if (view instanceof dev.openduo.pocket.location.TileMapView)
            check(
                    view.getHeight() >= screen.ui().touchTarget(),
                    page + " map retains a usable height");
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (text.length() > 0 && layout != null && text.getEllipsize() == null) {
                int required =
                        layout.getHeight()
                                + text.getCompoundPaddingTop()
                                + text.getCompoundPaddingBottom();
                check(
                        required <= text.getHeight() + 1,
                        page
                                + " clipped text: "
                                + text.getText()
                                + " height="
                                + text.getHeight()
                                + " needs="
                                + required);
            }
        }
        if (view.isClickable() && view.isEnabled() && !(view instanceof EditText)) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds))
                check(
                        view.getWidth() > 0 && view.getHeight() > 0,
                        page + " action has no geometry");
        }
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                inspectTree(page, ((ViewGroup) view).getChildAt(i));
    }

    private void capture(File directory, String name) throws Exception {
        android.graphics.Bitmap bitmap = probe.getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("No screenshot for " + name);
        try (java.io.FileOutputStream out =
                new java.io.FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
    }
}
