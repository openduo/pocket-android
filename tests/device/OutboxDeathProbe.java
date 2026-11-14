// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.audio.OpusEncoder;
import top.kuaner.pockete2.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real process termination before SQLite commit and after a deliberately failed file deletion. */
final class OutboxDeathProbe {
    private final Probe probe;
    private int assertions;

    OutboxDeathProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private File journal(Context context) {
        return new File(context.getFilesDir(), "qa/outbox-process-death.json");
    }

    private JSONObject read(Context context) throws Exception {
        return new JSONObject(
                new String(Files.readAllBytes(journal(context).toPath()), StandardCharsets.UTF_8));
    }

    private void write(Context context, JSONObject record) throws Exception {
        journal(context).getParentFile().mkdirs();
        AtomicFile atomic = new AtomicFile(journal(context));
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(record.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Throwable failure) {
            atomic.failWrite(out);
            throw failure;
        }
    }

    private File directory(Context context, JSONObject record) throws Exception {
        String id = record.getString("id");
        if (!UUID.fromString(id).toString().equals(id))
            throw new IllegalStateException("Invalid fixture id");
        return new File(context.getFilesDir(), "qa/outbox-death-" + id);
    }

    private String room(JSONObject record) throws Exception {
        return "qa:outbox-death:" + record.getString("id");
    }

    private List<String> paths(PocketController.Snapshot snapshot) {
        List<String> paths = new ArrayList<>();
        for (Attachment attachment : snapshot.chips) paths.add(attachment.localPath);
        return paths;
    }

    private void preserved(PocketController controller, JSONObject record) throws Exception {
        PocketController.Snapshot snapshot = controller.snapshot();
        List<String> before = Json.strings(record.getJSONArray("chips"));
        check(
                snapshot.importTarget.equals(record.getString("target"))
                        && snapshot.draft.equals(record.getString("draft"))
                        && snapshot.quote.equals(record.getString("quote"))
                        && paths(snapshot).equals(before)
                        && snapshot.outbox.isEmpty(),
                "the real paired room, draft, quote, attachments and empty outbox survive the"
                        + " actual process restart");
    }

