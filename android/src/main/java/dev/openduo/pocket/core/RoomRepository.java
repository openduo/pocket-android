// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Room-isolated SQLite cache. Cursor advancement and row admission are one durable transaction. */
public final class RoomRepository extends SQLiteOpenHelper {
    public static final String IMPORT_CANCELLED = "cancelled";
    private static final String IMPORT_RECEIPTS = "@import-receipts";
    private static final String IMPORT_REQUESTS = "@import-requests";
    private static final String OUTBOX_CLEANUP = "@outbox-cleanup";
    private static final String VOICE_RECEIPTS = "@voice-handoff";
    private String room = "unpaired";

    public RoomRepository(Context context) {
        this(context, "duoduo-pocket.db");
    }

    public RoomRepository(Context context, String database) {
        super(context, database, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE rows (room TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, seq"
                        + " INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(room,id))");
        db.execSQL("CREATE INDEX rows_time ON rows(room,at)");
        db.execSQL(
                "CREATE TABLE pending (room TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL,"
                        + " body TEXT NOT NULL, PRIMARY KEY(room,id))");
        db.execSQL(
                "CREATE TABLE metadata (room TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT"
                        + " NULL, PRIMARY KEY(room,name))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade");
    }

    public void select(Settings settings) {
        select(
                settings.hasEndpoint()
                        ? settings.endpoint().identity() + "|" + settings.room()
                        : "unpaired");
    }

    public void select(String identity) {
        room = identity;
        finishCleanup();
        for (Outbox.Item item : pending())
            if (item.state == Message.SENDING) transition(item.state(Message.QUEUED, ""));
    }

    public String roomIdentity() {
        return room;
    }

    public List<Message> rows(int limit) {
        List<Message> result = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT body FROM rows WHERE room=? ORDER BY at DESC,rowid DESC"
                                        + " LIMIT ?",
                                new String[] {room, String.valueOf(limit)})) {
            while (c.moveToNext()) result.add(Message.from(new JSONObject(c.getString(0))));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("对话缓存格式无效", failure);
        }
        Collections.reverse(result);
        return result;
    }

    public Message row(String id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT body FROM rows WHERE room=? AND id=?",
                                new String[] {room, id})) {
            return c.moveToFirst() ? Message.from(new JSONObject(c.getString(0))) : null;
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void put(Message message) {
        try {
            ContentValues values = new ContentValues();
            values.put("room", room);
            values.put("id", message.id);
            values.put("at", message.at);
            values.put("seq", message.seq);
            values.put("body", message.json().toString());
            if (getWritableDatabase()
                            .insertWithOnConflict(
                                    "rows", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    < 0) throw new IllegalStateException("无法保存对话缓存");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void transition(Outbox.Item item) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            pending(item);
            Message row = row(item.id);
            if (row != null) put(row.withDelivery(item.state, item.error));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void cleaned(String id, String text) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Outbox.Item item = findPending(id);
            Message row = row(id);
            if (item != null
                    && row != null
                    && (item.state == Message.SENDING || item.state == Outbox.ACCEPTED)) {
                pending(item.cleaned(text));
                put(row.withText(text, true, false).withDelivery(Outbox.ACCEPTED, ""));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int countRows() {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT count(*) FROM rows WHERE room=?", new String[] {room})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void draft(String text, String quote, String chips) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            metadata("draft", text);
            metadata("quote", quote);
            metadata("chips", chips);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean imported(String delivery) {
        return !importDestination(delivery).isEmpty();
    }

    public ImportRequest importRequest(String id) {
        ImportBatch.requireId(id);
        String value = roomMetadata(IMPORT_REQUESTS, id);
        try {
            return value.isEmpty() ? null : ImportRequest.from(new JSONObject(value));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Invalid pending import request", failure);
        }
    }

    /** The state owner persists every queued request before any provider operation begins. */
    public void rememberImport(ImportRequest request) {
        if (importRequest(request.route.id) == null)
            metadata(IMPORT_REQUESTS, request.route.id, request.json().toString());
    }

    public List<ImportRequest> importRequests() {
        List<ImportRequest> requests = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT value FROM metadata WHERE room=? ORDER BY rowid",
                                new String[] {IMPORT_REQUESTS})) {
            while (c.moveToNext()) requests.add(ImportRequest.from(new JSONObject(c.getString(0))));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Invalid pending import request", failure);
        }
        return requests;
    }

    /** Called only after releasing the source leases; receipts/manifests cover later recovery. */
    public void forgetImport(String id) {
        ImportBatch.requireId(id);
        getWritableDatabase()
                .delete("metadata", "room=? AND name=?", new String[] {IMPORT_REQUESTS, id});
    }

    public String importDestination(String delivery) {
        ImportBatch.requireId(delivery);
        return roomMetadata(IMPORT_RECEIPTS, delivery);
    }

    public boolean cancelImport(String delivery) {
        ImportBatch.requireId(delivery);
        if (importDestination(delivery).equals("received")) return false;
        metadata(IMPORT_RECEIPTS, delivery, IMPORT_CANCELLED);
        return true;
    }

    public java.util.Set<String> importReceipts() {
        java.util.Set<String> deliveries = new java.util.HashSet<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT name FROM metadata WHERE room=?",
                                new String[] {IMPORT_RECEIPTS})) {
            while (c.moveToNext()) deliveries.add(c.getString(0));
        }
        return deliveries;
    }

    /** Disposable-room deliveries must never replay into a real room after process death. */
    public void reserveTransientImport(String delivery, String scope) {
        ImportBatch.requireId(delivery);
        if (!scope.startsWith("try:"))
            throw new IllegalArgumentException("Invalid transient import scope");
        ImportBatch.requireId(scope.substring(4));
        String destination = importDestination(delivery);
        if (!destination.isEmpty() && !destination.equals(scope))
            throw new IllegalStateException("Import delivery was already settled");
        metadata(IMPORT_RECEIPTS, delivery, scope);
    }

    /** The receipt and the complete composer change either both commit or both roll back. */
    public boolean importDraft(String delivery, String text, String quote, String chips) {
        ImportBatch.requireId(delivery);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (imported(delivery)) return false;
            draft(text, quote, chips);
            metadata(IMPORT_RECEIPTS, delivery, "received");
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Content received before the first room belongs to the user's next paired composer. */
    public void inheritUnpairedDraft() {
        if (room.equals("unpaired")) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String oldText = roomMetadata("unpaired", "draft");
            String oldQuote = roomMetadata("unpaired", "quote");
            String oldChips = roomMetadata("unpaired", "chips");
            String text = metadata("draft");
            if (!oldText.isEmpty()) text = text.isEmpty() ? oldText : text + "\n" + oldText;
            String quote = metadata("quote");
            draft(
                    text,
                    quote.isEmpty() ? oldQuote : quote,
                    mergeChips(metadata("chips"), oldChips));
            for (String key : new String[] {"draft", "quote", "chips"})
                metadata("unpaired", key, "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String mergeChips(String current, String incoming) {
        JSONArray value = new JSONArray();
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (String encoded : new String[] {current, incoming}) {
            if (encoded.isEmpty()) continue;
            try {
                JSONArray entries = new JSONArray(encoded);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if (paths.add(entry.optString("localPath"))) value.put(entry);
                }
            } catch (org.json.JSONException failure) {
                throw new IllegalStateException("Invalid unpaired attachment draft", failure);
            }
        }
        return value.toString();
    }

    public void enqueue(Message row, Outbox.Item item) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            put(row);
            pending(item);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The handoff receipt prevents replay after a crash between commit and manifest removal. */
    public void enqueueVoice(VoiceInbox.Note note) {
        if (!room.equals(note.room))
            throw new IllegalArgumentException("Voice note belongs to another room");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String admitted = roomMetadata(VOICE_RECEIPTS, note.id);
            if (!admitted.isEmpty() && !admitted.equals(room))
                throw new IllegalStateException("Voice identity belongs to another room");
            if (admitted.isEmpty()) {
                if (row(note.id) == null && findPending(note.id) == null) {
                    put(note.row(false));
                    pending(note.item(false));
                }
                metadata(VOICE_RECEIPTS, note.id, room);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public java.util.Set<String> voiceReceipts() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT name FROM metadata WHERE room=?",
                                new String[] {VOICE_RECEIPTS})) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    public void acknowledgeVoice(String id) {
        getWritableDatabase()
                .delete("metadata", "room=? AND name=?", new String[] {VOICE_RECEIPTS, id});
    }

    /** The two newest cached days cover turn settlement across midnight without a UI row limit. */
    List<Message> recentAnswers() {
        List<Message> answers = new ArrayList<>();
        String previousDay = "";
        int days = 0;
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT body,at FROM rows WHERE room=? AND seq>=0 ORDER BY at"
                                        + " DESC,rowid DESC",
                                new String[] {room})) {
            while (c.moveToNext()) {
                String day = Json.day(c.getLong(1));
                if (!day.equals(previousDay)) {
                    if (++days > 2) break;
                    previousDay = day;
                }
                Message row = Message.from(new JSONObject(c.getString(0)));
                if (row.kind == Message.DUODUO) answers.add(row);
            }
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException(failure);
        }
        return answers;
    }

    /** A late final must neither duplicate an admitted answer nor overwrite a different log row. */
    public void provisional(Message answer) {
        for (Message logged : recentAnswers()) if (logged.sameAnswer(answer)) return;
        Message prior = row(answer.id);
        if (prior != null && (prior.seq >= 0 || !prior.sameAnswer(answer)))
            answer = answer.withIdentity("provisional:" + java.util.UUID.randomUUID());
        put(answer);
    }

    public void admit(List<Message> incoming, long seq) {
        SQLiteDatabase db = getWritableDatabase();
        List<Outbox.Item> removed = new ArrayList<>();
        db.beginTransaction();
        try {
            List<Message> provisionals = new ArrayList<>();
            if (!incoming.isEmpty()) {
                try (Cursor c =
                        db.rawQuery(
                                "SELECT body FROM rows WHERE room=? AND seq<0",
                                new String[] {room})) {
                    while (c.moveToNext()) {
                        Message row = Message.from(new JSONObject(c.getString(0)));
                        if (row.kind == Message.DUODUO) provisionals.add(row);
                    }
                } catch (org.json.JSONException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            for (Message message : incoming) {
                Message existing = row(message.id);
                if (existing != null
                        && existing.seq >= 0
                        && message.seq > existing.seq
                        && message.kind == Message.DUODUO) {
                    message = message.withIdentity("answer:" + message.seq);
                } else if (existing != null
                        && existing.seq >= 0
                        && message.seq >= 0
                        && message.seq < existing.seq
                        && message.kind == Message.DUODUO) {
                    put(existing.withIdentity("answer:" + existing.seq));
                } else if (existing != null && existing.durationMs > 0 && message.durationMs == 0) {
                    message = message.withDuration(existing.durationMs);
                }
                if (existing != null
                        && existing.seq >= 0
                        && existing.kind == Message.MINE
                        && existing.delivery == Message.FAILED)
                    message = message.withDelivery(existing.delivery, existing.error);
                if (message.kind == Message.DUODUO && message.seq >= 0) {
                    for (Message candidate : provisionals)
                        if (candidate.sameAnswer(message))
                            db.delete(
                                    "rows",
                                    "room=? AND id=? AND seq<0",
                                    new String[] {room, candidate.id});
                }
                put(message);
                if (message.kind == Message.MINE) {
                    Outbox.Item item = findPending(message.id);
                    if (item != null) {
                        rememberCleanup(item);
                        db.delete("pending", "room=? AND id=?", new String[] {room, item.id});
                        removed.add(item);
                    }
                }
            }
            metadata("cursor", String.valueOf(Math.max(cursor(), seq)));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (!removed.isEmpty()) finishCleanup();
    }

    public List<Outbox.Item> pending() {
        List<Outbox.Item> result = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT body FROM pending WHERE room=? ORDER BY at,rowid",
                                new String[] {room})) {
            while (c.moveToNext()) result.add(Outbox.Item.from(new JSONObject(c.getString(0))));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException(failure);
        }
        return result;
    }

    public Outbox.Item findPending(String id) {
        for (Outbox.Item item : pending()) if (item.id.equals(id)) return item;
        return null;
    }

    public void pending(Outbox.Item item) {
        ContentValues values = new ContentValues();
        values.put("room", room);
        values.put("id", item.id);
        values.put("at", item.createdAt);
        values.put("body", item.json().toString());
        if (getWritableDatabase()
                        .insertWithOnConflict(
                                "pending", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                < 0) throw new IllegalStateException("无法保存待发送消息");
    }

    public void discard(String id) {
        Outbox.Item item = findPending(id);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (item != null) rememberCleanup(item);
            db.delete("pending", "room=? AND id=?", new String[] {room, id});
            db.delete("rows", "room=? AND id=? AND seq<0", new String[] {room, id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (item != null) finishCleanup();
    }

    public void clear() {
        List<Outbox.Item> items = pending();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Outbox.Item item : items) rememberCleanup(item);
            for (String table : new String[] {"rows", "pending", "metadata"})
                db.delete(table, "room=?", new String[] {room});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (!items.isEmpty()) finishCleanup();
    }

    public long cursor() {
        try {
            return Long.parseLong(metadata("cursor"));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    public boolean hasCursor() {
        return !metadata("cursor").isEmpty();
    }

    public String metadata(String name) {
        return roomMetadata(room, name);
    }

    private String roomMetadata(String identity, String name) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT value FROM metadata WHERE room=? AND name=?",
                                new String[] {identity, name})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    public void metadata(String name, String value) {
        metadata(room, name, value);
    }

    private void metadata(String identity, String name, String value) {
        ContentValues values = new ContentValues();
        values.put("room", identity);
        values.put("name", name);
        values.put("value", value);
        if (getWritableDatabase()
                        .insertWithOnConflict(
                                "metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                < 0) throw new IllegalStateException("无法保存房间状态");
    }

    /** The paths and the removal of their outbox owner commit together. No message content. */
    private void rememberCleanup(Outbox.Item item) {
        JSONArray paths = new JSONArray();
        if (!item.voiceFile.isEmpty()) paths.put(item.voiceFile);
        for (Attachment attachment : item.attachments)
            if (!attachment.localPath.isEmpty()) paths.put(attachment.localPath);
        if (paths.length() > 0) metadata(OUTBOX_CLEANUP, room + "|" + item.id, paths.toString());
    }

    /** A crash or failed delete after admission must not leave the removed note's bytes forever. */
    private void finishCleanup() {
        if (getWritableDatabase().inTransaction()) return;
        List<String[]> tasks = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT name,value FROM metadata WHERE room=?",
                                new String[] {OUTBOX_CLEANUP})) {
            while (cursor.moveToNext())
                tasks.add(new String[] {cursor.getString(0), cursor.getString(1)});
        }
        for (String[] task : tasks) {
            boolean done = true;
            try {
                JSONArray paths = new JSONArray(task[1]);
                for (int i = 0; i < paths.length(); i++) {
                    File file = new File(paths.getString(i));
                    try {
                        if (file.exists() && !file.delete()) done = false;
                    } catch (SecurityException denied) {
                        done = false;
                    }
                }
            } catch (org.json.JSONException invalid) {
                throw new IllegalStateException("Invalid pending outbox cleanup", invalid);
            }
            if (done)
                getWritableDatabase()
                        .delete(
                                "metadata",
                                "room=? AND name=?",
                                new String[] {OUTBOX_CLEANUP, task[0]});
        }
    }
}
