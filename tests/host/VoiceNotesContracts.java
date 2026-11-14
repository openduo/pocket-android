// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class VoiceNotesContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    private static final class Store implements VoiceNotes.Store {
        String room = "qa:real";
        boolean failing;
        final List<VoiceInbox.Note> admitted = new ArrayList<>();
        final Set<String> receipts = new HashSet<>();

        public String room() {
            return room;
        }

        public void enqueue(VoiceInbox.Note note) {
            if (failing) throw new IllegalStateException("injected storage failure");
            admitted.add(note);
            receipts.add(note.id);
        }

        public void acknowledge(String id) {
            receipts.remove(id);
        }

        public Set<String> receipts() {
            return new HashSet<>(receipts);
        }
    }

    static int run() throws Exception {
        Path root = Files.createTempDirectory("pocket-input-voice");
        VoiceInbox real = new VoiceInbox(root.resolve("real").toFile());
        VoiceInbox trial = new VoiceInbox(root.resolve("try").toFile());
        List<String> errors = new ArrayList<>();
        VoiceNotes voice =
                new VoiceNotes(
                        real,
                        trial,
                        new VoiceNotes.Events() {
                            public void error(String message) {
                                errors.add(message);
                            }

                            public void log(String event) {}

                            public void admitted() {}
                        });
        Store store = new Store();
        try {
            check(
                    voice.begin("physical:rec", store.room, false),
                    "hardware source acquires one note");
            check(
                    !voice.begin("touch", store.room, false)
                            && voice.owned("physical:rec")
                            && !voice.owned("touch"),
                    "another source cannot replace the active voice owner");
            voice.ready();
            File cancelled = voice.file();
            packets(voice, 20);
            check(
                    voice.live() && voice.packets() == 20,
                    "capture readiness and duration belong to voice owner");
            voice.finish(true, false, store);
            check(
                    !voice.active()
                            && !voice.live()
                            && !cancelled.exists()
                            && store.admitted.isEmpty(),
                    "explicit cancel discards bytes without enqueuing");
            voice.begin("touch", store.room, false);
            packets(voice, 14);
            voice.finish(false, false, store);
            check(
                    store.admitted.isEmpty() && !errors.isEmpty(),
                    "a subminimum note never becomes an outgoing message");
            voice.begin("remote", store.room, false);
            packets(voice, 20);
            voice.finish(false, false, store);
            VoiceInbox.Note sent = store.admitted.get(0);
            check(
                    sent.packets == 20
                            && sent.file.isFile()
                            && !real.awaiting(sent.id)
                            && store.receipts.isEmpty(),
                    "release preserves durable admission and atomic receipt cleanup");
            store.failing = true;
            voice.begin("physical:rec", store.room, false);
            packets(voice, 20);
            voice.finish(false, false, store);
            VoiceInbox.Note retained = voice.retained(store.room).get(0);
            check(
                    voice.hasRetained(retained.id) && real.awaiting(retained.id),
                    "failed storage retains the original identity for retry");
            store.room = "qa:other";
            store.failing = false;
            check(
                    !voice.retry(retained.id, store) && voice.retained(store.room).isEmpty(),
                    "retry cannot move a voice into another room");
            store.room = "qa:real";
            check(
                    voice.retry(retained.id, store) && !voice.hasRetained(retained.id),
                    "manual retry admits the same retained voice");
            voice.begin("remote", store.room, false);
            packets(voice, 20);
            voice.finish(false, true, store);
            check(
                    store.admitted.get(2).item(false).state == Message.FAILED,
                    "capture interruption requires review and does not auto-send");
            store.failing = true;
            voice.begin("try-key", "try:room", true);
            packets(voice, 20);
            store.room = "try:room";
            voice.finish(false, false, store);
            check(voice.retained(store.room).size() == 1, "Try uses an independent voice inbox");
            voice.forgetTry();
            check(
                    voice.retained(store.room).isEmpty(),
                    "leaving Try removes only Try admissions from application memory");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
        return checked;
    }

    private static void packets(VoiceNotes voice, int count) throws Exception {
        for (int i = 0; i < count; i++) voice.append(new byte[] {9, 8});
    }
}
