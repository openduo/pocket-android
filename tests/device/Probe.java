// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import top.kuaner.pockete2.audio.*;
import top.kuaner.pockete2.core.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SQLite, JNI, input lease and Activity checks. Uses a disposable database only. */
public final class Probe extends Instrumentation {
    private int assertions;
    private Bundle arguments;

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args;
        start();
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    @Override
    public void onStart() {
        Bundle report = new Bundle();
        Context context = getTargetContext();
        try {
            database(context);
            codec();
            assertions += top.kuaner.pockete2.core.SecretProbe.run(context);
            images(context);
            notifications(context);
            playback();
            if (arguments != null && "1".equals(arguments.getString("capture"))) capture(context);
            if (arguments != null && "1".equals(arguments.getString("camera"))) {
                camera(context);
                qrCamera(context);
                sightSession(context);
            }
            activity(context);
            report.putString("result", "PASS " + assertions + " device assertions");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            report.putString("result", "FAIL " + failure);
            report.putString("stack", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, report);
        } finally {
            context.deleteDatabase("pocket-e2-qa.db");
        }
    }

    private void database(Context context) {
        context.deleteDatabase("pocket-e2-qa.db");
        RoomRepository repo = new RoomRepository(context, "pocket-e2-qa.db");
        repo.select("qa-room");
        Outbox.Item item =
                new Outbox.Item(
                        "qa-id",
                        "body",
                        false,
                        "",
                        "",
                        0,
                        Collections.emptyList(),
                        Message.SENDING,
                        "",
                        "",
                        1000,
                        0);
        repo.enqueue(
                new Message(
                        "qa-id",
                        Message.MINE,
                        1000,
                        "body",
                        "",
                        0,
                        Message.SENDING,
                        "",
                        true,
                        false,
                        Collections.emptyList(),
                        -1),
                item);
        repo.metadata("draft", "retained");
        repo.close();
        repo = new RoomRepository(context, "pocket-e2-qa.db");
        repo.select("qa-room");
        check(
                repo.pending().size() == 1
                        && repo.pending().get(0).state == Message.QUEUED
                        && repo.row("qa-id").delivery == Message.QUEUED,
                "crash recovers sending atomically");
        check(repo.metadata("draft").equals("retained"), "draft survives close");
        repo.transition(repo.findPending("qa-id").state(Outbox.ACCEPTED, ""));
        check(repo.pending().size() == 1, "202 stays pending until server row");
        repo.admit(
                Collections.singletonList(
                        new Message(
                                "qa-id",
                                Message.MINE,
                                1001,
                                "cleaned",
                                "",
                                0,
                                Message.DELIVERED,
                                "",
                                true,
                                false,
                                Collections.emptyList(),
                                7)),
                7);
        check(
                repo.pending().isEmpty()
                        && repo.cursor() == 7
                        && repo.row("qa-id").text.equals("cleaned"),
                "row, outbox and cursor settle together");
        repo.admit(
                Collections.singletonList(
                        new Message(
                                "qa-id",
                                Message.MINE,
                                1001,
                                "cleaned",
                                "",
                                0,
                                Message.DELIVERED,
                                "",
                                true,
                                false,
                                Collections.emptyList(),
                                7)),
                7);
        check(repo.rows(20).size() == 1, "replay is idempotent");
        Message first =
                new Message(
                        "ans:qa-id",
                        Message.DUODUO,
                        2000,
                        "first",
                        "",
                        0,
                        Message.DELIVERED,
                        "",
                        true,
                        false,
                        Collections.emptyList(),
                        8);
        Message second =
                new Message(
                        "ans:qa-id",
                        Message.DUODUO,
                        3000,
                        "second",
                        "",
                        0,
                        Message.DELIVERED,
                        "",
                        true,
                        false,
                        Collections.emptyList(),
                        9);
        repo.admit(Arrays.asList(first, second), 9);
        repo.admit(Arrays.asList(first, second), 9);
        check(repo.rows(20).size() == 3, "multiple answers of same turn retain distinct rows");
        repo.select("qa-other");
        check(repo.rows(20).isEmpty() && !repo.hasCursor(), "room isolation");
        repo.select("qa-room");
        check(repo.rows(20).size() == 3, "room data retained");
        repo.put(repo.row("qa-id").withDelivery(Message.FAILED, "not delivered"));
        repo.admit(
                Collections.singletonList(repo.row("qa-id").withDelivery(Message.DELIVERED, "")),
                9);
        check(repo.row("qa-id").delivery == Message.FAILED, "replayed row keeps after-row failure");
        repo.close();
    }

