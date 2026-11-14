// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Read-only actual-room acceptance; the host restores Wi-Fi after a deliberate short outage. */
final class NetworkRoomProbe {
    private final Probe probe;
    private PocketController controller;
    private int assertions;

    NetworkRoomProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private boolean online() {
        return controller.snapshot() != null
                && controller.snapshot().connection == PocketController.Connection.ONLINE;
    }

    private boolean hasNetwork(Context context) {
        return context.getSystemService(android.net.ConnectivityManager.class).getActiveNetwork()
                != null;
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(30);
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private boolean foreground() throws Exception {
        FutureTask<Boolean> value = new FutureTask<>(() -> (Boolean) field("foreground"));
        ((Executor) field("state")).execute(value);
        return value.get(3, TimeUnit.SECONDS);
    }

    private void awaitForeground(boolean expected) throws Exception {
        long until = SystemClock.elapsedRealtime() + 5000;
        while (foreground() != expected && SystemClock.elapsedRealtime() < until) Thread.sleep(30);
    }

    private void checkpoint(String phase, long elapsed) {
        Bundle report = new Bundle();
        report.putString("checkpoint", phase);
        report.putLong("elapsedMs", elapsed);
        probe.sendStatus(0, report);
    }

    private Activity open(Context context) {
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        return screen;
    }

    String run(Context context) throws Exception {
        long launched = SystemClock.elapsedRealtime();
        Activity screen = open(context);
        controller = PocketController.get(context);
        await(this::online, 35_000);
        check(online() && controller.configured(), "cold launch reaches the actual paired room");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.outbox.isEmpty(),
                "physical network acceptance requires an idle actual room");
        checkpoint("cold-online", SystemClock.elapsedRealtime() - launched);
        String target = initial.importTarget, draft = initial.draft, quote = initial.quote;
        int chips = initial.chips.size();
        try {
            long outage = SystemClock.elapsedRealtime();
            checkpoint("wifi-ready", 0);
            await(() -> !hasNetwork(context) && !online(), 18_000);
            check(
                    !hasNetwork(context) && !online(),
                    "real Wi-Fi loss retires the actual live connection");
            checkpoint("wifi-offline", SystemClock.elapsedRealtime() - outage);
            long restored = SystemClock.elapsedRealtime();
            await(() -> hasNetwork(context) && online(), 60_000);
            check(
                    hasNetwork(context) && online(),
                    "restored physical Wi-Fi reconnects without login or re-pairing");
            checkpoint("wifi-online", SystemClock.elapsedRealtime() - restored);
            check(
                    controller.snapshot().importTarget.equals(target)
                            && controller.draft().equals(draft)
                            && controller.snapshot().quote.equals(quote)
                            && controller.snapshot().chips.size() == chips
                            && controller.snapshot().outbox.isEmpty(),
                    "a real network outage preserves the room, draft and attachments without"
                            + " sending");
            Activity retiring = screen;
            probe.runOnMainSync(() -> retiring.moveTaskToBack(true));
            awaitForeground(false);
            check(!foreground(), "the actual Activity stop reaches the background owner");
            Thread.sleep(5000);
            launched = SystemClock.elapsedRealtime();
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            awaitForeground(true);
            check(foreground(), "the replacement Activity reaches the foreground owner");
            await(this::online, 35_000);
            check(
                    online() && foreground(),
                    "a real Activity return recovers the paired connection");
            checkpoint("foreground-online", SystemClock.elapsedRealtime() - launched);
            check(
                    controller.snapshot().importTarget.equals(target)
                            && controller.draft().equals(draft)
                            && controller.snapshot().quote.equals(quote)
                            && controller.snapshot().chips.size() == chips
                            && controller.snapshot().outbox.isEmpty(),
                    "actual background/foreground retains pairing and the unfinished composer");
            return "PASS " + assertions + " actual network and lifecycle assertions";
        } finally {
            Activity retiring = screen;
            probe.runOnMainSync(retiring::finish);
        }
    }
}
