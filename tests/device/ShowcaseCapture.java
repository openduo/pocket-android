// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.BooleanSupplier;

/** Documentation capture only: prepared Try content, no audio/camera or room sends. */
final class ShowcaseCapture {
    private final Probe probe;
    private boolean english;
    private int turn;

    ShowcaseCapture(Probe probe) {
        this.probe = probe;
    }

    private void await(BooleanSupplier ready) throws Exception {
        await(ready, 8000, "Showcase content did not settle");
    }

    private void await(BooleanSupplier ready, long timeout, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30);
        if (!ready.getAsBoolean()) throw new IllegalStateException(failure);
    }

    String run(Context context, Bundle args) throws Exception {
        PocketController controller = PocketController.get(context);
        await(() -> controller.snapshot() != null);
        boolean paired = controller.configured();
        if (paired && !"1".equals(args.getString("showcasePaired")))
            throw new IllegalStateException(
                    "Showcase requires an unpaired app or paired E2 opt-in");
        PocketController.Snapshot initial = controller.snapshot();
        if (initial.trying
                || initial.ambientActive
                || initial.holdRecording
                || initial.playingAudio
                || initial.uploadingVoice
                || !initial.readingId.isEmpty()
                || !initial.outbox.isEmpty()
                || !initial.imports.isEmpty())
            throw new IllegalStateException("Showcase requires an idle app");
        String draft = controller.draft();
        String language = controller.settings.language(),
                appearance = controller.settings.appearance();
        boolean voice = controller.settings.voiceInput(),
                output = controller.settings.ambientOutput();
        File directory = new File(context.getExternalCacheDir(), "showcase");
        directory.mkdirs();
        MainActivity activity = null;
        try {
            String selectedLanguage = args.getString("showcaseLanguage", "en");
            english = selectedLanguage.equals("en");
            controller.settings.language(selectedLanguage);
            controller.settings.appearance(args.getString("showcaseTheme", "light"));
            controller.setReplyAudio(false);
            controller.setVoiceInput(false);
            activity =
                    (MainActivity)
                            probe.startActivitySync(
                                    new Intent(context, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity screen = activity;
            probe.runOnMainSync(
                    () ->
                            screen.getWindow()
                                    .addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            controller.startTrying();
            await(
                    () ->
                            controller.snapshot().trying
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE);
            probe.runOnMainSync(() -> screen.navigate("onboarding"));
            capture(directory, "onboarding", controller);
            send(controller, english ? "Hello, DuoDuo" : "你好，多多");
            send(controller, english ? "Will I need an umbrella tomorrow?" : "明天出门需要带伞吗？");
            probe.runOnMainSync(() -> screen.navigate("conversation"));
            capture(directory, "conversation", controller);
            send(controller, english ? "Help me plan the week" : "帮我整理一下本周安排");
            capture(directory, "schedule", controller);
            send(controller, english ? "Send me this week's notes too" : "把这周笔记也发给我吧");
            capture(directory, "files", controller);
            probe.runOnMainSync(() -> screen.navigate("ambient"));
            capture(directory, "ambient", controller);
        } finally {
            controller.stopTrying();
            controller.settings.language(language);
            controller.settings.appearance(appearance);
            controller.setVoiceInput(voice);
            controller.setReplyAudio(output);
            if (activity != null) {
                MainActivity screen = activity;
                try {
                    if (paired)
                        await(
                                () ->
                                        !controller.snapshot().trying
                                                && controller.snapshot().connection
                                                        == PocketController.Connection.ONLINE
                                                && controller.draft().equals(draft),
                                20000,
                                "Paired room or draft did not restore after capture");
                } finally {
                    probe.runOnMainSync(screen::finish);
                }
            }
        }
        return "PASS documentation capture; prepared content only"
                + (paired ? "; original room online and draft preserved" : "");
    }

    private void send(PocketController controller, String text) throws Exception {
        requireTry(controller);
        String expected = dev.openduo.pocket.trymode.TryScript.turns(english).get(turn++).text;
        controller.draft(text);
        controller.sendDraft();
        await(
                () ->
                        controller.snapshot().rows.stream()
                                        .anyMatch(row -> row.text.equals(expected))
                                && controller.snapshot().working == null
                                && controller.snapshot().outbox.isEmpty());
        Thread.sleep(150);
    }

    private void capture(File directory, String name, PocketController controller)
            throws Exception {
        probe.waitForIdleSync();
        Thread.sleep(300);
        requireTry(controller);
        Bitmap bitmap = probe.getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IllegalStateException("No showcase screenshot");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
    }

    private static void requireTry(PocketController controller) {
        if (!controller.snapshot().trying)
            throw new IllegalStateException("Showcase can only send or capture in Try");
    }
}
