// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.input.RecordingInput;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Real capture/process/SQLite boundaries in a QA namespace; this transport cannot reach a server.
 */
final class VoiceDeathProbe {
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private RoomRepository repository;
    private VoiceInbox inbox;
    private Context context;
    private final Source source = new Source();
    private int assertions;

    VoiceDeathProbe(Probe probe) {
        this.probe = probe;
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void field(String name, Object value) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        state.execute(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private boolean online() {
        return controller.snapshot() != null
                && controller.snapshot().connection == PocketController.Connection.ONLINE;
    }

    private File journal() {
        return new File(context.getFilesDir(), "qa/voice-death.json");
    }

    private JSONObject read() throws Exception {
        return new JSONObject(
                new String(Files.readAllBytes(journal().toPath()), StandardCharsets.UTF_8));
    }

    private void write(JSONObject record) throws Exception {
        journal().getParentFile().mkdirs();
        AtomicFile atomic = new AtomicFile(journal());
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(record.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (Throwable failure) {
            atomic.failWrite(output);
            throw failure;
        }
    }

    private String scope(JSONObject record) throws Exception {
        return "qa:voice-death:" + record.getString("fixture");
    }

    private String digest(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private File packets(JSONObject record, String key) throws Exception {
        String id = record.getString(key);
        if (!UUID.fromString(id).toString().equals(id))
            throw new IllegalStateException("Invalid fixture id");
        return new File(context.getFilesDir(), "voice/" + id + ".packets");
    }

    private List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Attachment chip : controller.snapshot().chips) paths.add(chip.localPath);
        return paths;
    }

    private void preserved(JSONObject record) throws Exception {
        PocketController.Snapshot snapshot = controller.snapshot();
        check(
                online()
                        && !snapshot.trying
                        && !RecordingInput.isActive()
                        && snapshot.outbox.isEmpty()
                        && snapshot.importTarget.equals(record.getString("target"))
                        && snapshot.draft.equals(record.getString("draft"))
                        && snapshot.quote.equals(record.getString("quote"))
                        && paths().equals(Json.strings(record.getJSONArray("chips"))),
                "ordinary cold launch preserves the actual paired room, composer and idle"
                        + " microphone without sending QA recordings; online="
                        + online()
                        + ", nativeCapture="
                        + RecordingInput.isActive()
                        + ", outbox="
                        + snapshot.outbox.size()
                        + ", targetMatches="
                        + snapshot.importTarget.equals(record.getString("target"))
                        + ", draftMatches="
                        + snapshot.draft.equals(record.getString("draft"))
                        + ", quoteMatches="
                        + snapshot.quote.equals(record.getString("quote"))
                        + ", chipsMatch="
                        + paths().equals(Json.strings(record.getJSONArray("chips"))));
    }

    private void isolate(JSONObject record) throws Exception {
        onState(
                () -> {
                    invoke("retireRoomAccess");
                    field("connectionEpoch", (Integer) field("connectionEpoch") + 1);
                    ((ChannelConnection) field("channel")).closeLive();
                    repository.select(scope(record));
                    field("channel", source);
                    field("roomState", null);
                    field("connection", PocketController.Connection.ONLINE);
                    field("opening", false);
                    field("retryNotBefore", 0L);
                    invoke("reload");
                    invoke("publish");
                    return null;
                });
    }

    private void waitForKill(String checkpoint) throws Exception {
        Bundle status = new Bundle();
        status.putString("checkpoint", checkpoint);
        status.putInt("assertions", assertions);
        probe.sendStatus(0, status);
        new CountDownLatch(1).await(30, TimeUnit.SECONDS);
        throw new IllegalStateException("Host did not terminate the live voice checkpoint");
    }

    private VoiceInbox.Note staged(String id) throws Exception {
        return ((VoiceNotes) field("voiceNotes"))
                .retained(repository.roomIdentity()).stream()
                        .filter(note -> note.id.equals(id))
                        .findFirst()
                        .orElse(null);
    }

    Bundle run(Context context, String phase) throws Exception {
        this.context = context;
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        repository = (RoomRepository) field("realRepo");
        inbox = new VoiceInbox(new File(context.getFilesDir(), "voice"));
        await(this::online, 35_000);
        check(
                online() && !controller.snapshot().trying,
                "voice crash acceptance starts from the actual paired Online room");
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        if (phase.equals("prepare")) {
            check(
                    !journal().exists()
                            && !RecordingInput.isActive()
                            && controller.snapshot().outbox.isEmpty()
                            && !controller.snapshot().ambientActive
                            && !controller.snapshot().holdRecording
                            && !controller.snapshot().uploadingVoice
                            && !controller.snapshot().cameraOn
                            && controller.snapshot().readingId.isEmpty()
                            && controller.snapshot().imports.isEmpty(),
                    "new crash acceptance requires idle hardware and no unfinished prior fixture");
            JSONObject record =
                    Json.object(
                            "fixture",
                            UUID.randomUUID().toString(),
                            "target",
                            controller.snapshot().importTarget,
                            "draft",
                            controller.draft(),
                            "quote",
                            controller.snapshot().quote,
                            "chips",
                            new JSONArray(paths()));
            write(record);
            isolate(record);
            controller.beginHold();
            await(
                    () ->
                            controller.snapshot().holdLive
                                    && controller.snapshot().holdDurationMs >= 1300,
                    6000);
            File file = onState(() -> ((VoiceNotes) field("voiceNotes")).file());
            check(
                    file != null && RecordingInput.isActive() && file.length() > 0,
                    "the real E2 holds its native recording with complete frames flushed before"
                            + " termination");
            byte[] prefix = Files.readAllBytes(file.toPath());
            record.put("recordingId", file.getName().replace(".packets", ""));
            record.put("prefixBytes", prefix.length);
            record.put("prefixDigest", digest(prefix));
            write(record);
            check(
                    source.calls == 0 && onState(repository::pending).isEmpty(),
                    "the live unfinished recording has neither an upload nor a SQLite outbox"
                            + " owner");
            waitForKill("voice-recording-live");
        } else if (phase.equals("afterRecording")) {
            JSONObject record = read();
            preserved(record);
            String id = record.getString("recordingId");
            VoiceInbox.Note note = onState(() -> staged(id));
            byte[] bytes = Files.readAllBytes(packets(record, "recordingId").toPath());
            check(
                    note != null
                            && note.id.equals(id)
                            && note.room.equals(scope(record))
                            && note.item(false).state == Message.FAILED
                            && note.packets >= 50
                            && digest(Arrays.copyOf(bytes, record.getInt("prefixBytes")))
                                    .equals(record.getString("prefixDigest")),
                    "ordinary startup recovers the captured prefix under its original QA room/key"
                            + " and requires explicit retry");
            isolate(record);
            onState(
                    () -> {
                        invoke("recoverVoiceNotes");
                        invoke("reload");
                        invoke("publish");
                        return null;
                    });
            check(
                    onState(
                                    () ->
                                            repository.findPending(id).state == Message.FAILED
                                                    && repository.countRows() == 1)
                            && source.calls == 0
                            && !inbox.awaiting(id),
                    "activating the original room admits one interrupted note without automatically"
                            + " uploading it");
            controller.retry(id);
            await(
                    () ->
                            source.calls == 1
                                    && controller.snapshot().outbox.stream()
                                            .anyMatch(item -> item.state == Outbox.ACCEPTED),
                    3000);
            check(
                    source.calls == 1 && source.key.equals(id) && Arrays.equals(source.body, bytes),
                    "explicit retry uses the same key and surviving native recording bytes in the"
                            + " prepared transport");
            controller.discard(id);
            onState(() -> null);
            check(
                    !note.file.exists() && onState(repository::countRows) == 0,
                    "discard cleans the recovered recording without leaving a duplicate row");
            VoiceInbox.Note committing = inbox.begin(scope(record));
            record.put("commitId", committing.id);
            VoiceInbox.Note cancelled = inbox.begin(scope(record));
            record.put("cancelId", cancelled.id);
            write(record);
            ByteArrayOutputStream packets = new ByteArrayOutputStream();
            try (OpusEncoder encoder = new OpusEncoder()) {
                for (int i = 0; i < 25; i++) {
                    byte[] packet = encoder.encode(new short[320]);
                    packets.write(packet.length & 255);
                    packets.write(packet.length >>> 8);
                    packets.write(packet);
                }
            }
            byte[] silent = packets.toByteArray();
            Files.write(committing.file.toPath(), silent);
            VoiceInbox.Note ready = inbox.seal(committing, 25, false);
            inbox.remember(ready);
            Files.write(cancelled.file.toPath(), silent);
            Files.move(
                    new File(cancelled.file.getParentFile(), cancelled.id + ".json").toPath(),
                    new File(cancelled.file.getParentFile(), cancelled.id + ".discarded").toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
            record.put("commitDigest", digest(silent));
            write(record);
            onState(
                    () -> {
                        repository.enqueueVoice(ready);
                        return null;
                    });
            check(
                    inbox.awaiting(ready.id)
                            && onState(() -> repository.voiceReceipts().contains(ready.id))
                            && onState(
                                    () -> repository.findPending(ready.id).state == Message.QUEUED),
                    "the real SQLite commit retains an unacknowledged handoff while cancellation"
                            + " cleanup is also pending");
            waitForKill("voice-handoff-committed");
        } else if (phase.equals("afterCommit")) {
            JSONObject record = read();
            preserved(record);
            String id = record.getString("commitId");
            check(
                    !inbox.awaiting(id)
                            && !packets(record, "cancelId").exists()
                            && !inbox.awaiting(record.getString("cancelId")),
                    "ordinary startup acknowledges the committed handoff and completes"
                            + " cancelled-file cleanup");
            isolate(record);
            Outbox.Item item = onState(() -> repository.findPending(id));
            check(
                    item != null
                            && item.state == Message.QUEUED
                            && item.durationMs == 500
                            && digest(Files.readAllBytes(new File(item.voiceFile).toPath()))
                                    .equals(record.getString("commitDigest"))
                            && onState(repository::countRows) == 1
                            && onState(repository::voiceReceipts).isEmpty(),
                    "a crash after admission cannot replay or downgrade its one queued note or"
                            + " change bytes/duration");
            onState(
                    () -> {
                        invoke("pump");
                        return null;
                    });
            await(() -> source.calls == 1, 3000);
            onState(() -> null);
            check(
                    source.calls == 1
                            && source.key.equals(id)
                            && digest(source.body).equals(record.getString("commitDigest")),
                    "the original committed message uploads once under its unchanged key into the"
                            + " prepared sink");
            controller.discard(id);
            onState(() -> null);
            check(
                    !new File(item.voiceFile).exists() && onState(repository::countRows) == 0,
                    "discard removes the admitted recording before another cold launch");
        } else if (phase.equals("verify")) {
            JSONObject record = read();
            preserved(record);
            check(
                    onState(
                                    () ->
                                            staged(record.getString("recordingId")) == null
                                                    && staged(record.getString("commitId")) == null)
                            && !packets(record, "recordingId").exists()
                            && !packets(record, "commitId").exists()
                            && !packets(record, "cancelId").exists(),
                    "a further ordinary process restart cannot revive acknowledged or cancelled"
                            + " voice data");
        } else if (phase.equals("restore")) {
            if (journal().exists()) {
                JSONObject record = read();
                onState(
                        () -> {
                            invoke("retireRoomAccess");
                            ((ChannelConnection) field("channel")).closeLive();
                            repository.select(scope(record));
                            repository.clear();
                            for (VoiceInbox.Note note :
                                    inbox.pending(
                                                    repository.voiceReceipts(),
                                                    Collections.emptySet())
                                            .notes)
                                if (note.room.equals(scope(record))) inbox.discard(note);
                            for (String key : new String[] {"recordingId", "commitId", "cancelId"})
                                if (record.has(key))
                                    repository.acknowledgeVoice(record.getString(key));
                            VoiceNotes notes = (VoiceNotes) field("voiceNotes");
                            for (String key : new String[] {"recordingId", "commitId", "cancelId"})
                                if (record.has(key)) notes.discard(record.getString(key));
                            repository.select(controller.settings);
                            field("repo", repository);
                            field("channel", field("transport"));
                            field("connectionEpoch", (Integer) field("connectionEpoch") + 1);
                            field("opening", false);
                            field("retryNotBefore", 0L);
                            field("connection", PocketController.Connection.OFFLINE);
                            invoke("restoreDraft");
                            invoke("reload");
                            invoke("connect");
                            invoke("publish");
                            return null;
                        });
                await(this::online, 35_000);
                preserved(record);
                check(
                        journal().delete(),
                        "the scoped voice process-death journal is removed after restoration");
            }
        } else throw new IllegalArgumentException("Unknown voice-death phase");
        Bundle result = new Bundle();
        result.putString(
                "result", "PASS " + assertions + " native voice crash assertions in " + phase);
        return result;
    }

    private static final class Source implements ChannelConnection {
        volatile int calls;
        volatile String key;
        volatile byte[] body;

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            if (!method.equals("POST") || !path.startsWith("/api/messages?"))
                throw new AssertionError("Unexpected QA request");
            this.key = headers.optString("Idempotency-Key");
            this.body = body.clone();
            calls++;
            return new Response(202, new byte[0]);
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current) {
            throw new AssertionError("No speech in voice crash acceptance");
        }

        public void cancelStream(long id) {}

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current) {
            throw new AssertionError("No real socket in voice crash acceptance");
        }

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] bytes) {
            throw new AssertionError("Held recordings never stream in the QA room");
        }

        public boolean isOpen() {
            return true;
        }

        public void closeLive() {}
    }
}