    private void images(Context context) throws Exception {
        java.io.File file = imageFile(context);
        android.graphics.Bitmap bitmap =
                top.kuaner.pockete2.ui.components.ImageBitmaps.decode(file, 128);
        try {
            check(
                    bitmap.getHeight() <= 128 && bitmap.getHeight() > bitmap.getWidth(),
                    "bounded image decode applies EXIF rotation");
        } finally {
            bitmap.recycle();
            file.delete();
        }
    }

    private void notifications(Context context) throws Exception {
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        Settings settings = new Settings(context);
        if (!manager.areNotificationsEnabled() || !settings.answerNotifications()) return;
        java.util.Set<String> before = new java.util.HashSet<>();
        for (android.service.notification.StatusBarNotification n :
                manager.getActiveNotifications()) before.add(n.getKey());
        String text = "QA notice " + System.nanoTime();
        top.kuaner.pockete2.app.AnswerNotifications notices =
                new top.kuaner.pockete2.app.AnswerNotifications(context, settings);
        try {
            notices.frame(
                    Json.object(
                            "type",
                            "answer_final",
                            "text",
                            text,
                            "message_ids",
                            new org.json.JSONArray().put("qa-notice")),
                    false);
            notices.frame(
                    Json.object(
                            "type",
                            "row",
                            "row",
                            Json.object(
                                    "kind",
                                    "answer",
                                    "text",
                                    text,
                                    "message_ids",
                                    new org.json.JSONArray().put("qa-notice"))),
                    false);
            Thread.sleep(100);
            long fresh =
                    java.util.Arrays.stream(manager.getActiveNotifications())
                            .filter(n -> !before.contains(n.getKey()))
                            .count();
            check(fresh == 1, "real NotificationManager deduplicates live answer and log row");
            notices.frame(
                    Json.object("type", "answer_final", "text", text + " bootstrap"), false, false);
            Thread.sleep(100);
            check(
                    java.util.Arrays.stream(manager.getActiveNotifications())
                                    .filter(n -> !before.contains(n.getKey()))
                                    .count()
                            == 1,
                    "initial background bootstrap does not notify old history");
        } finally {
            for (android.service.notification.StatusBarNotification n :
                    manager.getActiveNotifications())
                if (!before.contains(n.getKey())) manager.cancel(n.getTag(), n.getId());
        }
    }

