// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.AmbientInput;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.sight.SightSession;
import dev.openduo.pocket.text.AppText;
import dev.openduo.pocket.text.TextCatalog;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Prepared camera control frames on the actual compact UI; no microphone or camera opt-in. */
final class SightUiProbe {
    private final Probe probe;
    private int assertions;
    private PocketController controller;
    private Executor state;

    SightUiProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private Field field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        T value = work.get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        probe.waitForIdleSync();
        return value;
    }

    private boolean visible(Activity screen, String text) {
        boolean[] result = {false};
        probe.runOnMainSync(
                () -> {
                    View root = screen.getWindow().getDecorView();
                    TextView line = probe.textView(root, text);
                    Rect bounds = new Rect(), viewport = new Rect();
                    root.getWindowVisibleDisplayFrame(viewport);
                    result[0] =
                            line != null
                                    && line.getGlobalVisibleRect(bounds)
                                    && bounds.height() == line.getHeight()
                                    && viewport.contains(bounds);
                });
        return result[0];
    }

    int run(Context context, Activity screen, PocketController controller) throws Exception {
        this.controller = controller;
        state = (Executor) field("state").get(controller);
        SightSession sight = (SightSession) field("sightSession").get(controller);
        TextCatalog words = AppText.get(context);
        boolean expanded = controller.settings.ambientTranscript();
        String draft = controller.draft(), quote = controller.snapshot().quote;
        int pending = controller.snapshot().outbox.size();
        check(
                controller.snapshot().trying && !controller.snapshot().ambientActive,
                "camera UI stimuli stay in an idle disposable room");
        try {
            controller.settings.ambientTranscript(false);
            onState(
                    () -> {
                        field("ambientWanted").setBoolean(controller, true);
                        AmbientInput input = (AmbientInput) field("ambientInput").get(controller);
                        input.socketOpened();
                        input.meta(Json.object("role", "master"), "try");
                        invoke("updateSight");
                        invoke("publish");
                        return null;
                    });
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("ambient"));
            probe.waitForIdleSync();
            check(
                    visible(screen, words.t("房间不支持视频，请更新 ambient"))
                            && !controller.snapshot().cameraOffered
                            && !controller.snapshot().cameraOn
                            && !controller.snapshot().cameraRequested,
                    "a master seat without media support explains the missing camera even when"
                            + " the server sends no camera frame");
            onState(
                    () -> {
                        ((AmbientInput) field("ambientInput").get(controller))
                                .meta(Json.object("role", "master", "media", 1), "try");
                        invoke("updateSight");
                        invoke("publish");
                        return null;
                    });
            check(
                    !controller.snapshot().cameraStatusVisible
                            && !controller.snapshot().cameraOffered,
                    "a media-capable connection clears the derived warning while waiting for"
                            + " a real camera offer");
            onState(
                    () -> {
                        field("ambientWanted").setBoolean(controller, true);
                        ((AmbientInput) field("ambientInput").get(controller))
                                .meta(Json.object("role", "master", "media", 1), "try");
                        invoke("updateSight");
                        sight.asked(
                                Json.object("capture", JSONObject.NULL, "reason", "nonconforming"));
                        return null;
                    });
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("ambient"));
            probe.waitForIdleSync();
            String refused = words.t("房间未接收这台设备的画面，相机已关闭");
            check(
                    visible(screen, refused)
                            && !controller.snapshot().cameraOffered
                            && !controller.snapshot().cameraOn
                            && !controller.snapshot().cameraRequested,
                    "a refused camera has a fully visible reason without offering controls or"
                            + " silently opting in");
            onState(
                    () -> {
                        field("lastHeard").set(controller, "Prepared heard text");
                        field("heatLevel").setInt(controller, 1);
                        ((AmbientInput) field("ambientInput").get(controller))
                                .press("qa-sight", true);
                        invoke("publish");
                        return null;
                    });
            check(
                    visible(screen, refused),
                    "hearing, held-note and heat updates cannot overwrite the camera refusal");
            probe.screenshot(context, "sight-refused");
            probe.runOnMainSync(
                    () ->
                            probe.textView(screen.getWindow().getDecorView(), words.t("对话"))
                                    .performClick());
            probe.waitForIdleSync();
            check(
                    visible(screen, refused) && visible(screen, words.t("关闭环境模式")),
                    "the expanded transcript retains the refusal and the full exit action on the"
                            + " actual narrow viewport");
            probe.screenshot(context, "sight-refused-transcript");

            String rawReason = "future_reason · 相机未开启";
            onState(
                    () -> {
                        sight.asked(Json.object("capture", JSONObject.NULL, "reason", rawReason));
                        return null;
                    });
            check(
                    visible(screen, words.format("房间关闭了这台设备的相机：%s", rawReason)),
                    "an unknown server reason remains literal inside localised application copy");
            onState(
                    () -> {
                        sight.asked(
                                Json.object(
                                        "capture", JSONObject.NULL, "reason", "unframed_channel"));
                        return null;
                    });
            check(
                    visible(screen, words.t("房间不支持视频，请更新 ambient")),
                    "an obsolete channel gets an actionable camera explanation");
            onState(
                    () -> {
                        sight.asked(
                                Json.object(
                                        "max_frame_bytes",
                                        1024,
                                        "capture",
                                        Json.object("width", 0)));
                        return null;
                    });
            check(
                    visible(screen, words.t("服务器相机参数无效")) && !controller.snapshot().cameraOffered,
                    "invalid camera parameters retain a visible error while controls are absent");
            onState(
                    () -> {
                        sight.asked(Json.object("capture", JSONObject.NULL));
                        return null;
                    });
            check(
                    !controller.snapshot().cameraStatusVisible
                            && controller.snapshot().sightRefusal.isEmpty(),
                    "an ordinary camera stop clears the previous diagnostic");
            onState(
                    () -> {
                        sight.asked(Json.object("capture", JSONObject.NULL, "reason", rawReason));
                        sight.connectionReset();
                        invoke("publish");
                        return null;
                    });
            check(
                    !controller.snapshot().cameraStatusVisible
                            && controller.snapshot().sightRefusal.isEmpty(),
                    "a replaced connection cannot retain another socket's camera refusal");
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().quote.equals(quote)
                            && controller.snapshot().outbox.size() == pending,
                    "camera diagnostics leave composer state and queued messages intact");
            return assertions;
        } finally {
            onState(
                    () -> {
                        field("ambientWanted").setBoolean(controller, false);
                        field("ambient").set(controller, PocketController.Ambient.OFF);
                        field("heatLevel").setInt(controller, 0);
                        field("lastHeard").set(controller, "");
                        ((AmbientInput) field("ambientInput").get(controller))
                                .press("qa-sight", false);
                        sight.clear();
                        invoke("publish");
                        return null;
                    });
            controller.settings.ambientTranscript(expanded);
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
    }
}
