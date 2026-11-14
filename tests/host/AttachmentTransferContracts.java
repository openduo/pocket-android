// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

final class AttachmentTransferContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        try (Fixture f = new Fixture()) {
            f.transfers.upload(f.local, f.room(false), null, (result, failure) -> f.uploads++);
            f.transfers.cancelUpload(f.local.localPath);
            f.time.drain();
            check(
                    f.source.calls == 0 && f.uploads == 0,
                    "cancelled queued uploads never read or dispatch HTTP");
            f.transfers.upload(f.local, f.room(false), null, (result, failure) -> f.uploads++);
            f.time.worker.runNext();
            f.transfers.upload(
                    f.local,
                    f.room(false),
                    null,
                    (result, failure) -> {
                        check(
                                failure == null && result.sha256.equals(f.remote.sha256),
                                "upload admits the digest of its exact bytes");
                        f.uploads++;
                    });
            f.time.drain();
            check(
                    f.uploads == 1 && f.source.calls == 2 && f.source.active.isEmpty(),
                    "replacing an upload suppresses its completed-but-unadmitted old result and"
                        + " closes both scopes");
            f.transfers.load(
                    f.local, new AttachmentTransfers.Room("unpaired", false, null), true, f);
            f.time.drain();
            f.main();
            check(
                    f.ready == 1 && f.file.equals(f.localFile),
                    "local previews remain readable without room credentials");
            f.cached = f.localFile;
            f.transfers.load(
                    f.remote, new AttachmentTransfers.Room("unpaired", false, null), true, f);
            f.time.drain();
            f.main();
            check(
                    f.ready == 2 && f.source.calls == 2,
                    "offline cached previews need neither HTTP nor authorization");
        }
        try (Fixture f = new Fixture()) {
            f.transfers.load(f.remote, f.room(true), true, f);
            f.time.drain();
            File created = f.cached;
            check(
                    created.isFile() && f.ready == 0,
                    "download waits for UI admission after state accepts its bytes");
            f.transfers.reset();
            f.main();
            check(
                    f.failed == 1 && f.ready == 0 && !created.exists(),
                    "room reset invalidates already-posted UI results and removes temporary"
                        + " downloaded bytes");
        }
        try (Fixture f = new Fixture()) {
            f.transfers.load(f.remote, f.room(true), true, f);
            f.transfers.reset();
            f.time.drain();
            f.main();
            check(
                    f.failed == 1 && f.ready == 0 && f.source.calls == 0,
                    "reset cancels queued downloads with exactly one failure callback and no I/O");
            f.time.worker.occupied = true;
            f.transfers.load(f.remote, f.room(false), true, f);
            f.main();
            check(
                    f.failed == 2 && f.source.calls == 0,
                    "full transfer admission fails its caller without leaking a scope");
        }
        return checked;
    }

    private static final class Fixture
            implements AutoCloseable, AttachmentTransfers.Storage, AttachmentTransfers.Result {
        final AsyncHarness time = new AsyncHarness();
        final Queue<Runnable> ui = new ArrayDeque<>();
        final Source source = new Source();
        final Path directory = Files.createTempDirectory("pocket-transfers-");
        final File localFile = directory.resolve("local.txt").toFile();
        final Attachment local, remote;
        final AttachmentTransfers transfers =
                new AttachmentTransfers(this, time.state, ui::add, ignored -> {}, time.worker);
        File file, cached;
        int ready, failed, uploads;

        Fixture() throws Exception {
            Files.write(localFile.toPath(), Source.BODY);
            local =
                    new Attachment(
                            "fixture.txt",
                            "text/plain",
                            localFile.getPath(),
                            "",
                            "",
                            3,
                            Attachment.UPLOADING);
            remote =
                    new Attachment(
                            "fixture.txt",
                            "text/plain",
                            "",
                            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                            "/fixture",
                            3,
                            Attachment.UPLOADED);
        }

        AttachmentTransfers.Room room(boolean temporary) {
            return new AttachmentTransfers.Room(
                    temporary ? "try:fixture" : "fixture", temporary, source.access);
        }

        void main() {
            while (!ui.isEmpty()) ui.remove().run();
        }

        public void ready(File file, String mime) {
            ready++;
            this.file = file;
        }

        public void failed() {
            failed++;
        }

        public File shareable(File source, Attachment attachment, boolean temporary) {
            return source;
        }

        public File cached(Attachment attachment, String scope) {
            return cached;
        }

        public File cache(Attachment attachment, byte[] bytes, String scope)
                throws java.io.IOException {
            cached = directory.resolve("download.txt").toFile();
            Files.write(cached.toPath(), bytes);
            return cached;
        }

        public void close() throws Exception {
            transfers.close();
            source.access.close();
            time.close();
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static final class Source implements ChannelConnection {
        static final byte[] BODY = "abc".getBytes(StandardCharsets.UTF_8);
        final Set<Long> active = new HashSet<>();
        final RoomAccess access =
                new RoomAccess(
                        this, new Endpoint("example.test", 443, true), "fixture", "qa", "qa", null);
        int calls;

        public void beginRequest(long id) {
            active.add(id);
        }

        public void endRequest(long id) {
            active.remove(id);
        }

        public Response requestScoped(
                long id,
                Endpoint e,
                String t,
                String method,
                String path,
                JSONObject h,
                byte[] b,
                long timeout) {
            if (!active.contains(id)) throw new AssertionError("Unregistered attachment I/O");
            calls++;
            return new Response(
                    200,
                    method.equals("GET")
                            ? BODY
                            : Json.object("path", "/fixture")
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8));
        }

        public Response request(
                Endpoint e, String t, String m, String p, JSONObject h, byte[] b, long timeout) {
            throw new AssertionError();
        }

        public Response stream(
                Endpoint e,
                String t,
                long id,
                String p,
                byte[] b,
                StreamListener l,
                BooleanSupplier c) {
            throw new AssertionError();
        }

        public void cancelStream(long id) {}

        public void live(
                Endpoint e, String t, String p, JSONObject h, LiveListener l, BooleanSupplier c) {}

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] packet) {}

        public boolean isOpen() {
            return true;
        }

        public void closeLive() {}
    }
}
