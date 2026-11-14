// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.content.*;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.*;

import top.kuaner.pockete2.app.InboundContent;
import top.kuaner.pockete2.app.PocketFiles;
import top.kuaner.pockete2.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Real provider copies and SQLite rollback; all records live in a disposable database. */
final class ImportProbe {
    private static int assertions;

    static int run(Context context) throws Exception {
        provider(context);
        database(context);
        return assertions;
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static void provider(Context context) throws Exception {
        File shared = new File(context.getCacheDir(), "shared");
        shared.mkdirs();
        File first = File.createTempFile("qa-import-", ".bin", shared);
        File second = File.createTempFile("qa-import-", ".bin", shared);
        File copy = File.createTempFile("qa-copy-", ".tmp", context.getCacheDir());
        try {
            Files.write(first.toPath(), "first document".getBytes(StandardCharsets.UTF_8));
            Files.write(second.toPath(), "second document".getBytes(StandardCharsets.UTF_8));
            Uri one = PocketFiles.uri(context, first, "原始 报告.txt", "text/plain");
            Uri two =
                    PocketFiles.uri(
                            context,
                            second,
                            "settings stays.literal",
                            "application/vnd.pocket-e2+data");
            try (android.database.Cursor cursor =
                    context.getContentResolver().query(one, null, null, null, null)) {
                check(
                        cursor != null
                                && cursor.moveToFirst()
                                && cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                        OpenableColumns.DISPLAY_NAME))
                                        .equals("原始 报告.txt")
                                && cursor.getLong(
                                                cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                                        == first.length()
                                && context.getContentResolver()
                                        .getType(two)
                                        .equals("application/vnd.pocket-e2+data"),
                        "content provider exposes original names and declared MIME independently of"
                                + " cache filenames");
            }
            Attachment imported = new AttachmentStore(context).importFile(one, copy, 0);
            check(
                    imported.name.equals("原始 报告.txt")
                            && imported.mime.equals("text/plain")
                            && Arrays.equals(
                                    Files.readAllBytes(first.toPath()),
                                    Files.readAllBytes(copy.toPath())),
                    "provider import preserves literal metadata and actual copied bytes");
            boolean tooLarge = false;
            try {
                new AttachmentStore(context).importFile(two, copy, 3);
            } catch (IOException expected) {
                tooLarge = true;
            }
            check(
                    tooLarge && !copy.exists() && second.isFile(),
                    "remote import limit removes partial copies while retaining the source");
            boolean escaped = false;
            try {
                PocketFiles.uri(context, new File(context.getCacheDir(), "private-db"));
            } catch (IllegalArgumentException expected) {
                escaped = true;
            }
            check(escaped, "the file provider refuses paths outside the shared cache");
            Uri traversal =
                    new Uri.Builder()
                            .scheme("content")
                            .authority(context.getPackageName() + ".files")
                            .appendPath("../private-db")
                            .build();
            boolean inaccessible = false;
            try (InputStream ignored = context.getContentResolver().openInputStream(traversal)) {
            } catch (FileNotFoundException expected) {
                inaccessible = true;
            }
            check(inaccessible, "encoded path traversal cannot open a private file");
            Intent send =
                    new Intent(Intent.ACTION_SEND_MULTIPLE)
                            .putExtra(Intent.EXTRA_TEXT, "literal 中文 text");
            send.putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, new ArrayList<>(Arrays.asList(two, one, two)));
            ClipData clip = ClipData.newRawUri("duplicated representations", one);
            clip.addItem(new ClipData.Item(two));
            clip.addItem(new ClipData.Item(Uri.fromFile(first)));
            send.setClipData(clip);
            InboundContent content = InboundContent.share(send);
            check(
                    content.text.equals("literal 中文 text")
                            && content.files.equals(Arrays.asList(two, one)),
                    "multiple streams and ClipData deduplicate while preserving the explicit order"
                            + " and content");
            Intent clipOnly = new Intent(Intent.ACTION_SEND).setType("text/plain");
            ClipData only = ClipData.newPlainText("text", "clip-only text");
            only.addItem(new ClipData.Item(one));
            clipOnly.setClipData(only);
            InboundContent fallback = InboundContent.share(clipOnly);
            check(
                    fallback.text.equals("clip-only text")
                            && fallback.files.equals(Collections.singletonList(one)),
                    "ClipData-only shares retain both text and content URI");
            Intent link = new Intent(Intent.ACTION_SEND);
            link.setClipData(
                    ClipData.newRawUri(
                            "link", Uri.parse("https://example.test/path?q=literal%20text")));
            link.setData(two);
            InboundContent linkContent = InboundContent.share(link);
            check(
                    linkContent.text.equals("https://example.test/path?q=literal%20text")
                            && linkContent.files.equals(Collections.singletonList(two)),
                    "ClipData web links become editable text and a content data URI remains an"
                            + " attachment fallback");
            Intent picked = new Intent().setData(one);
            ClipData selection = ClipData.newRawUri("selection", two);
            selection.addItem(new ClipData.Item(one));
            picked.setClipData(selection);
            InboundContent selectionContent = InboundContent.picked(picked);
            check(
                    selectionContent.files.equals(Arrays.asList(two, one)),
                    "picker ClipData order takes precedence over its repeated data URI");
            Runnable release = selectionContent.retainReads(context, 0);
            release.run();
            release.run();
            check(
                    InboundContent.share(
                                    new Intent(Intent.ACTION_SEND)
                                            .putExtra(Intent.EXTRA_STREAM, "wrong type"))
                            .files
                            .isEmpty(),
                    "invalid stream extras do not crash import and temporary-grant release is"
                            + " repeatable");
        } finally {
            first.delete();
            second.delete();
            copy.delete();
        }
    }

    private static void database(Context context) throws Exception {
        String name = "pocket-e2-import-qa.db";
        context.deleteDatabase(name);
        RoomRepository repo = new RoomRepository(context, name);
        try {
            repo.select("qa-import-room");
            repo.draft("before", "quote-before", "[]");
            String id = UUID.randomUUID().toString();
            check(
                    repo.importDraft(id, "after", "quote-after", "[]"),
                    "first import transaction admits a draft and receipt");
            repo.close();
            repo = new RoomRepository(context, name);
            repo.select("qa-import-room");
            check(
                    repo.imported(id)
                            && !repo.importDraft(id, "duplicate", "", "[]")
                            && repo.metadata("draft").equals("after")
                            && repo.metadata("quote").equals("quote-after"),
                    "reopened SQLite retains the receipt and rejects a duplicate composer change");
            repo.select("qa-other-room");
            repo.draft("other", "", "[]");
            check(
                    !repo.importDraft(id, "leaked", "", "[]")
                            && repo.metadata("draft").equals("other"),
                    "a delivery cannot replay into a different paired room");
            String failed = UUID.randomUUID().toString();
            repo.getWritableDatabase()
                    .execSQL(
                            "CREATE TEMP TRIGGER reject_import BEFORE INSERT ON metadata WHEN"
                                    + " NEW.room='@import-receipts' AND NEW.name='"
                                    + failed
                                    + "' BEGIN SELECT RAISE(ABORT, 'injected receipt failure');"
                                    + " END");
            boolean rolledBack = false;
            try {
                repo.importDraft(failed, "must roll back", "wrong quote", "[{\"name\":\"wrong\"}]");
            } catch (android.database.SQLException | IllegalStateException expected) {
                rolledBack = true;
            }
            check(
                    rolledBack
                            && !repo.imported(failed)
                            && repo.metadata("draft").equals("other")
                            && repo.metadata("quote").isEmpty()
                            && repo.metadata("chips").equals("[]"),
                    "receipt-write failure rolls back text, quote and chips together");
            repo.getWritableDatabase().execSQL("DROP TRIGGER reject_import");
            String cancelled = UUID.randomUUID().toString();
            check(
                    repo.cancelImport(cancelled)
                            && repo.importDestination(cancelled)
                                    .equals(RoomRepository.IMPORT_CANCELLED)
                            && !repo.importDraft(cancelled, "cancelled content", "", "[]")
                            && repo.metadata("draft").equals("other"),
                    "a durable cancellation receipt prevents later manifest admission without"
                            + " clearing the existing draft");
            boolean reserved = false;
            try {
                repo.reserveTransientImport(cancelled, "try:" + UUID.randomUUID());
            } catch (IllegalStateException expected) {
                reserved = true;
            }
            check(
                    reserved
                            && !repo.cancelImport(id)
                            && repo.importDestination(id).equals("received"),
                    "try reservation cannot overwrite cancellation and late cancellation cannot"
                            + " delete an admitted draft");
            Attachment a =
                    new Attachment("one", "text/plain", "/qa/one", "", "", 1, Attachment.FAILED);
            Attachment b =
                    new Attachment("two", "text/plain", "/qa/two", "", "", 1, Attachment.FAILED);
            repo.select("unpaired");
            repo.draft(
                    "received before pairing",
                    "unpaired quote",
                    new JSONArray().put(a.json(true)).put(b.json(true)).toString());
            repo.select("qa-other-room");
            repo.draft("existing", "room quote", new JSONArray().put(a.json(true)).toString());
            repo.inheritUnpairedDraft();
            JSONArray merged = new JSONArray(repo.metadata("chips"));
            check(
                    repo.metadata("draft").equals("existing\nreceived before pairing")
                            && repo.metadata("quote").equals("room quote")
                            && merged.length() == 2
                            && merged.getJSONObject(1).getString("localPath").equals("/qa/two"),
                    "first pairing merges unassigned text and ordered chips without replacing a"
                            + " saved room quote");
            repo.inheritUnpairedDraft();
            check(
                    repo.metadata("draft").equals("existing\nreceived before pairing")
                            && new JSONArray(repo.metadata("chips")).length() == 2,
                    "repeated first-pair recovery cannot transfer the same content twice");
            repo.select("unpaired");
            check(
                    repo.metadata("draft").isEmpty()
                            && repo.metadata("chips").isEmpty()
                            && repo.metadata("quote").isEmpty(),
                    "unassigned draft removal commits with the destination composer");
            String transientId = UUID.randomUUID().toString(),
                    transientScope = "try:" + UUID.randomUUID();
            repo.reserveTransientImport(transientId, transientScope);
            try (RoomRepository memory = new RoomRepository(context, null)) {
                memory.select(transientScope);
                check(
                        memory.importDraft(transientId, "disposable", "", "[]")
                                && repo.importDestination(transientId).equals(transientScope),
                        "try content stays in memory while its durable receipt prevents replay into"
                                + " real rooms");
            }
            repo.select("qa-other-room");
            repo.clear();
            check(
                    repo.imported(id)
                            && repo.imported(transientId)
                            && repo.importDestination(cancelled)
                                    .equals(RoomRepository.IMPORT_CANCELLED)
                            && repo.importReceipts().contains(id),
                    "room-cache clearing retains content-free delivery receipts");
        } finally {
            repo.close();
            context.deleteDatabase(name);
        }
    }
}
