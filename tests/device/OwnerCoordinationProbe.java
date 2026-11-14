// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.SpeechPlayer;
import dev.openduo.pocket.audio.SpeechReader;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.trymode.TryScript;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;

/** Short Try-only wiring check: retained sends and silent real decoding/playback, no capture. */
final class OwnerCoordinationProbe {
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private int assertions;

    OwnerCoordinationProbe(Probe probe) {
        this.probe = probe;
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(3, TimeUnit.SECONDS);
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private void await(Callable<Boolean> condition, long timeout, String label) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
        check(condition.call(), label);
    }

    private boolean delivered(String id) {
        return controller.snapshot().rows.stream()
                        .anyMatch(row -> row.id.equals(id) && row.delivery == Message.DELIVERED)
                && controller.snapshot().outbox.stream().noneMatch(item -> item.id.equals(id));
    }

    String run(Context context) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(context, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () -> screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(() -> controller.snapshot() != null, 5000, "state is available");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty(),
                "coordination check starts with idle real work");
        String room = controller.settings.room(), host = controller.settings.host();
        boolean voiceMode = controller.settings.voiceInput();
        boolean replies = controller.settings.ambientOutput();
        try {
            // The disposable socket must start without unsolicited prepared reply audio.
            onState(
                    () -> {
                        controller.settings.ambientOutput(false);
                        invoke("startTryingOnState");
                        return null;
                    });
            if (!initial.ambientMuted) controller.ambientMute();
            controller.setVoiceInput(false);
            await(
                    () ->
                            controller.snapshot().trying
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    5000,
                    "outgoing work is isolated in Try");

            controller.draft("Owner coordination: local Try only");
            controller.sendDraft();
            await(
                    () ->
                            controller.snapshot().rows.stream()
                                            .anyMatch(
                                                    row ->
                                                            row.kind == Message.MINE
                                                                    && row.text.equals(
                                                                            "Owner coordination:"
                                                                                + " local Try only")
                                                                    && row.delivery
                                                                            == Message.DELIVERED)
                                    && controller.snapshot().outbox.isEmpty(),
                    3000,
                    "text live settlement finishes the extracted sender");

            String voiceId = UUID.randomUUID().toString();
            File voiceDirectory = new File(context.getCacheDir(), "try-voice");
            voiceDirectory.mkdirs();
            File voice = new File(voiceDirectory, "owner-check-" + voiceId + ".packets");
            try (OpusEncoder encoder = new OpusEncoder();
                    FileOutputStream output = new FileOutputStream(voice)) {
                for (int i = 0; i < 20; i++) {
                    byte[] packet = encoder.encode(new short[320]);
                    output.write(packet.length & 255);
                    output.write(packet.length >>> 8);
                    output.write(packet);
                }
            }
            onState(
                    () -> {
                        RoomRepository repo = (RoomRepository) field("repo");
                        repo.enqueue(
                                Message.mine(
                                        voiceId,
                                        "",
                                        "phone",
                                        400,
                                        Message.QUEUED,
                                        Collections.emptyList()),
                                new Outbox.Item(
                                        voiceId,
                                        "",
                                        true,
                                        voice.getPath(),
                                        "phone",
                                        400,
                                        Collections.emptyList(),
                                        Message.QUEUED,
                                        "",
                                        "",
                                        System.currentTimeMillis(),
                                        0));
                        invoke("reload");
                        invoke("pump");
                        return null;
                    });
            await(
                    () -> delivered(voiceId),
                    4000,
                    "prepared voice bytes settle through the normal outbox path");
            check(
                    onState(() -> !((OutboxSender) field("outboxSender")).active()),
                    "delivered voice leaves no active sender lifetime");

            String text =
                    TryScript.turns(dev.openduo.pocket.text.AppText.get(context).en).get(3).text;
            Message read =
                    Message.answer("owner-read", text, true, false, -1, Collections.emptyList());
            controller.readAloud(read);
            await(
                    () -> controller.snapshot().readingId.equals(read.id),
                    1500,
                    "read action reaches the extracted reader");
            await(
                    () ->
                            onState(
                                    () ->
                                            !((SpeechReader) field("speechReader")).inFlight()
                                                    && ((SpeechPlayer) field("player"))
                                                            .hasPending()),
                    5000,
                    "HTTP completion preserves reading identity until real silent playback drains");
            await(
                    () -> controller.snapshot().readingId.isEmpty(),
                    6000,
                    "playback drain clears the reader identity");
            check(
                    controller.snapshot().lastError.isEmpty(),
                    "read completes without a controller notice");

            controller.readAloud(read);
            await(
                    () -> controller.snapshot().readingId.equals(read.id),
                    1500,
                    "a subsequent read starts");
            controller.cancelOrStop();
            await(
                    () ->
                            controller.snapshot().readingId.isEmpty()
                                    && onState(
                                            () ->
                                                    !((SpeechReader) field("speechReader"))
                                                                    .inFlight()
                                                            && !((SpeechPlayer) field("player"))
                                                                    .hasPending()),
                    2000,
                    "shared stop action retires streaming and queued output together");
        } finally {
            controller.cancelOrStop();
            onState(
                    () -> {
                        controller.settings.ambientOutput(replies);
                        return null;
                    });
            controller.stopTrying();
            controller.setVoiceInput(voiceMode);
            if (controller.snapshot().ambientMuted != initial.ambientMuted)
                controller.ambientMute();
            onState(() -> null);
        }
        await(
                () ->
                        !controller.snapshot().trying
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                20_000,
                "original room reconnects with its retained node");
        check(
                controller.settings.room().equals(room)
                        && controller.settings.host().equals(host)
                        && controller.snapshot().draft.equals(initial.draft)
                        && controller.snapshot().quote.equals(initial.quote)
                        && controller.snapshot().chips.size() == initial.chips.size(),
                "original pairing and composer are preserved");
        return "PASS "
                + assertions
                + " bounded owner-coordination assertions; Try-only prepared sends;"
                + " silent native decode/playback; no capture; original pairing Online";
    }
}
