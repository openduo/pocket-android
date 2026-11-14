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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;

/** Short Try-only wiring check, with optional explicitly enabled capture cancelled before send. */
final class OwnerCoordinationProbe {
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private int assertions;

    OwnerCoordinationProbe(Probe probe) {
        this.probe = probe;
    }

    private Object playbackField(String name) throws Exception {
        Object owner = field("playback");
        Field member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
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

    String run(Context context, boolean capture) throws Exception {
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

            File shared = new File(context.getCacheDir(), "shared");
            shared.mkdirs();
            File prepared = new File(shared, "owner-coordination.txt");
            byte[] fixture = "Prepared attachment; local Try only".getBytes(StandardCharsets.UTF_8);
            Files.write(prepared.toPath(), fixture);
            CountDownLatch imported = new CountDownLatch(1);
            controller.importContent(
                    UUID.randomUUID().toString(),
                    "",
                    Collections.singletonList(
                            dev.openduo.pocket.app.PocketFiles.uri(
                                    context, prepared, prepared.getName(), "text/plain")),
                    imported::countDown);
            check(
                    imported.await(5, TimeUnit.SECONDS),
                    "prepared import completes its durable handoff");
            await(
                    () ->
                            controller.snapshot().chips.size() == 1
                                    && controller.snapshot().chips.get(0).state
                                            == Attachment.UPLOADED,
                    5000,
                    "composer attachment reaches the owned upload worker");
            Attachment uploaded = controller.snapshot().chips.get(0);
            Attachment remote =
                    new Attachment(
                            uploaded.name,
                            uploaded.mime,
                            "",
                            uploaded.sha256,
                            uploaded.remotePath,
                            uploaded.size,
                            Attachment.UPLOADED);
            CountDownLatch loaded = new CountDownLatch(1);
            File[] downloaded = {null};
            controller.previewAttachment(
                    remote,
                    new PocketController.FileResult() {
                        public void ready(File file, String mime) {
                            downloaded[0] = file;
                            loaded.countDown();
                        }

                        public void failed() {
                            loaded.countDown();
                        }
                    });
            check(
                    loaded.await(5, TimeUnit.SECONDS)
                            && downloaded[0] != null
                            && Arrays.equals(Files.readAllBytes(downloaded[0].toPath()), fixture),
                    "owned download verifies and admits the prepared attachment bytes");
            controller.removeAttachment(uploaded.localPath);
            await(
                    () -> controller.snapshot().chips.isEmpty(),
                    1500,
                    "removing an attachment updates the composer without a stale upload result");
            prepared.delete();

            controller.loadOlder();
            await(
                    () -> !controller.snapshot().olderLoading,
                    2000,
                    "history paging leaves no stuck request");

            if (capture) {
                controller.beginHold("owner-check");
                await(
                        () ->
                                controller.snapshot().holdLive
                                        && controller.snapshot().holdDurationMs >= 400,
                        5000,
                        "microphone owner reaches ready and supplies real captured packets");
                controller.releaseHold("owner-check", true);
                await(
                        () ->
                                !controller.snapshot().holdRecording
                                        && !dev.openduo.pocket.audio.input.RecordingInput
                                                .isActive(),
                        5000,
                        "cancel releases the actual microphone without sending its recording");
            }

            await(
                    () -> {
                        boolean[] visible = {false};
                        probe.runOnMainSync(
                                () -> {
                                    android.widget.EditText input =
                                            screen.findViewById(dev.openduo.pocket.R.id.composer);
                                    visible[0] = input != null && input.isShown();
                                });
                        return visible[0];
                    },
                    2000,
                    "extracted conversation editor is visible in text mode");
            probe.runOnMainSync(
                    () -> {
                        android.widget.EditText input =
                                screen.findViewById(dev.openduo.pocket.R.id.composer);
                        input.setText("Owner coordination: local Try only");
                    });
            await(
                    () -> controller.snapshot().draft.equals("Owner coordination: local Try only"),
                    2000,
                    "native editor changes reach the owned composer through edit acknowledgement");
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
                                            !((SpeechReader) playbackField("reader")).inFlight()
                                                    && ((SpeechPlayer) playbackField("player"))
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
                                                    !((SpeechReader) playbackField("reader"))
                                                                    .inFlight()
                                                            && !((SpeechPlayer)
                                                                            playbackField("player"))
                                                                    .hasPending()),
                    2000,
                    "shared stop action retires streaming and queued output together");
            int before =
                    onState(() -> ((dev.openduo.pocket.net.RoomLink) field("liveLink")).epoch());
            controller.checkCompatibility();
            await(
                    () ->
                            onState(
                                                    () ->
                                                            ((dev.openduo.pocket.net.RoomLink)
                                                                            field("liveLink"))
                                                                    .epoch())
                                            > before
                                    && controller.snapshot().connection
                                            == PocketController.Connection.ONLINE,
                    3000,
                    "Try reconnect admits a new live generation and returns Online");
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
                + " silent native decode/playback; "
                + (capture ? "bounded cancelled capture" : "no capture")
                + "; original pairing Online";
    }
}
