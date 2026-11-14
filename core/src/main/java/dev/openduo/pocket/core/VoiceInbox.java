// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import dev.openduo.pocket.audio.PacketStream;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * File-only voice handoff, owned by the state queue. SQLite admission acknowledges its manifest.
 */
public final class VoiceInbox {
    public static final long MAX_BYTES = 16L * 1024 * 1024;
    public static final String UNSAVED = "录音尚未保存 · 轻点重试";
    private static final String RECORDING = "recording",
            READY = "ready",
            INTERRUPTED = "interrupted";
    private final File root;

    public static final class Note {
        public final String id, room;
        public final File file;
        public final long at, packets, bytes;
        private final String phase;

        private Note(
                String id,
                String room,
                File file,
                long at,
                long packets,
                long bytes,
                String phase) {
            this.id = id;
            this.room = room;
            this.file = file;
            this.at = at;
            this.packets = packets;
            this.bytes = bytes;
            this.phase = phase;
        }

        public Outbox.Item item(boolean unsaved) {
            int state = unsaved || !phase.equals(READY) ? Message.FAILED : Message.QUEUED;
            return new Outbox.Item(
                    id,
                    "",
                    true,
                    file.getPath(),
                    "phone",
                    packets * 20,
                    Collections.emptyList(),
                    state,
                    unsaved ? UNSAVED : state == Message.FAILED ? "录音被打断 · 轻点重试" : "",
                    "",
                    at,
                    0);
        }

        public Message row(boolean unsaved) {
            Outbox.Item item = item(unsaved);
            return new Message(
                    id,
                    Message.MINE,
                    at,
                    "",
                    "phone",
                    item.durationMs,
                    item.state,
                    item.error,
                    true,
                    false,
                    Collections.emptyList(),
                    -1);
        }
    }

    public static final class Scan {
        public final List<Note> notes;
        public final int unreadable;

        private Scan(List<Note> notes, int unreadable) {
            this.notes = Collections.unmodifiableList(notes);
            this.unreadable = unreadable;
        }
    }

    public VoiceInbox(File root) {
        this.root = root;
    }

    /** Persist identity and room before acquiring the mic; an abandoned hold is never auto-sent. */
    public Note begin(String room) throws IOException {
        if (room == null || room.isEmpty() || room.equals("unpaired"))
            throw new IOException("Voice note has no room");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create voice inbox");
        String id = UUID.randomUUID().toString();
        File file = child(id, ".packets");
        if (!file.createNewFile()) throw new IOException("Voice note already exists");
        Note note = new Note(id, room, file, System.currentTimeMillis(), 0, 0, RECORDING);
        try {
            remember(note);
            return note;
        } catch (IOException failure) {
            file.delete();
            throw failure;
        }
    }

    /** Called after closing the writer. Recover only complete frames after a broken write. */
    public Note seal(Note note, long expectedPackets, boolean interrupted) throws IOException {
        requireOwned(note);
        if (!note.file.isFile() || note.file.length() > MAX_BYTES)
            throw new IOException("Missing or oversized voice note");
        long count = 0, complete = 0;
        try (RandomAccessFile input = new RandomAccessFile(note.file, "r")) {
            while (input.getFilePointer() < input.length()) {
                int low = input.read(), high = input.read();
                if (high < 0) break;
                int size = low | high << 8;
                if (size < 1
                        || size > PacketStream.MAX_PACKET_BYTES
                        || size > input.length() - input.getFilePointer()) break;
                input.seek(input.getFilePointer() + size);
                count++;
                complete = input.getFilePointer();
            }
            interrupted |= count != expectedPackets || complete != input.length();
        }
        if (count < 15) return null;
        return new Note(
                note.id,
                note.room,
                note.file,
                note.at,
                count,
                complete,
                interrupted ? INTERRUPTED : READY);
    }