    private void synced(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private void awaitTermination(String checkpoint) {
        Bundle status = new Bundle();
        status.putString("checkpoint", checkpoint);
        status.putInt("assertions", assertions);
        probe.sendStatus(0, status);
        try {
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("Host did not terminate the checkpoint process");
    }

    private Message logged(JSONObject record) throws Exception {
        return new Message(
                record.getString("id"),
                Message.MINE,
                record.getLong("at"),
                "Pocket E2 QA · Prepared transcript",
                "phone",
                0,
                Message.DELIVERED,
                "",
                true,
                false,
                Collections.emptyList(),
                18);
    }

    private int cleanupCount(RoomRepository repository, JSONObject record) throws Exception {
        try (Cursor cursor =
                repository
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT count(*) FROM metadata WHERE room=? AND name=?",
                                new String[] {
                                    "@outbox-cleanup", room(record) + "|" + record.getString("id")
                                })) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private String extraId(JSONObject record, String kind) throws Exception {
        return UUID.nameUUIDFromBytes(
                        (record.getString("id") + ":" + kind).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private void extra(RoomRepository repository, JSONObject record, File file, String kind)
            throws Exception {
        String id = extraId(record, kind);
        Outbox.Item item =
                new Outbox.Item(
                        id,
                        "",
                        true,
                        file.getPath(),
                        "phone",
                        400,
                        Collections.emptyList(),
                        Message.QUEUED,
                        "",
                        "",
                        record.getLong("at"),
                        0);
        repository.enqueue(
                Message.mine(id, "", "phone", 400, Message.QUEUED, Collections.emptyList()), item);
    }

    Bundle run(Context context, String phase) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PocketController controller = PocketController.get(context);
        long until = SystemClock.elapsedRealtime() + 30_000;
        while ((controller.snapshot() == null
                        || controller.snapshot().connection != PocketController.Connection.ONLINE)
                && SystemClock.elapsedRealtime() < until) Thread.sleep(25);
        check(
                controller.snapshot() != null
                        && controller.configured()
                        && !controller.snapshot().trying,
                "outbox crash acceptance retains the authorized actual room");
        if (phase.equals("prepare")) {
            check(
                    !journal(context).exists(),
                    "a previous outbox crash fixture must be restored first");
            PocketController.Snapshot snapshot = controller.snapshot();
            check(
                    snapshot.connection == PocketController.Connection.ONLINE
                            && !snapshot.ambientActive
                            && !snapshot.holdRecording
                            && !snapshot.uploadingVoice
                            && !snapshot.cameraOn
                            && snapshot.readingId.isEmpty()
                            && snapshot.imports.isEmpty()
                            && snapshot.outbox.isEmpty(),
                    "outbox crash acceptance requires the actual app idle");
            JSONObject record =
                    Json.object(
                            "id",
                            UUID.randomUUID().toString(),
                            "target",
                            snapshot.importTarget,
                            "draft",
                            snapshot.draft,
                            "quote",
                            snapshot.quote,
                            "chips",
                            new JSONArray(paths(snapshot)),
                            "at",
                            System.currentTimeMillis());
            File directory = directory(context, record);
            if (!directory.mkdirs()) throw new IOException("Cannot create outbox crash fixture");
            File voice = new File(directory, "voice.packets"),
                    attachment = new File(directory, "attachment.txt");
            try (OpusEncoder encoder = new OpusEncoder();
                    ByteArrayOutputStream packets = new ByteArrayOutputStream()) {
                for (int i = 0; i < 20; i++) {
                    byte[] packet = encoder.encode(new short[320]);
                    packets.write(packet.length & 255);
                    packets.write(packet.length >>> 8);
                    packets.write(packet);
                }
                synced(voice, packets.toByteArray());
            }
            synced(attachment, "Pocket E2 QA attachment bytes".getBytes(StandardCharsets.UTF_8));
            record.put("voice_bytes", voice.length());
            record.put("attachment_bytes", attachment.length());
            record.put(
                    "voice",
                    Base64.getEncoder().encodeToString(Files.readAllBytes(voice.toPath())));
            write(context, record);
            String id = record.getString("id");
            try (RoomRepository repository = new RoomRepository(context)) {
                repository.select(room(record));
                repository.metadata("cursor", "17");
                Attachment local =
                        new Attachment(
                                "fixture.txt",
                                "text/plain",
                                attachment.getPath(),
                                "",
                                "",
                                attachment.length(),
                                Attachment.UPLOADED);
                Outbox.Item item =
                        new Outbox.Item(
                                        id,
                                        "",
                                        true,
                                        voice.getPath(),
                                        "phone",
                                        400,
                                        Collections.singletonList(local),
                                        Message.SENDING,
                                        "",
                                        "",
                                        record.getLong("at"),
                                        0)
                                .surface("phone", false, "");
                repository.enqueue(
                        Message.mine(id, "", "phone", 400, Message.SENDING, item.attachments),
                        item);
                android.database.sqlite.SQLiteDatabase database = repository.getWritableDatabase();
                database.beginTransactionWithListener(
                        new android.database.sqlite.SQLiteTransactionListener() {
                            public void onBegin() {}

                            public void onRollback() {}

                            public void onCommit() {
                                check(
                                        database.inTransaction(),
                                        "the native commit callback still owns the open"
                                                + " transaction");
                                check(
                                        repository.findPending(id) == null
                                                && repository.row(id).seq == 18
                                                && repository.cursor() == 18,
                                        "the outer native transaction contains production"
                                                + " row/outbox/cursor admission before its actual"
                                                + " commit");
                                check(
                                        voice.isFile() && attachment.isFile(),
                                        "owned voice and attachment bytes remain until the outer"
                                                + " transaction commits");
                                awaitTermination("outbox-transaction-open");
                            }
                        });
                try {
                    repository.admit(Collections.singletonList(logged(record)), 18);
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            }
        } else if (phase.equals("restore") && !journal(context).exists()) {
            // Preparation can fail before creating any fixture; cleanup is still idempotent.
        } else {
            JSONObject record = read(context);
            preserved(controller, record);
            File directory = directory(context, record);
            File voice = new File(directory, "voice.packets"),
                    attachment = new File(directory, "attachment.txt"),
                    discarded = new File(directory, "discard.packets"),
                    cleared = new File(directory, "clear.packets");
            if (phase.equals("afterRollback")) {
                try (RoomRepository repository = new RoomRepository(context)) {
                    repository.select(room(record));
                    Outbox.Item item = repository.findPending(record.getString("id"));
                    check(
                            item != null
                                    && item.state == Message.QUEUED
                                    && item.id.equals(record.getString("id"))
                                    && item.display.equals("phone")
                                    && !item.spoken
                                    && item.place.isEmpty()
                                    && item.durationMs == 400
                                    && repository.row(item.id).seq < 0
                                    && repository.row(item.id).delivery == Message.QUEUED
                                    && repository.cursor() == 17
                                    && cleanupCount(repository, record) == 0,
                            "actual process termination rolls back row/removal/cursor/cleanup"
                                    + " together and recovers the original queued id and surface");
                    check(
                            voice.length() == record.getLong("voice_bytes")
                                    && attachment.length() == record.getLong("attachment_bytes")
                                    && Arrays.equals(
                                            Files.readAllBytes(voice.toPath()),
                                            Base64.getDecoder().decode(record.getString("voice")))
                                    && new String(
                                                    Files.readAllBytes(attachment.toPath()),
                                                    StandardCharsets.UTF_8)
                                            .equals("Pocket E2 QA attachment bytes"),
                            "rollback keeps both the complete voice file and attachment");
                    synced(discarded, Base64.getDecoder().decode(record.getString("voice")));
                    synced(cleared, Base64.getDecoder().decode(record.getString("voice")));
                    try {
                        Os.chmod(directory.getPath(), 0500);
                        repository.admit(Collections.singletonList(logged(record)), 18);
                        check(
                                repository.findPending(item.id) == null
                                        && repository.row(item.id).seq == 18
                                        && repository.row(item.id).durationMs == 400
                                        && repository.cursor() == 18
                                        && voice.isFile()
                                        && attachment.isFile(),
                                "committed admission survives a real file-deletion denial while"
                                        + " retaining authoritative row, duration and cursor");
                        extra(repository, record, discarded, "discard");
                        repository.discard(extraId(record, "discard"));
                        check(
                                repository.findPending(extraId(record, "discard")) == null
                                        && repository.row(extraId(record, "discard")) == null
                                        && discarded.isFile(),
                                "discard commits its row/outbox removal while a denied file"
                                        + " deletion remains retryable");
                        repository.select(room(record) + ":clear");
                        extra(repository, record, cleared, "clear");
                        repository.clear();
                        check(
                                repository.pending().isEmpty()
                                        && repository.countRows() == 0
                                        && cleared.isFile(),
                                "clearing a room retains denied file cleanup independently of its"
                                        + " removed metadata");
                    } finally {
                        Os.chmod(directory.getPath(), 0700);
                    }
                }
                record.put("committed", true);
                write(context, record);
                awaitTermination("outbox-cleanup-retained");
            } else if (phase.equals("afterCommit")) {
                check(
                        record.optBoolean("committed"),
                        "the previous native phase committed the fixture");
                // Ordinary MainActivity startup already opened/selects the production repository.
                check(
                        !voice.exists()
                                && !attachment.exists()
                                && !discarded.exists()
                                && !cleared.exists(),
                        "ordinary cold launch completes retained cleanup before this probe opens"
                                + " its own repository");
                try (RoomRepository repository = new RoomRepository(context)) {
                    repository.select(room(record));
                    check(
                            repository.cursor() == 18
                                    && repository.findPending(record.getString("id")) == null
                                    && repository.row(record.getString("id")).seq == 18
                                    && repository.row(record.getString("id")).durationMs == 400
                                    && cleanupCount(repository, record) == 0,
                            "post-commit process death preserves one delivered row and cursor with"
                                    + " no pending resend or cleanup receipt");
                    repository.admit(Collections.singletonList(logged(record)), 18);
                    check(
                            repository.countRows() == 1
                                    && repository.pending().isEmpty()
                                    && repository.cursor() == 18,
                            "replaying the same committed row remains idempotent after actual"
                                    + " restart");
                    repository.select(room(record) + ":clear");
                    check(
                            repository.countRows() == 0 && repository.pending().isEmpty(),
                            "the separately cleared namespace stays empty after restart");
                }
            } else if (phase.equals("restore")) {
                if (directory.exists()) Os.chmod(directory.getPath(), 0700);
                try (RoomRepository repository = new RoomRepository(context)) {
                    repository.select(room(record));
                    repository.clear();
                    repository.select(room(record) + ":clear");
                    repository.clear();
                }
                voice.delete();
                attachment.delete();
                discarded.delete();
                cleared.delete();
                directory.delete();
                check(
                        !directory.exists(),
                        "cleanup removes only the fixture's private byte directory");
                if (!journal(context).delete())
                    throw new IOException("Cannot remove outbox crash journal");
                preserved(controller, record);
            } else throw new IllegalArgumentException("Unknown outbox crash phase");
        }
        Bundle result = new Bundle();
        result.putString(
                "result",
                "PASS " + assertions + " native outbox process-death assertions; phase=" + phase);
        return result;
    }
}
