// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.app.PocketFiles;
import dev.openduo.pocket.audio.SpeechPlayer;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.text.AppText;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicit opt-in real-service acceptance. No room messages or session notifications are sent. */
final class RoomProbe {
    private final Probe probe;
    private PocketController controller;
    private Activity activity;
    private Executor state;
    private int assertions;
    private String sessionEvidence = "", speechEvidence = "";

    RoomProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private void await(java.util.function.BooleanSupplier condition, long timeout)
            throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    private dev.openduo.pocket.audio.SpeechReader reader() throws Exception {
        return (dev.openduo.pocket.audio.SpeechReader) playbackField("reader");
    }

    private Object playbackField(String name) throws Exception {
        Object owner = field("playback");
        Field member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }

    private Object field(String name) throws ReflectiveOperationException {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private Object invoke(String name) throws ReflectiveOperationException {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(3, TimeUnit.SECONDS);
    }

    private void checkpoint(String evidence) {
        android.os.Bundle result = new android.os.Bundle();
        result.putString("checkpoint", evidence + "; assertions=" + assertions);
        probe.sendStatus(0, result);
    }

    private List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Attachment attachment : controller.snapshot().chips) paths.add(attachment.localPath);
        return paths;
    }

    String run(Context context) throws Exception {
        activity =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        probe.runOnMainSync(
                () ->
                        activity.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                25000);
        check(
                controller.configured()
                        && controller.snapshot() != null
                        && !controller.snapshot().trying
                        && controller.snapshot().connection == PocketController.Connection.ONLINE,
                "real paired room reaches Online after an application restart");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.readingId.isEmpty()
                        && initial.chips.stream().noneMatch(a -> a.state == Attachment.UPLOADING)
                        && onState(() -> !(Boolean) playbackField("active")),
                "real-service probe requires this app's recording, playback and outgoing work"
                        + " idle");
        Message proof =
                initial.rows.stream()
                        .filter(
                                row ->
                                        row.kind == Message.DUODUO
                                                && row.text.trim().equals("POCKET-E2-LIVE-OK"))
                        .findFirst()
                        .orElse(null);
        check(
                proof != null && initial.cursor > 0,
                "the previously accepted exact live reply and history survive a cold restart");
        String target = initial.importTarget, draft = initial.draft, quote = initial.quote;
        List<String> originalPaths = paths();
        try {
            sessions(context);
            checkpoint(sessionEvidence);
            attachment(context);
            checkpoint("real attachment upload, download and local removal");
            speech(proof);
            check(
                    controller.snapshot().importTarget.equals(target)
                            && controller.draft().equals(draft)
                            && controller.snapshot().quote.equals(quote)
                            && paths().equals(originalPaths)
                            && controller.snapshot().outbox.isEmpty(),
                    "real-service acceptance retains the original room, text, quote and files"
                            + " without sending");
            return "PASS "
                    + assertions
                    + " real room assertions; "
                    + sessionEvidence
                    + "; "
                    + speechEvidence;
        } finally {
            controller.showSessions(false);
            onState(
                    () -> {
                        invoke("stopPlayback");
                        SpeechPlayer player = (SpeechPlayer) playbackField("player");
                        if (player != null) player.silent(controller.snapshot().ambientMuted);
                        return null;
                    });
            probe.runOnMainSync(() -> ((MainActivity) activity).navigate("conversation"));
        }
    }

    private void sessions(Context context) throws Exception {
        probe.runOnMainSync(() -> ((MainActivity) activity).navigate("sessions"));
        probe.waitForIdleSync();
        if (!controller.settings.scope().matches("sessions|notify")) {
            check(
                    probe.textView(
                                            activity.getWindow().getDecorView(),
                                            AppText.get(context)
                                                    .t(
                                                            "当前配对只允许访问房间。在房间管理页用 sessions 或 notify"
                                                                    + " 范围生成新配对链接，才能查看其他会话。"))
                                    != null
                            && probe.visibleInput(activity.getWindow().getDecorView()) == null,
                    "the actual room-only credential exposes the native scope explanation without a"
                            + " notify input");
            sessionEvidence = "sessions limited by actual room-only scope";
        } else {
            await(
                    () ->
                            controller.snapshot().sessions.listed
                                    || !controller.snapshot().sessions.error.isEmpty(),
                    15000);
            check(
                    controller.snapshot().sessions.listed,
                    "the actual scoped server supplies a session list; error="
                            + controller.snapshot().sessions.error);
            SessionViewer.Snapshot sessions = controller.snapshot().sessions;
            if (!sessions.sessions.isEmpty()) {
                controller.openSession(sessions.sessions.get(0).key);
                await(
                        () ->
                                controller.snapshot().sessions.loaded
                                        || !controller.snapshot().sessions.error.isEmpty(),
                        15000);
                check(
                        controller.snapshot().sessions.loaded,
                        "a real listed session supplies its row window without sending a"
                                + " notification");
                sessionEvidence = "real session list and rows";
            } else sessionEvidence = "real empty session list";
        }
        probe.screenshot(context, "room-session-scope");
        probe.runOnMainSync(() -> ((MainActivity) activity).navigate("conversation"));
        await(() -> !controller.snapshot().sessions.watching, 1000);
        check(
                !controller.snapshot().sessions.watching,
                "returning to the room closes its actual session watch");
    }

    private void attachment(Context context) throws Exception {
        ChannelClient.State room = onState(() -> (ChannelClient.State) field("roomState"));
        check(
                room != null && !room.uploadsDisabled,
                "the real room advertises its upload service enabled");
        File shared = new File(context.getCacheDir(), "shared");
        shared.mkdirs();
        File fixture = File.createTempFile("qa-real-room-", ".txt", shared);
        File[] download = {null};
        String name = "DuoDuo Pocket integration fixture " + UUID.randomUUID() + ".txt";
        String[] importedPath = {""};
        ImportRoute route = controller.importRoute(ImportRoute.Kind.FILES);
        try {
            byte[] bytes =
                    ("DuoDuo Pocket real upload/download fixture.\n" + UUID.randomUUID() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(fixture.toPath(), bytes);
            CountDownLatch copied = new CountDownLatch(1);
            controller.importContent(
                    route,
                    "",
                    Collections.singletonList(
                            PocketFiles.uri(context, fixture, name, "text/plain")),
                    copied::countDown);
            check(
                    copied.await(5, TimeUnit.SECONDS),
                    "real-room file import finishes through the durable inbox");
            Attachment first =
                    controller.snapshot().chips.stream()
                            .filter(a -> a.name.equals(name))
                            .findFirst()
                            .orElse(null);
            check(first != null, "the real composer admits exactly the fixture file");
            importedPath[0] = first.localPath;
            await(
                    () ->
                            controller.snapshot().chips.stream()
                                    .anyMatch(
                                            a ->
                                                    a.name.equals(name)
                                                            && a.state != Attachment.UPLOADING),
                    55000);
            Attachment uploaded =
                    controller.snapshot().chips.stream()
                            .filter(a -> a.name.equals(name))
                            .findFirst()
                            .orElse(null);
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            check(
                    uploaded != null
                            && uploaded.state == Attachment.UPLOADED
                            && uploaded.sha256.equals(digest.toString())
                            && !uploaded.remotePath.isEmpty(),
                    "the real upload acknowledgement retains the fixture's actual digest and"
                            + " remote path: state="
                            + (uploaded == null ? "missing" : uploaded.state)
                            + ", digestLength="
                            + (uploaded == null ? 0 : uploaded.sha256.length())
                            + ", digestMatches="
                            + (uploaded != null && uploaded.sha256.equals(digest.toString()))
                            + ", pathPresent="
                            + (uploaded != null && !uploaded.remotePath.isEmpty())
                            + ", localMatches="
                            + Arrays.equals(
                                    bytes, Files.readAllBytes(new File(first.localPath).toPath())));
            Attachment remote =
                    new Attachment(
                            name,
                            "text/plain",
                            "",
                            uploaded.sha256,
                            uploaded.remotePath,
                            bytes.length,
                            Attachment.UPLOADED);
            check(
                    new AttachmentStore(context).cached(remote) == null,
                    "the real download fixture has no local source or existing cached response");
            CountDownLatch downloaded = new CountDownLatch(1);
            controller.download(
                    remote,
                    new PocketController.FileResult() {
                        public void ready(File file, String mime) {
                            download[0] = file;
                            downloaded.countDown();
                        }

                        public void failed() {
                            downloaded.countDown();
                        }
                    });
            check(
                    downloaded.await(55, TimeUnit.SECONDS)
                            && download[0] != null
                            && Arrays.equals(bytes, Files.readAllBytes(download[0].toPath())),
                    "the authenticated real attachment endpoint returns the original bytes and"
                            + " passes digest verification");
            controller.removeAttachment(importedPath[0]);
            await(
                    () -> controller.snapshot().chips.stream().noneMatch(a -> a.name.equals(name)),
                    1500);
            check(
                    controller.snapshot().chips.stream().noneMatch(a -> a.name.equals(name))
                            && !new File(importedPath[0]).exists(),
                    "removing the uploaded real-room fixture cleans its local draft copy");
        } finally {
            controller.cancelImport(route.id);
            for (Attachment chip : controller.snapshot().chips)
                if (chip.name.equals(name)) controller.removeAttachment(chip.localPath);
            await(
                    () -> controller.snapshot().chips.stream().noneMatch(a -> a.name.equals(name)),
                    1500);
            fixture.delete();
            if (download[0] != null) download[0].delete();
        }
    }

    private void speech(Message proof) throws Exception {
        onState(
                () -> {
                    invoke("ensurePlayer");
                    ((SpeechPlayer) playbackField("player")).silent(true);
                    return null;
                });
        controller.clearNotice();
        controller.readAloud(proof);
        long until = android.os.SystemClock.elapsedRealtime() + 55000;
        long played = 0;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            played = onState(() -> ((AmbientOutput) playbackField("output")).playedMs());
            if (played > 0 && controller.snapshot().readingId.isEmpty()) break;
            if (!controller.snapshot().lastError.isEmpty()) break;
            Thread.sleep(30);
        }
        check(
                played > 0
                        && controller.snapshot().readingId.isEmpty()
                        && controller.snapshot().lastError.isEmpty(),
                "real synthesis streams through PacketStream, Opus and muted hardware playback to"
                        + " completion; error="
                        + controller.snapshot().lastError);
        controller.readAloud(proof);
        check(
                onState(() -> reader().inFlight())
                        && controller.snapshot().readingId.equals(proof.id),
                "a repeated real read request enters its ordinary cancellable stream");
        controller.readAloud(proof);
        await(() -> controller.snapshot().readingId.isEmpty(), 1000);
        Thread.sleep(750);
        check(
                controller.snapshot().readingId.isEmpty()
                        && onState(
                                () ->
                                        !reader().inFlight()
                                                && reader().id().isEmpty()
                                                && !((SpeechPlayer) playbackField("player"))
                                                        .hasPending())
                        && controller.snapshot().lastError.isEmpty(),
                "stopping the real stream clears playback and ignores late response bytes/errors");
        speechEvidence = "real synthesis and cancellation with muted AudioTrack watermarks";
    }
}
