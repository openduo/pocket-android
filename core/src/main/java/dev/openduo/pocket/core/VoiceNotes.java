// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * One owned recording and durable admissions. All calls belong to the application's state owner.
 */
public final class VoiceNotes {
    public interface Store {
        String room();

        void enqueue(VoiceInbox.Note note);

        void acknowledge(String id);

        Set<String> receipts();
    }

    public interface Events {
        void error(String message);

        void log(String event);

        void admitted();
    }

    private static final class Admission {
        final VoiceInbox inbox;
        final VoiceInbox.Note note;

        Admission(VoiceInbox inbox, VoiceInbox.Note note) {
            this.inbox = inbox;
            this.note = note;
        }
    }

    private final VoiceInbox realInbox, tryInbox;
    private final Events events;
    private final Map<String, Admission> admissions = new LinkedHashMap<>();
    private VoiceInbox inbox;
    private VoiceInbox.Note note;
    private FileOutputStream fileOutput;
    private BufferedOutputStream output;
    private String owner = "";
    private boolean live;
    private long packets, lastPublish;

    public VoiceNotes(VoiceInbox realInbox, VoiceInbox tryInbox, Events events) {
        this.realInbox = realInbox;
        this.tryInbox = tryInbox;
        this.events = events;
    }

    public boolean active() {
        return !owner.isEmpty();
    }

    public boolean live() {
        return live;
    }

    public String owner() {
        return owner;
    }

    public boolean owned(String value) {
        return active() && owner.equals(value);
    }

    public long packets() {
        return packets;
    }

    public File file() {
        return note == null ? null : note.file;
    }

    public void ready() {
        if (active()) live = true;
    }

    public boolean begin(String owner, String room, boolean trying) {
        if (active() || owner == null || owner.isEmpty()) return false;
        try {
            inbox = trying ? tryInbox : realInbox;
            note = inbox.begin(room);
            fileOutput = new FileOutputStream(note.file);
            output = new BufferedOutputStream(fileOutput);
            this.owner = owner;
            live = false;
            packets = lastPublish = 0;
            return true;
        } catch (IOException | RuntimeException failure) {
            discardCurrent();
            events.error("无法准备语音存储");
            return false;
        }
    }

    /** Returns whether a bounded progress update is due; the capture owner still owns the mic. */
    public boolean append(byte[] packet) throws IOException {
        if (!active() || output == null) return false;
        output.write(packet.length & 255);
        output.write(packet.length >>> 8);
        output.write(packet);
        packets++;
        if (packets % 50 == 0) output.flush();
        if (packets * 20 - lastPublish < 100) return false;
        lastPublish = packets * 20;
        return true;
    }

    public boolean tooLarge() {
        return file() != null && file().length() > 16L * 1024 * 1024;
    }

    /**
     * Finishes the stream before admitting bytes. An interrupted note remains manually retryable.
     */
    public boolean finish(boolean cancel, boolean interrupted, Store store) {
        if (!active()) return true;
        owner = "";
        live = false;
        boolean writeFailed = false;
        try {
            if (output != null && !cancel && packets >= 15) {
                output.flush();
                fileOutput.getFD().sync();
            }
        } catch (IOException failure) {
            writeFailed = true;
            events.error("语音保存失败，请检查存储空间");
        } finally {
            try {
                if (output != null) output.close();
            } catch (IOException failure) {
                writeFailed = true;
                events.error("语音保存失败，请检查存储空间");
            }
        }
        output = null;
        fileOutput = null;
        if (cancel || packets < 15) {
            discardCurrent();
            if (!cancel) events.error("按住说话，麦克风就绪后再松开");
        } else {
            try {
                VoiceInbox.Note sealed = inbox.seal(note, packets, interrupted || writeFailed);
                if (sealed == null) discardCurrent();
                else {
                    Admission admission = new Admission(inbox, sealed);
                    admissions.put(sealed.id, admission);
                    admit(admission, store);
                }
            } catch (IOException failure) {
                events.log("seal_deferred");
                events.error("语音保存失败，请检查存储空间");
            }
        }
        note = null;
        inbox = null;
        return !writeFailed;
    }

    private void discardCurrent() {
        try {
            if (output != null) output.close();
        } catch (IOException ignored) {
        }
        output = null;
        fileOutput = null;
        try {
            if (note != null) inbox.discard(note);
        } catch (IOException failure) {
            events.log("cancel_cleanup_deferred");
            events.error("录音未能删除，请稍后重试");
        }
        note = null;
        inbox = null;
        owner = "";
        live = false;
    }

    private boolean admit(Admission admission, Store store) {
        if (!store.room().equals(admission.note.room)) return false;
        try {
            admission.inbox.remember(admission.note);
            store.enqueue(admission.note);
        } catch (IOException | RuntimeException failure) {
            events.log("admission_deferred");
            events.error(VoiceInbox.UNSAVED);
            return false;
        }
        admissions.remove(admission.note.id);
        try {
            admission.inbox.acknowledge(admission.note.id);
            store.acknowledge(admission.note.id);
        } catch (IOException | RuntimeException failure) {
            events.log("acknowledgement_deferred");
        }
        events.admitted();
        return true;
    }

    public boolean hasRetained(String id) {
        return admissions.containsKey(id);
    }

    public boolean retry(String id, Store store) {
        Admission admission = admissions.get(id);
        return admission != null && admit(admission, store);
    }

    public void discard(String id) throws IOException {
        Admission admission = admissions.get(id);
        if (admission == null) return;
        admission.inbox.discard(admission.note);
        admissions.remove(id);
    }

    public List<VoiceInbox.Note> retained(String room) {
        List<VoiceInbox.Note> result = new ArrayList<>();
        for (Admission admission : admissions.values())
            if (admission.note.room.equals(room)) result.add(admission.note);
        return Collections.unmodifiableList(result);
    }

    public boolean discardRetained(String room) {
        for (VoiceInbox.Note note : retained(room)) {
            try {
                discard(note.id);
            } catch (IOException failure) {
                events.error("录音未能删除，请稍后重试");
                return false;
            }
        }
        return true;
    }

    public void forgetTry() {
        admissions.values().removeIf(a -> a.inbox == tryInbox);
    }

    public void recover(Store real, Store current, boolean trying) {
        scan(realInbox, real);
        if (trying) scan(tryInbox, current);
        for (Admission admission : new ArrayList<>(admissions.values()))
            if (admission.note.room.equals(current.room())) admit(admission, current);
    }

    private void scan(VoiceInbox inbox, Store store) {
        try {
            Set<String> active = new HashSet<>(admissions.keySet());
            if (note != null) active.add(note.id);
            Set<String> receipts = store.receipts();
            VoiceInbox.Scan scan = inbox.pending(receipts, active);
            for (VoiceInbox.Note note : scan.notes)
                admissions.put(note.id, new Admission(inbox, note));
            for (String id : receipts) if (!inbox.awaiting(id)) store.acknowledge(id);
            if (scan.unreadable > 0) events.error("有录音未能恢复，请检查存储空间后重新打开应用");
        } catch (IOException | RuntimeException failure) {
            events.log("recovery_deferred");
            events.error("有录音未能恢复，请检查存储空间后重新打开应用");
        }
    }
}