    /**
     * Atomic replacement leaves the earlier recording manifest usable when storage is exhausted.
     */
    public void remember(Note note) throws IOException {
        requireOwned(note);
        if (child(note.id, ".discarded").exists())
            throw new IOException("Voice note was cancelled");
        if (!note.phase.equals(RECORDING)) {
            try (RandomAccessFile file = new RandomAccessFile(note.file, "rw")) {
                if (file.length() < note.bytes || note.bytes > MAX_BYTES)
                    throw new IOException("Voice bytes changed before handoff");
                if (file.length() != note.bytes) {
                    if (!note.phase.equals(INTERRUPTED))
                        throw new IOException("Voice length changed");
                    file.setLength(note.bytes);
                }
                file.getFD().sync();
            }
        }
        byte[] bytes =
                Json.object(
                                "version",
                                1,
                                "id",
                                note.id,
                                "room",
                                note.room,
                                "at",
                                note.at,
                                "phase",
                                note.phase,
                                "packets",
                                note.packets,
                                "bytes",
                                note.bytes)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16_384) throw new IOException("Voice manifest too large");
        File next = child(note.id, ".next");
        try {
            try (FileOutputStream output = new FileOutputStream(next)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(
                    next.toPath(),
                    child(note.id, ".json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            next.delete();
        }
    }

    public Scan pending(Set<String> receipts, Set<String> active) throws IOException {
        List<Note> notes = new ArrayList<>();
        int unreadable = 0;
        File[] entries = root.listFiles();
        if (entries == null) {
            if (root.exists()) throw new IOException("Cannot read voice inbox");
            return new Scan(notes, 0);
        }
        for (File entry : entries) {
            String name = entry.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0) continue;
            String id = name.substring(0, dot), suffix = name.substring(dot);
            if (!validId(id) || active.contains(id)) continue;
            try {
                if (suffix.equals(".discarded")) {
                    cleanupDiscarded(id);
                } else if (suffix.equals(".json")) {
                    if (receipts.contains(id)) {
                        acknowledge(id);
                        continue;
                    }
                    if (child(id, ".discarded").exists()) continue;
                    Note prior = read(id);
                    Note note =
                            seal(
                                    prior,
                                    prior.packets,
                                    !prior.phase.equals(READY)
                                            || prior.bytes != prior.file.length());
                    if (note == null) discard(prior);
                    else notes.add(note);
                } else if (suffix.equals(".next")) Files.deleteIfExists(child(id, suffix).toPath());
            } catch (IOException failure) {
                unreadable++;
            }
        }
        notes.sort(Comparator.comparingLong((Note note) -> note.at).thenComparing(note -> note.id));
        return new Scan(notes, unreadable);
    }

    public boolean awaiting(String id) throws IOException {
        return child(id, ".json").exists() || child(id, ".discarded").exists();
    }

    /** Only the manifest is acknowledged: the admitted outbox now owns the recording's bytes. */
    public void acknowledge(String id) throws IOException {
        Files.deleteIfExists(child(id, ".json").toPath());
        Files.deleteIfExists(child(id, ".next").toPath());
    }

    /** Rename a cancellation marker first, so failed deletion cannot revive a cancelled note. */
    public void discard(Note note) throws IOException {
        requireOwned(note);
        File manifest = child(note.id, ".json"), marker = child(note.id, ".discarded");
        if (manifest.exists())
            Files.move(
                    manifest.toPath(),
                    marker.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        cleanupDiscarded(note.id);
    }

    private void cleanupDiscarded(String id) throws IOException {
        Files.deleteIfExists(child(id, ".packets").toPath());
        Files.deleteIfExists(child(id, ".next").toPath());
        Files.deleteIfExists(child(id, ".json").toPath());
        Files.deleteIfExists(child(id, ".discarded").toPath());
    }

    private Note read(String id) throws IOException {
        File manifest = child(id, ".json");
        if (!manifest.isFile() || manifest.length() > 16_384)
            throw new IOException("Invalid voice manifest");
        try {
            JSONObject value =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            String phase = value.getString("phase"), room = value.getString("room");
            long at = value.getLong("at"),
                    packets = value.getLong("packets"),
                    bytes = value.getLong("bytes");
            if (value.getInt("version") != 1
                    || !id.equals(value.getString("id"))
                    || room.isEmpty()
                    || room.equals("unpaired")
                    || at < 0
                    || packets < 0
                    || bytes < 0
                    || bytes > MAX_BYTES
                    || !Arrays.asList(RECORDING, READY, INTERRUPTED).contains(phase))
                throw new IOException("Invalid voice identity or state");
            return new Note(id, room, child(id, ".packets"), at, packets, bytes, phase);
        } catch (org.json.JSONException failure) {
            throw new IOException("Invalid voice manifest", failure);
        }
    }

    private void requireOwned(Note note) throws IOException {
        if (!child(note.id, ".packets").equals(note.file))
            throw new IOException("Voice note is outside inbox");
    }

    private File child(String id, String suffix) throws IOException {
        if (!validId(id)) throw new IOException("Invalid voice identity");
        File file = new File(root, id + suffix).getCanonicalFile();
        if (!root.getCanonicalFile().equals(file.getParentFile()))
            throw new IOException("Voice path is outside inbox");
        return file;
    }

    private static boolean validId(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
