// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.audio.PacketStream;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.ChannelClient;
import top.kuaner.pockete2.net.ChannelConnection;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Explicit opt-in: one marked synthetic note to actual ASR. The private journal prevents repeats.
 */
final class VoiceRoomProbe {
    private static final String EXPECTED = "语音链路验证成功";
    private static final String TEXT = "这是 Pocket E2 集成测试的语音消息。请只回复：" + EXPECTED + "。";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private Activity activity;
    private int assertions;

    VoiceRoomProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void channel(ChannelConnection channel) throws Exception {
        Field field = PocketController.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(controller, channel);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(25);
    }

    private void checkpoint(String text) {
        android.os.Bundle report = new android.os.Bundle();
        report.putString("checkpoint", text + "; assertions=" + assertions);
        probe.sendStatus(0, report);
    }

    private boolean uploadService(Context context) {
        for (android.app.ActivityManager.RunningServiceInfo service :
                context.getSystemService(android.app.ActivityManager.class).getRunningServices(40))
            if (service.service
                            .getClassName()
                            .equals("top.kuaner.pockete2.app.PocketSessionService")
                    && service.foreground) return true;
        return false;
    }

    String run(Context context) throws Exception {
        activity =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        activity.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                30_000);
        check(
                controller.configured()
                        && controller.snapshot().connection == PocketController.Connection.ONLINE
                        && !controller.snapshot().trying,
                "real voice acceptance starts in the authorized actual room");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.chips.stream().noneMatch(a -> a.state == Attachment.UPLOADING),
                "real voice acceptance requires capture, camera, imports and playback idle");
        String target = initial.importTarget, draft = initial.draft, quote = initial.quote;
        List<String> chips = new ArrayList<>();
        for (Attachment chip : initial.chips) chips.add(chip.localPath);
        File qa = new File(context.getFilesDir(), "qa");
        qa.mkdirs();
        File journal = new File(qa, "voice-room-once.json");
        File prior = new File(context.getCacheDir(), "qa/voice-room-once.json");
        if (!journal.exists() && prior.exists() && !prior.renameTo(journal))
            throw new java.io.IOException("Could not retain the original voice-test journal");
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        ChannelClient client = new ChannelClient(controller.settings, original);
        Gate gate = null;
        boolean fresh = !journal.exists();
        try {
            JSONObject record;
            if (fresh) {
                check(
                        initial.outbox.isEmpty() && initial.voiceAvailable,
                        "a new marked fixture starts only with an idle outbox and available voice"
                                + " service");
                byte[] body = client.readAloud(TEXT);
                PacketStream stream = new PacketStream();
                int count = 0;
                for (int offset = 0; offset < body.length; offset += 4096)
                    count +=
                            stream.push(
                                            Arrays.copyOfRange(
                                                    body,
                                                    offset,
                                                    Math.min(body.length, offset + 4096)))
                                    .size();
                stream.finish();
                check(
                        count > 0,
                        "the actual synthesis service supplies a framed known-speech fixture");
                String id = UUID.randomUUID().toString();
                File dir = new File(context.getFilesDir(), "voice");
                dir.mkdirs();
                File file = new File(dir, id + ".packets");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(body);
                    out.getFD().sync();
                }
                record =
                        Json.object(
                                "id",
                                id,
                                "target",
                                target,
                                "since",
                                initial.cursor,
                                "duration",
                                count * 20L,
                                "path",
                                file.getPath(),
                                "at",
                                System.currentTimeMillis());
                android.util.AtomicFile atomic = new android.util.AtomicFile(journal);
                java.io.FileOutputStream out = atomic.startWrite();
                try {
                    out.write(record.toString().getBytes(StandardCharsets.UTF_8));
                    atomic.finishWrite(out);
                } catch (Throwable failure) {
                    atomic.failWrite(out);
                    throw failure;
                }
            } else {
                record =
                        new JSONObject(
                                new String(
                                        Files.readAllBytes(journal.toPath()),
                                        StandardCharsets.UTF_8));
            }
            String id = record.getString("id");
            check(
                    UUID.fromString(id).toString().equals(id),
                    "the private once-only journal retains the original idempotency key");
            check(
                    initial.outbox.stream().allMatch(item -> item.id.equals(id)),
                    "resuming acceptance never races unrelated outgoing messages");
            RoomRepository repo = onState(() -> (RoomRepository) field("repo"));
            Message logged = onState(() -> repo.row(id));
            check(
                    record.has("target")
                            ? record.optString("target").equals(target)
                            : logged != null && logged.seq >= 0,
                    "the retained fixture belongs to the current room before any request can"
                            + " resume");
            if (logged == null) {
                gate = new Gate(original, id);
                Gate active = gate;
                onState(
                        () -> {
                            channel(active);
                            if (repo.findPending(id) == null) {
                                Outbox.Item item =
                                        new Outbox.Item(
                                                        id,
                                                        "",
                                                        true,
                                                        record.getString("path"),
                                                        "phone",
                                                        record.getLong("duration"),
                                                        Collections.emptyList(),
                                                        Message.QUEUED,
                                                        "",
                                                        "",
                                                        record.getLong("at"),
                                                        0)
                                                .surface("phone", false, "");
                                repo.enqueue(
                                        Message.mine(
                                                id,
                                                "",
                                                "phone",
                                                item.durationMs,
                                                Message.QUEUED,
                                                Collections.emptyList()),
                                        item);
                            }
                            invoke("reload");
                            invoke("pump");
                            return null;
                        });
                check(
                        active.started.await(5, TimeUnit.SECONDS),
                        "the normal controller loads the known fixture and starts its actual voice"
                                + " request");
                await(() -> uploadService(context), 3000);
                check(uploadService(context), "Android runs the actual foreground upload service");
                probe.runOnMainSync(() -> activity.moveTaskToBack(true));
                await(
                        () -> {
                            try {
                                return !onState(() -> (Boolean) field("foreground"));
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        },
                        2000);
                check(
                        !onState(() -> (Boolean) field("foreground"))
                                && uploadService(context)
                                && controller.snapshot().uploadingVoice,
                        "moving the real Activity to the background retains the upload task");
                active.release.countDown();
                checkpoint(
                        "one marked synthetic voice fixture released to actual ASR in background");
            }
            await(
                    () ->
                            controller.snapshot().rows.stream()
                                            .anyMatch(row -> row.id.equals(id) && row.seq >= 0)
                                    || controller.snapshot().outbox.stream()
                                            .anyMatch(
                                                    item ->
                                                            item.id.equals(id)
                                                                    && item.state
                                                                            == Message.FAILED),
                    90_000);
            Message row = onState(() -> repo.row(id));
            check(
                    row != null
                            && row.seq >= 0
                            && row.kind == Message.MINE
                            && row.delivery == Message.DELIVERED
                            && row.durationMs == record.getLong("duration")
                            && row.text.contains("语音")
                            && row.text.contains("测试")
                            && row.text.contains(EXPECTED),
                    "actual ASR recognizes the marked known speech and logs the delivered voice"
                            + " row");
            check(
                    onState(() -> repo.findPending(id)) == null
                            && !new File(record.getString("path")).exists(),
                    "the actual logged row atomically settles outbox state and removes the note"
                            + " file");
            await(() -> !uploadService(context) && !controller.snapshot().uploadingVoice, 5000);
            check(
                    !uploadService(context) && !controller.snapshot().uploadingVoice,
                    "actual acceptance ends the background upload service");
            await(
                    () ->
                            controller.snapshot().rows.stream()
                                    .anyMatch(
                                            answer ->
                                                    answer.kind == Message.DUODUO
                                                            && answer.at >= record.optLong("at")
                                                            && answer.text.contains(EXPECTED)),
                    90_000);
            check(
                    controller.snapshot().rows.stream()
                            .anyMatch(
                                    answer ->
                                            answer.kind == Message.DUODUO
                                                    && answer.at >= record.optLong("at")
                                                    && answer.text.contains(EXPECTED)),
                    "the actual brain supplies the requested voice-test reply");
            ChannelClient.Rows rows = client.rows(record.getLong("since"));
            check(
                    rows.messages.stream()
                                    .filter(
                                            candidate ->
                                                    candidate.kind == Message.MINE
                                                            && candidate.id.equals(id))
                                    .count()
                            == 1,
                    "the actual room log holds exactly one copy of this voice message");
            List<String> afterChips = new ArrayList<>();
            for (Attachment chip : controller.snapshot().chips) afterChips.add(chip.localPath);
            check(
                    controller.snapshot().importTarget.equals(target)
                            && controller.draft().equals(draft)
                            && controller.snapshot().quote.equals(quote)
                            && afterChips.equals(chips),
                    "actual voice acceptance preserves the original composer and attachments");
            return "PASS "
                    + assertions
                    + " real voice room assertions; "
                    + (fresh
                            ? "one marked synthetic note, actual ASR/delivery/reply and background"
                                    + " upload"
                            : "cold-restart verification reuses the private journal without sending"
                                    + " again");
        } finally {
            if (gate != null) gate.release.countDown();
            onState(
                    () -> {
                        channel(original);
                        return null;
                    });
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    }

    private static final class Gate implements ChannelConnection {
        final ChannelConnection delegate;
        final String id;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);

        Gate(ChannelConnection delegate, String id) {
            this.delegate = delegate;
            this.id = id;
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            if (headers.optString("Idempotency-Key").equals(id)) {
                started.countDown();
                if (!release.await(12, TimeUnit.SECONDS))
                    throw new AssertionError("Background voice gate timed out");
            }
            return delegate.request(endpoint, token, method, path, headers, body, timeout);
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current)
                throws Exception {
            return delegate.stream(endpoint, token, id, path, body, listener, current);
        }

        public void cancelStream(long id) {
            delegate.cancelStream(id);
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current)
                throws Exception {
            delegate.live(endpoint, token, path, headers, listener, current);
        }

        public void send(JSONObject frame) throws Exception {
            delegate.send(frame);
        }

        public void sendBinary(byte[] packet) throws Exception {
            delegate.sendBinary(packet);
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void closeLive() {
            delegate.closeLive();
        }
    }
}
