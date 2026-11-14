// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class VoiceInboxContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    private static void packets(VoiceInbox.Note note, int count, boolean broken) throws Exception {
        try (FileOutputStream output = new FileOutputStream(note.file)) {
            for (int i = 0; i < count; i++) output.write(new byte[] {2, 0, 9, 8});
            if (broken) output.write(new byte[] {5, 0, 7});
            output.getFD().sync();
        }
    }

    static int run() throws Exception {
        File root = Files.createTempDirectory("duoduo-pocket-voice").toFile();
        VoiceInbox inbox = new VoiceInbox(root);
        Set<String> none = Collections.emptySet();
        try {
            VoiceInbox.Note recording = inbox.begin("qa:one");
            check(
                    inbox.awaiting(recording.id) && recording.file.isFile(),
                    "room and stable identity are durable before recording starts");
            packets(recording, 25, false);
            check(
                    inbox.pending(none, Collections.singleton(recording.id)).notes.isEmpty(),
                    "foreground recovery never claims the still-active recording");
            VoiceInbox.Note ready = inbox.seal(recording, 25, false);
            inbox.remember(ready);
            VoiceInbox.Note reopened = new VoiceInbox(root).pending(none, none).notes.get(0);
            check(
                    reopened.id.equals(recording.id)
                            && reopened.room.equals("qa:one")
                            && reopened.at == recording.at
                            && reopened.bytes == 100
                            && reopened.packets == 25
                            && reopened.item(false).state == Message.QUEUED
                            && reopened.item(false).durationMs == 500,
                    "a completed send handoff reopens with its exact identity, room, bytes and"
                            + " duration");
            check(
                    reopened.item(true).state == Message.FAILED
                            && reopened.row(true).error.equals(VoiceInbox.UNSAVED),
                    "uncommitted storage failure exposes the same retryable voice identity");
            inbox.acknowledge(ready.id);
            check(
                    !inbox.awaiting(ready.id)
                            && ready.file.isFile()
                            && inbox.pending(none, none).notes.isEmpty(),
                    "handoff acknowledgment retains bytes owned by the admitted outbox");
            ready.file.delete();

            VoiceInbox.Note interrupted = inbox.begin("qa:two");
            packets(interrupted, 27, true);
            VoiceInbox.Note recovered = new VoiceInbox(root).pending(none, none).notes.get(0);
            check(
                    recovered.item(false).state == Message.FAILED
                            && recovered.packets == 27
                            && recovered.item(false).durationMs == 540
                            && recovered.file.length() == 111,
                    "an unfinished hold recovers its complete prefix for review and never"
                            + " auto-sends");
            inbox.remember(recovered);
            check(
                    recovered.file.length() == 108,
                    "durable handoff trims an incomplete trailing frame");
            inbox.discard(recovered);
            check(
                    !recovered.file.exists() && !inbox.awaiting(recovered.id),
                    "explicit discard removes the cancelled note and its handoff");

            VoiceInbox.Note shortNote = inbox.begin("qa:short");
            packets(shortNote, 14, false);
            check(
                    inbox.pending(none, none).notes.isEmpty() && !shortNote.file.exists(),
                    "a killed subminimum hold does not create a sendable note");
            VoiceInbox.Note cancelled = inbox.begin("qa:cancel");
            packets(cancelled, 30, false);
            Files.move(
                    new File(root, cancelled.id + ".json").toPath(),
                    new File(root, cancelled.id + ".discarded").toPath());
            boolean revived = false;
            try {
                inbox.remember(cancelled);
                revived = true;
            } catch (IOException expected) {
            }
            check(!revived, "retry cannot recreate a manifest after cancellation was committed");
            check(
                    new VoiceInbox(root).pending(none, none).notes.isEmpty()
                            && !cancelled.file.exists()
                            && !inbox.awaiting(cancelled.id),
                    "a persisted cancellation cannot revive after process death before file"
                            + " cleanup");

            VoiceInbox.Note admitted = inbox.begin("qa:admitted");
            packets(admitted, 22, false);
            admitted.file.delete();
            check(
                    inbox.pending(Collections.singleton(admitted.id), none).unreadable == 0
                            && !inbox.awaiting(admitted.id),
                    "an atomic SQLite receipt acknowledges a delivered/deleted recording without"
                            + " reading its removed bytes");
            VoiceInbox.Note changed = inbox.begin("qa:changed");
            packets(changed, 22, false);
            inbox.remember(inbox.seal(changed, 22, false));
            packets(changed, 23, false);
            VoiceInbox.Note changedRecovered = inbox.pending(none, none).notes.get(0);
            check(
                    changedRecovered.item(false).state == Message.FAILED
                            && changedRecovered.packets == 23,
                    "changed completed bytes require explicit review rather than automatic upload");
            inbox.discard(changedRecovered);

            VoiceInbox.Note invalid = inbox.begin("qa:invalid");
            packets(invalid, 25, false);
            Files.write(new File(root, invalid.id + ".json").toPath(), new byte[] {9});
            check(
                    inbox.pending(none, none).unreadable == 1 && invalid.file.isFile(),
                    "an unreadable manifest is reported and never reassigned to the current room");
            inbox.discard(invalid);
            File outside = File.createTempFile("duoduo-pocket-outside", ".bin");
            try {
                VoiceInbox.Note link = inbox.begin("qa:link");
                Files.delete(link.file.toPath());
                Files.createSymbolicLink(link.file.toPath(), outside.toPath());
                check(
                        inbox.pending(none, none).unreadable == 1 && outside.isFile(),
                        "a manifest cannot escape its voice root through a replaced symlink");
                Files.delete(link.file.toPath());
                Files.delete(new File(root, link.id + ".json").toPath());
            } finally {
                outside.delete();
            }
        } finally {
            File[] files = root.listFiles();
            if (files != null) for (File file : files) file.delete();
            root.delete();
        }
        return checked;
    }
}