    private java.io.File imageFile(Context context) throws Exception {
        java.io.File dir = new java.io.File(context.getCacheDir(), "shared");
        dir.mkdirs();
        java.io.File file = new java.io.File(dir, "qa-image.jpg");
        android.graphics.Bitmap source =
                android.graphics.Bitmap.createBitmap(
                        320, 160, android.graphics.Bitmap.Config.ARGB_8888);
        source.eraseColor(0xff3b8971);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            source.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            source.recycle();
        }
        android.media.ExifInterface exif = new android.media.ExifInterface(file.getPath());
        exif.setAttribute(
                android.media.ExifInterface.TAG_ORIENTATION,
                String.valueOf(android.media.ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        return file;
    }

    private void codec() throws Exception {
        try (OpusEncoder encoder = new OpusEncoder();
                OpusDecoder decoder = new OpusDecoder()) {
            short[] pcm = new short[320];
            for (int i = 0; i < pcm.length; i++)
                pcm[i] = (short) (6000 * Math.sin(i * 2 * Math.PI * 440 / 16000));
            byte[] packet = encoder.encode(pcm);
            short[] decoded = decoder.packet(packet);
            check(
                    packet.length > 0 && packet.length <= 1275 && decoded.length == 320,
                    "native Opus 16k mono roundtrip");
        }
    }

    private void playback() throws Exception {
        java.util.concurrent.ConcurrentHashMap<String, Long> played =
                new java.util.concurrent.ConcurrentHashMap<>();
        CountDownLatch drained = new CountDownLatch(2);
        String[] error = {""};
        try (SpeechPlayer output =
                new SpeechPlayer(
                        new SpeechPlayer.Listener() {
                            public void played(String id, long ms, boolean done) {
                                Long previous = played.put(id, ms);
                                if (previous == null) drained.countDown();
                            }

                            public void active(boolean active) {}

                            public void failed(String why) {
                                error[0] = why;
                            }
                        })) {
            output.silent(true);
            short[] silence = new short[320];
            for (int i = 0; i < 10; i++) output.enqueue("first", silence);
            for (int i = 0; i < 8; i++) output.enqueue("second", silence);
            check(
                    drained.await(4, TimeUnit.SECONDS),
                    "hardware receipts keep both speech identities");
            long until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (output.hasPending() && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(10);
            check(
                    error[0].isEmpty()
                            && played.getOrDefault("first", 0L) == 200
                            && played.getOrDefault("second", 0L) == 160,
                    "hardware playback head reports cumulative durations");
            for (int i = 0; i < 100; i++) output.enqueue("cancel", silence);
            output.stop();
            until = android.os.SystemClock.elapsedRealtime() + 500;
            while (output.hasPending() && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(10);
            check(!output.hasPending(), "playback cancellation clears bounded queue");
        }
    }

    private void capture(Context context) throws Exception {
        AtomicInteger packets = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        String[] error = {""};
        CaptureSession capture =
                new CaptureSession(
                        context,
                        new CaptureSession.Listener() {
                            public void started() {
                                started.countDown();
                            }

                            public void packet(byte[] bytes) {
                                packets.incrementAndGet();
                            }

                            public void stopped(String why) {
                                error[0] = why;
                                stopped.countDown();
                            }
                        });
        check(started.await(8, TimeUnit.SECONDS), "real input starts");
        long until = android.os.SystemClock.elapsedRealtime() + 2500;
        while (packets.get() < 25 && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(10);
        capture.stop();
        check(
                stopped.await(4, TimeUnit.SECONDS) && error[0].isEmpty() && packets.get() >= 15,
                "real array frames and bounded teardown");
        check(
                !top.kuaner.pockete2.audio.input.RecordingInput.isActive(),
                "microphone lease released");
    }

    private void camera(Context context) throws Exception {
        org.json.JSONObject video =
                Json.object(
                        "codec",
                        "avc1.42e01f",
                        "fps",
                        5,
                        "min_fps",
                        1,
                        "bitrate",
                        256000,
                        "key_interval_ms",
                        1000);
        top.kuaner.pockete2.sight.SightProtocol.Ask ask =
                new top.kuaner.pockete2.sight.SightProtocol.Ask(
                        Json.object("width", 640, "height", 480, "video", video));
        CountDownLatch frames = new CountDownLatch(3);
        CountDownLatch preview = new CountDownLatch(1);
        top.kuaner.pockete2.sight.PreviewFrame[] viewfinder = {null};
        String[] failure = {""};
        java.util.concurrent.atomic.AtomicBoolean key =
                new java.util.concurrent.atomic.AtomicBoolean();
        top.kuaner.pockete2.sight.SightCapture camera =
                new top.kuaner.pockete2.sight.SightCapture(
                        context,
                        ask,
                        false,
                        new top.kuaner.pockete2.sight.SightCapture.Listener() {
                            public void started() {}

                            public boolean wantsPreview() {
                                return true;
                            }

                            public void preview(top.kuaner.pockete2.sight.PreviewFrame frame) {
                                viewfinder[0] = frame;
                                preview.countDown();
                            }

                            public void failed(String reason) {
                                failure[0] = reason;
                                while (frames.getCount() > 0) frames.countDown();
                            }

                            public void chunk(
                                    boolean isKey,
                                    long at,
                                    byte[] bytes,
                                    int w,
                                    int h,
                                    int fps,
                                    int bitrate) {
                                if (isKey)
                                    key.set(bytes.length > 4 && bytes[0] == 0 && bytes[1] == 0);
                                frames.countDown();
                            }
                        });
        try {
            check(
                    frames.await(10, TimeUnit.SECONDS) && failure[0].isEmpty() && key.get(),
                    "real Camera2 H264 Annex-B output: " + failure[0]);
            check(
                    preview.await(2, TimeUnit.SECONDS)
                            && viewfinder[0] != null
                            && Math.max(viewfinder[0].width, viewfinder[0].height) <= 240,
                    "rear viewfinder uses real bounded RGB camera frames");
        } finally {
            camera.close();
            check(camera.awaitStopped(4_000), "camera and encoder teardown bounded");
        }
    }

    private void activity(Context context) throws Exception {
        Activity screen =
                startActivitySync(
                        new Intent()
                                .setClassName(
                                        context.getPackageName(),
                                        "top.kuaner.pockete2.app.MainActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        check(screen != null, "Activity cold start");
        runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        PocketController controller = PocketController.get(context);
        long until = android.os.SystemClock.elapsedRealtime() + 4000;
        while (controller.snapshot() == null && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(10);
        check(controller.snapshot() != null, "asynchronous initial snapshot available");
        check(
                context.getResources().getDisplayMetrics().widthPixels >= 240
                        && context.getResources().getDisplayMetrics().widthPixels <= 480,
                "compact viewport measured independently of physical panel");
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        if (image != null) {
            java.io.File dir = new java.io.File(context.getCacheDir(), "qa");
            dir.mkdirs();
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new java.io.File(dir, "cold-start.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            image.recycle();
        }
        if (!controller.configured()) {
            boolean originalVoice = controller.settings.voiceInput();
            String originalDraft = controller.draft();
            long originalCursor = controller.snapshot().cursor;
            java.util.List<String> originalRows = new java.util.ArrayList<>();
            for (Message row : controller.snapshot().rows) originalRows.add(row.id);
            controller.startTrying();
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (!controller.snapshot().trying
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10);
            controller.setVoiceInput(false);
            runOnMainSync(
                    () -> ((top.kuaner.pockete2.app.MainActivity) screen).navigate("conversation"));
            waitForIdleSync();
            Thread.sleep(120);
            final android.widget.EditText[] composer = {null};
            runOnMainSync(
                    () -> {
                        composer[0] = input(screen.getWindow().getDecorView());
                        if (composer[0] != null) {
                            composer[0].setText("QA retained draft");
                            composer[0].requestFocus();
                            screen.getSystemService(
                                            android.view.inputmethod.InputMethodManager.class)
                                    .showSoftInput(
                                            composer[0],
                                            android.view.inputmethod.InputMethodManager
                                                    .SHOW_IMPLICIT);
                        }
                    });
            check(composer[0] != null, "text composer reachable on compact viewport");
            Thread.sleep(750);
            waitForIdleSync();
            android.graphics.Rect visible = new android.graphics.Rect(),
                    bounds = new android.graphics.Rect();
            runOnMainSync(
                    () -> {
                        screen.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
                        composer[0].getGlobalVisibleRect(bounds);
                    });
            if (context.getResources().getDisplayMetrics().heightPixels - visible.bottom < 140) {
                runOnMainSync(
                        () ->
                                screen.getSystemService(
                                                android.view.inputmethod.InputMethodManager.class)
                                        .showSoftInput(
                                                composer[0],
                                                android.view.inputmethod.InputMethodManager
                                                        .SHOW_FORCED));
                Thread.sleep(750);
                runOnMainSync(
                        () -> {
                            screen.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
                            composer[0].getGlobalVisibleRect(bounds);
                        });
            }
            check(
                    context.getResources().getDisplayMetrics().heightPixels - visible.bottom >= 140,
                    "real system IME is visible for avoidance check");
            check(
                    bounds.height() > 0 && bounds.bottom <= visible.bottom,
                    "composer stays above IME");
            screenshot(context, "conversation-keyboard");
            runOnMainSync(
                    () -> ((top.kuaner.pockete2.app.MainActivity) screen).navigate("settings"));
            runOnMainSync(
                    () -> ((top.kuaner.pockete2.app.MainActivity) screen).navigate("conversation"));
            waitForIdleSync();
            check(
                    composer[0].getText().toString().equals("QA retained draft"),
                    "page switches retain draft and view instance");
            controller.draft("QA sample");
            controller.sendDraft();
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (controller.snapshot().rows.stream()
                            .noneMatch(
                                    row ->
                                            row.kind == Message.DUODUO
                                                    && row.id.startsWith("ans:")
                                                    && row.seq >= 0
                                                    && row.text.contains("体验模式"))
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.snapshot().rows.stream()
                            .anyMatch(
                                    row ->
                                            row.kind == Message.DUODUO
                                                    && row.id.startsWith("ans:")
                                                    && row.seq >= 0
                                                    && row.text.contains("体验模式")),
                    "try final settles through the ordinary channel log");
            check(
                    controller.snapshot().outbox.isEmpty(),
                    "try log admission settles the ordinary outbox");
            Message prepared =
                    controller.snapshot().rows.stream()
                            .filter(row -> row.kind == Message.DUODUO && row.id.startsWith("ans:"))
                            .findFirst()
                            .get();
            controller.readAloud(prepared);
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (!controller.snapshot().readingId.equals(prepared.id)
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            Thread.sleep(120);
            check(
                    controller.snapshot().readingId.equals(prepared.id)
                            && !controller.snapshot().lastError.contains("朗读"),
                    "prepared read aloud streams through the normal speech path");
            controller.readAloud(prepared);
            until = android.os.SystemClock.elapsedRealtime() + 2000;
            while (!controller.snapshot().readingId.isEmpty()
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.snapshot().readingId.isEmpty(),
                    "prepared read aloud cancels through normal playback ownership");
            controller.draft("QA tools");
            controller.sendDraft();
            until = android.os.SystemClock.elapsedRealtime() + 7000;
            while (controller.snapshot().traces.isEmpty()
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    !controller.snapshot().traces.isEmpty(),
                    "try tools use the real TurnState reducer");
            controller.showSessions(true);
            controller.openSession("try-chat");
            Thread.sleep(150);
            controller.notifySession("QA local notification");
            Thread.sleep(150);
            check(
                    controller.snapshot().sessions.outcome.equals("sent")
                            && controller.snapshot().sessions.rows.stream()
                                    .anyMatch(r -> r.text.contains("未发送")),
                    "try scoped notify stays local and labelled");
            java.io.File photo = imageFile(context);
            final boolean[] pasted = {false};
            runOnMainSync(
                    () -> {
                        android.view.inputmethod.EditorInfo editor =
                                new android.view.inputmethod.EditorInfo();
                        android.view.inputmethod.InputConnection connection =
                                composer[0].onCreateInputConnection(editor);
                        pasted[0] =
                                editor.contentMimeTypes != null
                                        && connection.commitContent(
                                                new android.view.inputmethod.InputContentInfo(
                                                        top.kuaner.pockete2.app.PocketFiles.uri(
                                                                context, photo),
                                                        new android.content.ClipDescription(
                                                                "QA image",
                                                                new String[] {"image/jpeg"}),
                                                        null),
                                                0,
                                                null);
                    });
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while ((controller.snapshot().chips.isEmpty()
                            || controller.snapshot().chips.get(0).state != Attachment.UPLOADED)
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    pasted[0]
                            && controller.snapshot().chips.size() == 1
                            && controller.snapshot().chips.get(0).inlineImage(),
                    "keyboard image content enters the normal attachment pipeline");
            check(
                    controller.snapshot().chips.get(0).sha256.matches("[0-9a-f]{64}"),
                    "try image upload uses its actual digest");
            String imported = controller.snapshot().chips.get(0).localPath;
            runOnMainSync(
                    () -> {
                        ((top.kuaner.pockete2.app.MainActivity) screen).navigate("conversation");
                        screen.getSystemService(android.view.inputmethod.InputMethodManager.class)
                                .hideSoftInputFromWindow(composer[0].getWindowToken(), 0);
                    });
            waitForIdleSync();
            Thread.sleep(250);
            screenshot(context, "conversation-image-draft");
            controller.draft("QA image");
            controller.sendDraft();
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (controller.snapshot().rows.stream()
                            .noneMatch(
                                    row ->
                                            row.kind == Message.MINE
                                                    && row.seq >= 0
                                                    && !row.attachments.isEmpty())
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            screenshot(context, "conversation-image-sent");
            Attachment delivered =
                    controller.snapshot().rows.stream()
                            .filter(
                                    row ->
                                            row.kind == Message.MINE
                                                    && row.seq >= 0
                                                    && !row.attachments.isEmpty())
                            .findFirst()
                            .get()
                            .attachments
                            .get(0);
            java.util.concurrent.CountDownLatch downloaded =
                    new java.util.concurrent.CountDownLatch(1);
            java.io.File[] downloadedFile = {null};
            controller.download(
                    delivered,
                    new PocketController.FileResult() {
                        public void ready(java.io.File file, String mime) {
                            downloadedFile[0] = file;
                            downloaded.countDown();
                        }

                        public void failed() {
                            downloaded.countDown();
                        }
                    });
            check(
                    downloaded.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            && downloadedFile[0] != null
                            && java.util.Arrays.equals(
                                    java.nio.file.Files.readAllBytes(downloadedFile[0].toPath()),
                                    java.nio.file.Files.readAllBytes(photo.toPath())),
                    "try uploaded photo downloads through actual channel bytes");
            android.net.Uri sharedPhoto =
                    top.kuaner.pockete2.app.PocketFiles.uri(context, downloadedFile[0]);
            try (java.io.InputStream in =
                    context.getContentResolver().openInputStream(sharedPhoto)) {
                check(
                        in != null
                                && in.read()
                                        == (java.nio.file.Files.readAllBytes(photo.toPath())[0]
                                                & 255),
                        "downloaded try file is reachable through the grantable content provider");
            }
            photo.delete();
            if (arguments != null && "1".equals(arguments.getString("capture"))) {
                controller.ambientToggle();
                until = android.os.SystemClock.elapsedRealtime() + 4000;
                while (controller.snapshot().ambient != PocketController.Ambient.SPEAKING
                        && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
                check(
                        controller.snapshot().ambient == PocketController.Ambient.SPEAKING,
                        "try ambient hello follows capture and plays actual prepared packets");
                runOnMainSync(
                        () -> ((top.kuaner.pockete2.app.MainActivity) screen).navigate("ambient"));
                waitForIdleSync();
                screenshot(context, "ambient-room");
                boolean originalTranscript = controller.settings.ambientTranscript();
                controller.settings.ambientTranscript(true);
                controller.clearNotice();
                Thread.sleep(120);
                waitForIdleSync();
                check(
                        visibleInput(screen.getWindow().getDecorView()) == null,
                        "expanded ambient conversation has no visible editable composer");
                screenshot(context, "ambient-transcript");
                controller.settings.ambientTranscript(originalTranscript);
                controller.endAmbient();
            }
            until = android.os.SystemClock.elapsedRealtime() + 8000;
            while (controller.snapshot().working != null
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            visualVariant(context, screen, controller, false, 1.35f);
            visualVariant(context, screen, controller, true, 1.0f);
            controller.stopTrying();
            controller.setVoiceInput(originalVoice);
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (controller.snapshot().trying && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(20);
            check(!new java.io.File(imported).exists(), "try attachment bytes removed on exit");
            check(
                    downloadedFile[0] != null && !downloadedFile[0].exists(),
                    "try downloaded bytes removed on exit");
            java.util.List<String> restoredRows = new java.util.ArrayList<>();
            for (Message row : controller.snapshot().rows) restoredRows.add(row.id);
            check(
                    controller.snapshot().draft.equals(originalDraft)
                            && controller.snapshot().cursor == originalCursor
                            && restoredRows.equals(originalRows),
                    "try exit restores the actual room draft, rows and cursor");
        }
        runOnMainSync(screen::finish);
    }

    private void visualVariant(
            Context context,
            Activity screen,
            PocketController controller,
            boolean light,
            float scale)
            throws Exception {
        top.kuaner.pockete2.ui.pages.ConversationPage[] preview = {null};
        android.app.Dialog[] dialog = {null};
        runOnMainSync(
                () -> {
                    android.content.res.Configuration configuration =
                            new android.content.res.Configuration(
                                    context.getResources().getConfiguration());
                    configuration.fontScale = scale;
                    Context scoped =
                            new android.view.ContextThemeWrapper(
                                    context.createConfigurationContext(configuration),
                                    light
                                            ? top.kuaner.pockete2.R.style.PocketLightTheme
                                            : top.kuaner.pockete2.R.style.PocketTheme);
                    top.kuaner.pockete2.ui.Ui variant =
                            new top.kuaner.pockete2.ui.Ui(
                                    scoped,
                                    light
                                            ? top.kuaner.pockete2.ui.ThemePalette.light()
                                            : top.kuaner.pockete2.ui.ThemePalette.dark());
                    top.kuaner.pockete2.ui.PageHost host =
                            new top.kuaner.pockete2.ui.PageHost() {
                                public Activity activity() {
                                    return screen;
                                }

                                public top.kuaner.pockete2.ui.Ui ui() {
                                    return variant;
                                }

                                public void navigate(String ignored) {}

                                public void back() {}

                                public void toast(String ignored) {}

                                public void permission(String[] ignored, Runnable action) {}

                                public void pickAttachment(String ignored) {}

                                public void takePhoto() {}

                                public void openAttachment(Attachment ignored) {}

                                public void loginTailnet() {}
                            };
                    preview[0] =
                            new top.kuaner.pockete2.ui.pages.ConversationPage(host, controller);
                    android.widget.LinearLayout shell = variant.column();
                    shell.setBackgroundColor(variant.palette.background);
                    shell.setPadding(0, variant.dp(24), 0, 0);
                    shell.addView(
                            preview[0].render(),
                            new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
                    shell.addView(
                            new top.kuaner.pockete2.ui.components.BottomNav(variant, ignored -> {})
                                    .render("conversation"));
                    dialog[0] =
                            new android.app.Dialog(
                                    screen,
                                    light
                                            ? top.kuaner.pockete2.R.style.PocketLightTheme
                                            : top.kuaner.pockete2.R.style.PocketTheme);
                    dialog[0].setContentView(shell);
                    dialog[0].show();
                    dialog[0].getWindow().setLayout(-1, -1);
                    dialog[0]
                            .getWindow()
                            .getDecorView()
                            .setSystemUiVisibility(
                                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                });
        try {
            waitForIdleSync();
            Thread.sleep(200);
            android.graphics.Rect bounds = new android.graphics.Rect();
            runOnMainSync(
                    () -> {
                        android.widget.EditText input =
                                visibleInput(dialog[0].getWindow().getDecorView());
                        if (input != null) input.getGlobalVisibleRect(bounds);
                    });
            check(
                    bounds.height() >= 40
                            && bounds.right
                                    <= context.getResources().getDisplayMetrics().widthPixels,
                    light
                            ? "light-theme compact composer reachable"
                            : "large-font compact composer reachable");
            screenshot(context, light ? "conversation-light" : "conversation-large-font");
        } finally {
            runOnMainSync(
                    () -> {
                        preview[0].close();
                        dialog[0].dismiss();
                    });
        }
    }

    private android.widget.EditText visibleInput(android.view.View view) {
        if (!view.isShown()) return null;
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = visibleInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void screenshot(Context context, String name) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        java.io.File directory = new java.io.File(context.getExternalCacheDir(), "qa");
        directory.mkdirs();
        try (java.io.FileOutputStream out =
                new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
    }

    private void qrCamera(Context context) throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        String[] failure = {""};
        top.kuaner.pockete2.pairing.QrCapture capture =
                new top.kuaner.pockete2.pairing.QrCapture(
                        context,
                        new top.kuaner.pockete2.pairing.QrCapture.Listener() {
                            public void preview(android.graphics.Bitmap bitmap) {
                                bitmap.recycle();
                                first.countDown();
                            }

                            public void code(String ignored) {
                                first.countDown();
                            }

                            public void failed(String why) {
                                failure[0] = why;
                                first.countDown();
                            }
                        });
        try {
            check(
                    first.await(8, TimeUnit.SECONDS) && failure[0].isEmpty(),
                    "real QR camera frame: " + failure[0]);
        } finally {
            capture.close();
            check(capture.awaitStopped(4_000), "QR camera teardown before reuse");
        }
    }

    private void sightSession(Context context) throws Exception {
        java.util.concurrent.ExecutorService serial =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.ExecutorService closer =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch first = new CountDownLatch(2),
                resumed = new CountDownLatch(1),
                rotated = new CountDownLatch(1);
        AtomicInteger phase = new AtomicInteger(1), declines = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong lastSeq =
                new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicBoolean monotone =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>
                firstConfig = new java.util.concurrent.atomic.AtomicReference<>(),
                rotatedConfig = new java.util.concurrent.atomic.AtomicReference<>();
        top.kuaner.pockete2.sight.SightSession session =
                new top.kuaner.pockete2.sight.SightSession(
                        context,
                        serial,
                        closer,
                        () -> true,
                        () -> false,
                        new top.kuaner.pockete2.sight.SightSession.Listener() {
                            public void changed() {}

                            public void text(org.json.JSONObject frame) {
                                if (frame.optString("type").equals("camera_declined"))
                                    declines.incrementAndGet();
                            }

                            public void binary(byte[] frame) {
                                java.nio.ByteBuffer header =
                                        java.nio.ByteBuffer.wrap(frame)
                                                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                                long seq = header.getInt(2) & 0xffffffffL;
                                if (seq <= lastSeq.getAndSet(seq)) monotone.set(false);
                                if ((frame[1] & 1) != 0) {
                                    int size = header.getShort(14) & 65535;
                                    try {
                                        org.json.JSONObject config =
                                                new org.json.JSONObject(
                                                        new String(
                                                                frame,
                                                                16,
                                                                size,
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                                        if (phase.get() < 3) firstConfig.set(config);
                                        if (phase.get() == 3) rotatedConfig.set(config);
                                    } catch (org.json.JSONException invalid) {
                                        monotone.set(false);
                                    }
                                }
                                if (phase.get() == 1) first.countDown();
                                else if (phase.get() == 2) resumed.countDown();
                                else if ((frame[1] & 1) != 0) rotated.countDown();
                            }
                        });
        org.json.JSONObject ask =
                Json.object(
                        "type",
                        "camera",
                        "max_frame_bytes",
                        1024 * 1024,
                        "capture",
                        Json.object(
                                "width",
                                640,
                                "height",
                                480,
                                "video",
                                Json.object(
                                        "codec",
                                        "avc1.42e01f",
                                        "fps",
                                        5,
                                        "min_fps",
                                        1,
                                        "bitrate",
                                        256000,
                                        "key_interval_ms",
                                        1000)));
        try {
            boolean gated =
                    serial.submit(
                                    () -> {
                                        session.update(true, true, true, true, 0);
                                        session.asked(ask);
                                        return session.snapshot().offered
                                                && !session.snapshot().on
                                                && declines.get() == 1;
                                    })
                            .get(3, TimeUnit.SECONDS);
            check(gated, "SightSession requires explicit opt-in after server ask");
            session.previewWanted(true);
            serial.submit(session::toggle).get(3, TimeUnit.SECONDS);
            check(
                    first.await(10, TimeUnit.SECONDS),
                    "SightSession admits real native video after opt-in");
            long previewUntil = android.os.SystemClock.elapsedRealtime() + 3000;
            while (session.preview() == null
                    && android.os.SystemClock.elapsedRealtime() < previewUntil) Thread.sleep(10);
            check(
                    session.preview() != null,
                    "SightSession exposes the current rear viewfinder without another camera");
            final android.graphics.Bitmap[] rendered = {null};
            runOnMainSync(
                    () -> {
                        top.kuaner.pockete2.ui.Ui ui = new top.kuaner.pockete2.ui.Ui(context);
                        top.kuaner.pockete2.ui.components.SightViewfinder view =
                                new top.kuaner.pockete2.ui.components.SightViewfinder(
                                        ui, session::preview, ignored -> {});
                        view.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(
                                        ui.dp(235), android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(
                                        ui.dp(180), android.view.View.MeasureSpec.EXACTLY));
                        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
                        rendered[0] =
                                android.graphics.Bitmap.createBitmap(
                                        view.getWidth(),
                                        view.getHeight(),
                                        android.graphics.Bitmap.Config.ARGB_8888);
                        view.draw(new android.graphics.Canvas(rendered[0]));
                    });
            java.io.File previewDirectory = new java.io.File(context.getExternalCacheDir(), "qa");
            previewDirectory.mkdirs();
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(
                            new java.io.File(previewDirectory, "sight-viewfinder.png"))) {
                rendered[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                rendered[0].recycle();
            }
            check(
                    !serial.submit(() -> session.snapshot().canFlip).get(3, TimeUnit.SECONDS),
                    "single-camera E2 does not offer an unavailable lens switch");
            serial.submit(() -> session.update(true, true, true, false, 0))
                    .get(3, TimeUnit.SECONDS);
            closer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            serial.submit(
                            () -> {
                                phase.set(2);
                                session.update(true, true, true, true, 0);
                            })
                    .get(3, TimeUnit.SECONDS);
            check(
                    resumed.await(10, TimeUnit.SECONDS),
                    "SightSession releases then resumes camera on foreground return");
            serial.submit(
                            () -> {
                                phase.set(3);
                                session.orientation((session.snapshot().rotation + 90) % 360);
                            })
                    .get(3, TimeUnit.SECONDS);
            check(
                    rotated.await(10, TimeUnit.SECONDS)
                            && firstConfig.get() != null
                            && rotatedConfig.get() != null
                            && firstConfig.get().optInt("width")
                                    == rotatedConfig.get().optInt("height")
                            && firstConfig.get().optInt("height")
                                    == rotatedConfig.get().optInt("width")
                            && monotone.get(),
                    "camera orientation restarts with swapped dimensions and preserves connection"
                        + " sequence");
        } finally {
            serial.submit(session::clear).get(3, TimeUnit.SECONDS);
            closer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            serial.shutdown();
            closer.shutdown();
        }
    }

    private static android.widget.EditText input(android.view.View view) {
        if (view instanceof android.widget.EditText
                && String.valueOf(((android.widget.EditText) view).getHint()).contains("发消息"))
            return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText result = input(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
