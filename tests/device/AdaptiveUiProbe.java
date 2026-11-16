// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
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
        boolean layoutOnly = arguments.getString("layoutOnly", "0").equals("1");
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
                inspectCanvas(destination);
                inspectPrimaryActions(destination);
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
            if (screen.ui().metrics.twoPaneWorkspace()) {
                View item =
                        main(
                                () ->
                                        screen.getWindow()
                                                .getDecorView()
                                                .findViewWithTag("session:try-chat"));
                check(item != null && item.isShown(), "wide sessions show a selectable list");
                if (item != null) {
                    probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
                    main(
                            () -> {
                                item.requestFocus();
                                return null;
                            });
                    probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
                } else controller.openSession("try-chat");
            } else controller.openSession("try-chat");
            await(() -> controller.snapshot().sessions.loaded, 5000);
            settle();
            if (screen.ui().metrics.twoPaneWorkspace())
                check(
                        main(
                                () -> {
                                    View selected =
                                            screen.getWindow()
                                                    .getDecorView()
                                                    .findViewWithTag("session:try-chat");
                                    return selected != null && selected.hasFocus();
                                }),
                        "wide session selection retains D-pad focus in the list");
            inspect("session-thread", screen.getWindow().getDecorView());
            capture(directory, "session-thread");
            if (!layoutOnly) keyboard(directory, "session-keyboard");
            navigate("conversation");
            if (screen.ui().metrics.compactNavigation()) {
                View menu =
                        main(
                                () ->
                                        described(
                                                screen.getWindow().getDecorView(),
                                                screen.ui().t("切换页面")));
                check(menu != null, "compact conversation exposes the page menu");
                if (menu != null) {
                    View ambientButton =
                            main(
                                    () ->
                                            described(
                                                    screen.getWindow().getDecorView(),
                                                    screen.ui().t("环境模式")));
                    main(
                            () -> {
                                if (ambientButton != null) ambientButton.requestFocus();
                                return null;
                            });
                    check(
                            ambientButton != null && main(ambientButton::hasFocus),
                            "compact ambient header control accepts focus");
                    probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
                    boolean reached = main(menu::hasFocus);
                    check(reached, "D-pad can reach compact page menu from header");
                    if (reached) {
                        probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
                        settle();
                        capture(directory, "compact-menu");
                        probe.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                        settle();
                        check(main(menu::isShown), "Back closes compact menu without leaving chat");
                    }
                }
            }
            if (!layoutOnly) {
                controller.draft("QA window and font retained draft");
                await(() -> controller.draft().equals("QA window and font retained draft"), 2000);
                keyboard(directory, "conversation-keyboard");
                check(
                        controller.draft().equals("QA window and font retained draft"),
                        "opening the keyboard preserves the draft");
                navigate("pairing");
                keyboard(directory, "pairing-keyboard");
                keyboard(
                        directory,
                        "pairing-code-keyboard",
                        screen.getWindow(),
                        dev.openduo.pocket.R.id.pair_code);
                navigate("location");
                keyboard(directory, "location-keyboard");
            }
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
            if (!layoutOnly) {
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
            }
            android.content.res.Configuration config = screen.getResources().getConfiguration();
            report.put("widthDp", config.screenWidthDp)
                    .put("heightDp", config.screenHeightDp)
                    .put("fontScale", config.fontScale)
                    .put("orientation", config.orientation)
                    .put("multiWindow", screen.isInMultiWindowMode())
                    .put("api", android.os.Build.VERSION.SDK_INT)
                    .put("language", arguments.getString("uiLanguage", "en"))
                    .put("theme", arguments.getString("uiTheme", "dark"))
                    .put("layoutOnly", layoutOnly)
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
        keyboard(directory, name, window, 0);
    }

    private void keyboard(File directory, String name, android.view.Window window, int editorId)
            throws Exception {
        await(() -> main(() -> window.getDecorView().hasWindowFocus()), 3000);
        EditText editor =
                main(
                        () ->
                                editorId == 0
                                        ? input(window.getDecorView())
                                        : window.getDecorView().findViewById(editorId));
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

    private void inspectCanvas(String page) {
        main(
                () -> {
                    try {
                        java.lang.reflect.Field field =
                                MainActivity.class.getDeclaredField("pages");
                        field.setAccessible(true);
                        ViewGroup pages = (ViewGroup) field.get(screen);
                        for (int i = 0; i < pages.getChildCount(); i++) {
                            View wrapper = pages.getChildAt(i);
                            if (!wrapper.isShown() || !(wrapper instanceof ViewGroup)) continue;
                            View child = ((ViewGroup) wrapper).getChildAt(0);
                            check(
                                    child != null && child.getWidth() == pages.getWidth(),
                                    page + " page does not span the available window");
                            break;
                        }
                        if (page.equals("conversation")
                                || page.equals("ambient")
                                || page.equals("settings")) {
                            field = MainActivity.class.getDeclaredField("navSlot");
                            field.setAccessible(true);
                            View nav = (View) field.get(screen);
                            if (screen.ui().metrics.compactNavigation()) {
                                check(!nav.isShown(), page + " compact page keeps content height");
                                String access =
                                        page.equals("conversation")
                                                ? screen.ui().t("切换页面")
                                                : page.equals("settings")
                                                        ? screen.ui().t("返回")
                                                        : screen.ui().t("最小化环境模式");
                                View button = described(pages, access);
                                check(
                                        button != null
                                                && button.isShown()
                                                && button.isFocusable()
                                                && button.getWidth() >= screen.ui().touchTarget(),
                                        page + " compact page has a focusable way back");
                            } else if (screen.ui().metrics.navigationRail())
                                check(
                                        nav.getWidth() < pages.getWidth() / 3
                                                && nav.getHeight() >= pages.getHeight(),
                                        page
                                                + " wide/short navigation does not save vertical"
                                                + " room");
                            else
                                check(
                                        nav.getWidth() >= pages.getWidth()
                                                && nav.getHeight() <= screen.ui().dp(100),
                                        page + " compact navigation does not span the bottom");
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    return null;
                });
    }

    private View described(View root, String description) {
        if (!root.isShown()) return null;
        if (root.getContentDescription() != null
                && description.contentEquals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View found = described(((ViewGroup) root).getChildAt(i), description);
                if (found != null) return found;
            }
        return null;
    }

    private View text(View root, String value) {
        if (!root.isShown()) return null;
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText()))
            return root;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View found = text(((ViewGroup) root).getChildAt(i), value);
                if (found != null) return found;
            }
        return null;
    }

    private void inspectPrimaryActions(String page) {
        main(
                () -> {
                    View decor = screen.getWindow().getDecorView();
                    String label =
                            page.equals("pairing")
                                    ? screen.ui().t("配对并连接")
                                    : page.equals("location") ? screen.ui().t("使用这个位置") : "";
                    if (!label.isEmpty()) {
                        View action = text(decor, label);
                        Rect visible = new Rect();
                        android.view.WindowInsets insets = decor.getRootWindowInsets();
                        int navigation =
                                insets == null
                                        ? 0
                                        : insets.getInsetsIgnoringVisibility(
                                                        android.view.WindowInsets.Type
                                                                .navigationBars())
                                                .bottom;
                        check(
                                action != null
                                        && action.getGlobalVisibleRect(visible)
                                        && visible.height() >= screen.ui().touchTarget()
                                        && visible.bottom <= decor.getHeight() - navigation + 1,
                                page + " primary action clears the navigation gesture area");
                    }
                    if (page.equals("onboarding")) {
                        View introduction = text(decor, screen.ui().t("随时说，接着聊"));
                        View join = text(decor, screen.ui().t("连接房间"));
                        View trial = text(decor, screen.ui().t("先体验 · 预设对话"));
                        Rect descriptionBounds = new Rect(), actionBounds = new Rect();
                        boolean bothVisible =
                                introduction != null
                                        && join != null
                                        && introduction.getGlobalVisibleRect(descriptionBounds)
                                        && join.getGlobalVisibleRect(actionBounds);
                        check(
                                introduction != null
                                        && join != null
                                        && trial != null
                                        && (!screen.ui().metrics.twoPaneOnboarding()
                                                        && screen.ui().metrics.shortHeight()
                                                || bothVisible
                                                        && actionBounds.height()
                                                                >= screen.ui().touchTarget()),
                                "onboarding keeps its primary action reachable at each window"
                                    + " size");
                        if (bothVisible) {
                            boolean beside = actionBounds.left > descriptionBounds.right;
                            check(
                                    beside == screen.ui().metrics.twoPaneOnboarding(),
                                    "onboarding reflows choices according to available pane width");
                        }
                    }
                    if (page.equals("conversation") && !screen.ui().metrics.twoPaneWorkspace()) {
                        check(
                                text(decor, screen.ui().t("体验房间 · 回答为预设内容")) == null,
                                "try room context is not duplicated below the chat heading");
                        if (screen.ui().metrics.compactNavigation()) {
                            EditText editor = decor.findViewById(dev.openduo.pocket.R.id.composer);
                            check(
                                    editor != null && editor.getWidth() >= screen.ui().dp(120),
                                    "narrow chat keeps a readable text composer");
                        }
                    }
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
