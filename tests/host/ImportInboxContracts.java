// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import org.json.*;

import top.kuaner.pockete2.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real filesystem failure/recovery contracts without an Android runtime. */
public final class ImportInboxContracts {
    private static int checked;

    private static void check(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    public static int run() throws Exception {
        routes();
        Path temporary = Files.createTempDirectory("pocket-e2-import-");
        try {
            File root = temporary.resolve("inbox").toFile();
            ImportInbox inbox = new ImportInbox(root);
            String cancelledId = UUID.randomUUID().toString();
            boolean[] cancelled = {false};
            boolean rejectedCopy = false;
            try {
                inbox.prepare(
                        cancelledId,
                        "room-one",
                        "never append",
                        "",
                        1,
                        (index, file) -> {
                            write(file, "copied bytes before cancellation");
                            cancelled[0] = true;
                            return new Attachment(
                                    "cancelled.txt",
                                    "text/plain",
                                    file.getPath(),
                                    "",
                                    "",
                                    file.length(),
                                    Attachment.UPLOADING);
                        },
                        () -> {
                            if (cancelled[0])
                                throw new InterruptedIOException(
                                        "cancelled before manifest commit");
                        });
            } catch (InterruptedIOException expected) {
                rejectedCopy = true;
            }
            check(
                    rejectedCopy
                            && inbox.find(cancelledId) == null
                            && !new File(root, cancelledId + ".partial").exists(),
                    "cancellation after the last copied byte cannot publish a manifest or retain"
                            + " partial files");
            String first = "ffffffff-ffff-4fff-8fff-ffffffffffff";
            String second = "00000000-0000-4000-8000-000000000001";
            List<Integer> copied = new ArrayList<>();
            ImportBatch a =
                    inbox.prepare(
                            first,
                            "room-one",
                            "  first text \n",
                            2,
                            (index, file) -> {
                                copied.add(index);
                                write(file, "bytes-" + index);
                                return new Attachment(
                                        index == 0 ? "原始 文档.txt" : "picture.webp",
                                        index == 0 ? "text/plain" : "image/webp",
                                        file.getPath(),
                                        "",
                                        "",
                                        file.length(),
                                        Attachment.UPLOADING);
                            });
            check(
                    copied.equals(Arrays.asList(0, 1))
                            && a.attachments.size() == 2
                            && a.attachments.get(0).name.equals("原始 文档.txt")
                            && a.attachments.get(1).mime.equals("image/webp"),
                    "one delivery preserves file ordering and literal provider metadata");
            check(
                    a.appendTo("existing").equals("existing\nfirst text")
                            && a.appendTo("").equals("first text"),
                    "share text is trimmed once and appended on its owner");
            check(
                    a.scope.equals("room-one")
                            && a.attachments.stream()
                                    .allMatch(
                                            f ->
                                                    new File(f.localPath).isFile()
                                                            && !f.localPath.contains(".partial")),
                    "only committed final paths can enter a composer");
            ImportBatch replay =
                    inbox.prepare(
                            first,
                            "different-room",
                            "do not replace",
                            1,
                            (index, file) -> {
                                throw new AssertionError(
                                        "replayed delivery must not re-read temporary URIs");
                            });
            check(
                    replay.scope.equals("room-one")
                            && replay.text.equals("first text")
                            && replay.attachments.size() == 2,
                    "committed delivery reuses its original room and copied content");
            ImportBatch b =
                    inbox.prepare(
                            second,
                            "",
                            "next",
                            0,
                            (index, file) -> {
                                throw new AssertionError();
                            });
            ImportInbox restarted = new ImportInbox(root);
            ImportInbox.Scan recovered = restarted.pending();
            check(
                    recovered.unreadable == 0
                            && recovered.batches.size() == 2
                            && recovered.batches.get(0).id.equals(first)
                            && b.at > a.at,
                    "recovery retains commit order even when delivery ids sort oppositely");
            String failing = UUID.randomUUID().toString();
            boolean failed = false;
            try {
                inbox.prepare(
                        failing,
                        "room-one",
                        "not admitted",
                        2,
                        (index, file) -> {
                            write(file, "partial bytes");
                            if (index == 1) throw new IOException("provider revoked access");
                            return new Attachment(
                                    "first",
                                    "text/plain",
                                    file.getPath(),
                                    "",
                                    "",
                                    file.length(),
                                    Attachment.UPLOADING);
                        });
            } catch (IOException expected) {
                failed = true;
            }
            check(
                    failed
                            && inbox.find(failing) == null
                            && !new File(root, failing + ".partial").exists()
                            && inbox.pending().batches.size() == 2,
                    "mid-batch read failure admits no partial text or files and cleans copied"
                            + " bytes");
            File abandoned = new File(root, UUID.randomUUID() + ".partial");
            abandoned.mkdirs();
            write(new File(abandoned, "file-0"), "interrupted write");
            restarted.pruneIncomplete();
            check(
                    !abandoned.exists() && new File(a.attachments.get(0).localPath).isFile(),
                    "startup prunes interrupted copies while retaining committed data");
            restarted.acknowledge(a);
            check(
                    restarted.pending().batches.size() == 1
                            && new File(a.attachments.get(0).localPath).isFile(),
                    "acknowledgement removes only the manifest, preserving draft/outbox bytes");
            restarted.acknowledge(a);
            restarted.discard(b);
            check(
                    restarted.pending().batches.isEmpty() && !new File(root, b.id).exists(),
                    "manifest acknowledgements are repeatable and discarded batches are removed");
            String settledId = UUID.randomUUID().toString();
            ImportBatch settled =
                    restarted.prepare(
                            settledId,
                            "room-one",
                            "already sent",
                            "photo-owned123.jpg",
                            1,
                            (index, file) -> {
                                write(file, "settled bytes");
                                return new Attachment(
                                        "settled",
                                        "text/plain",
                                        file.getPath(),
                                        "",
                                        "",
                                        file.length(),
                                        Attachment.UPLOADING);
                            });
            new File(settled.attachments.get(0).localPath).delete();
            ImportInbox.Scan settledScan =
                    new ImportInbox(root).pending(Collections.singleton(settledId));
            check(
                    settledScan.batches.size() == 1
                            && settledScan.unreadable == 0
                            && settledScan.batches.get(0).attachments.isEmpty()
                            && settledScan.batches.get(0).ownedPhoto.equals("photo-owned123.jpg"),
                    "receipt recovery retains owned-photo cleanup metadata even after outbox"
                            + " settlement removed copied files");
            restarted.acknowledge(settledScan.batches.get(0));
            String corrupt = UUID.randomUUID().toString();
            File corruptDir = new File(root, corrupt);
            corruptDir.mkdirs();
            JSONObject manifest =
                    new JSONObject()
                            .put("version", 1)
                            .put("id", corrupt)
                            .put("scope", "room-one")
                            .put("at", 0)
                            .put("text", "must not append")
                            .put(
                                    "files",
                                    new JSONArray()
                                            .put(
                                                    new JSONObject()
                                                            .put("file", "../outside")
                                                            .put("name", "outside")
                                                            .put("mime", "text/plain")
                                                            .put("size", 1)));
            write(new File(corruptDir, "share.json"), manifest.toString());
            ImportInbox.Scan invalid = restarted.pending();
            check(
                    invalid.batches.isEmpty() && invalid.unreadable == 1,
                    "invalid file paths cannot yield a partially accepted delivery");
            boolean rejected = false;
            try {
                restarted.find("../outside");
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "delivery identifiers cannot escape the inbox");
            boolean invalidPhoto = false;
            try {
                ImportBatch.requirePhotoName("../../private.jpg");
            } catch (IllegalArgumentException expected) {
                invalidPhoto = true;
            }
            check(invalidPhoto, "owned-photo cleanup metadata cannot name arbitrary local paths");
            ImportBatch blank =
                    new ImportBatch(
                            UUID.randomUUID().toString(), "", " \n ", 0, Collections.emptyList());
            check(
                    blank.appendTo("existing").equals("existing"),
                    "blank shared text does not add a spurious draft newline");
            check(
                    DraftText.edit("draft\nshared", "draft", "draft typed")
                            .equals("draft typed\nshared"),
                    "typing based on the previous editor value retains a concurrently appended"
                            + " share");
            check(
                    DraftText.edit("shared", "", "typed").equals("typed\nshared"),
                    "typing into an empty focused editor retains content arriving before its"
                            + " acknowledgement");
            check(
                    DraftText.edit("typed\nshared", "typed", "typed twice")
                            .equals("typed twice\nshared"),
                    "successive unacknowledged editor changes preserve the appended content exactly"
                            + " once");
            check(
                    DraftText.edit("draft\nfirst\nsecond", "draft", "changed")
                            .equals("changed\nfirst\nsecond"),
                    "editor reconciliation retains multiple appended deliveries in order");
            check(
                    DraftText.edit("draft\nshared", "draft", "").equals("shared"),
                    "deleting the original editor value keeps the incoming share without a leading"
                            + " separator");
            check(
                    DraftText.edit("draft", "draft", "changed  ").equals("changed  "),
                    "ordinary editor changes retain user whitespace");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
        return checked;
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void routes() {
        String id = UUID.randomUUID().toString();
        ImportRoute picked = new ImportRoute(id, "room-one", ImportRoute.Kind.FILES);
        ImportRoute restored = ImportRoute.from(picked.json());
        check(
                restored.id.equals(id)
                        && restored.target.equals("room-one")
                        && restored.kind == ImportRoute.Kind.FILES,
                "saved picker routes preserve the original delivery, room and import policy");
        check(
                picked.visibleIn("room-one", false)
                        && !picked.visibleIn("room-two", false)
                        && !picked.visibleIn("room-one", true),
                "real-room results cannot enter a different room or a disposable room");
        ImportRoute unpaired = new ImportRoute(id, "", ImportRoute.Kind.MEDIA);
        check(
                unpaired.visibleIn("unpaired", false)
                        && unpaired.visibleIn("room-one", false)
                        && !unpaired.visibleIn("try:" + id, true),
                "unassigned imports follow the first real pairing without entering try mode");
        ImportRoute demo = new ImportRoute(id, "try:" + id, ImportRoute.Kind.CAMERA);
        check(
                demo.trying()
                        && demo.visibleIn("try:" + id, true)
                        && !demo.visibleIn("try:" + UUID.randomUUID(), true)
                        && !demo.visibleIn("room-one", false),
                "try results are bound to exactly their original disposable generation");
        boolean invalid = false;
        try {
            new ImportRoute(id, "try:invalid", ImportRoute.Kind.MEDIA);
        } catch (IllegalArgumentException expected) {
            invalid = true;
        }
        check(invalid, "restored try routes reject malformed generation identifiers");
    }
}
