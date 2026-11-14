// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

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
    private String room = "unpaired";

    public RoomRepository(Context context) {
        this(context, "pocket-e2.db");
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

    public void admit(List<Message> incoming, long seq) {
        SQLiteDatabase db = getWritableDatabase();
        List<Outbox.Item> removed = new ArrayList<>();
        db.beginTransaction();
        try {
            List<Message> provisionals = new ArrayList<>();
            if (!incoming.isEmpty()) {
                try (Cursor c =
                        db.rawQuery(
                                "SELECT body FROM rows WHERE room=? AND seq<0 AND id LIKE"
                                        + " 'provisional:%'",
                                new String[] {room})) {
                    while (c.moveToNext())
                        provisionals.add(Message.from(new JSONObject(c.getString(0))));
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
                        if (candidate.id.startsWith("provisional:")
                                && candidate.seq < 0
                                && candidate.text.trim().equals(message.text.trim()))
                            db.delete("rows", "room=? AND id=?", new String[] {room, candidate.id});
                }
                put(message);
                if (message.kind == Message.MINE) {
                    Outbox.Item item = findPending(message.id);
                    if (item != null) {
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
        for (Outbox.Item item : removed) cleanup(item);
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
            db.delete("pending", "room=? AND id=?", new String[] {room, id});
            db.delete("rows", "room=? AND id=? AND seq<0", new String[] {room, id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (item != null) cleanup(item);
    }

    public void clear() {
        List<Outbox.Item> items = pending();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : new String[] {"rows", "pending", "metadata"})
                db.delete(table, "room=?", new String[] {room});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (Outbox.Item item : items) cleanup(item);
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

    private void cleanup(Outbox.Item item) {
        if (!item.voiceFile.isEmpty()) new File(item.voiceFile).delete();
        for (Attachment attachment : item.attachments)
            if (!attachment.localPath.isEmpty()) new File(attachment.localPath).delete();
    }
}
