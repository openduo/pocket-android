// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Room-local composition state. Mutations belong to state; edit tickets may be issued by UI. */
public final class Composer {
    public static final class Content {
        public final String text, quote;
        public final List<Attachment> attachments;

        public Content(String text, String quote, List<Attachment> attachments) {
            this.text = text;
            this.quote = quote;
            this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
        }

        public String encodedAttachments() {
            JSONArray encoded = new JSONArray();
            try {
                for (Attachment attachment : attachments) encoded.put(attachment.json(true));
            } catch (JSONException impossible) {
                throw new AssertionError(impossible);
            }
            return encoded.toString();
        }
    }

    private final AtomicLong edits = new AtomicLong();
    private Content content = new Content("", "", Collections.emptyList());
    private long generation, editAck;

    public Content content() {
        return content;
    }

    public long generation() {
        return generation;
    }

    public long editAck() {
        return editAck;
    }

    public long nextEdit() {
        return edits.incrementAndGet();
    }

    public void text(String text) {
        content = new Content(text == null ? "" : text, content.quote, content.attachments);
        generation++;
    }

    public void edit(String before, String after, long generation, long ticket) {
        if (generation == this.generation)
            content =
                    new Content(
                            DraftText.edit(content.text, before, after),
                            content.quote,
                            content.attachments);
        editAck = Math.max(editAck, ticket);
    }

    public void append(String text) {
        content =
                new Content(
                        DraftText.append(content.text, text), content.quote, content.attachments);
    }

    public void quote(String quote) {
        content = new Content(content.text, quote == null ? "" : quote, content.attachments);
    }

    public Content importing(ImportBatch batch) {
        List<Attachment> combined = new ArrayList<>(content.attachments);
        combined.addAll(batch.attachments);
        return new Content(batch.appendTo(content.text), content.quote, combined);
    }

    /** Apply only after the repository accepts the import's once-only transaction. */
    public void imported(Content admitted) {
        content = admitted;
    }

    public String message(boolean textMode) {
        String text = textMode ? content.text.trim() : "";
        if (textMode && !content.quote.isEmpty())
            text = "> " + content.quote.replace("\n", "\n> ") + "\n\n" + text;
        return text;
    }

    public boolean attachmentsReady() {
        return content.attachments.stream().allMatch(a -> a.state == Attachment.UPLOADED);
    }

    /** Call after enqueue succeeds; attachment-only sends retain the user's text and quote. */
    public void sent(boolean textMode) {
        if (textMode) generation++;
        content =
                new Content(
                        textMode ? "" : content.text,
                        textMode ? "" : content.quote,
                        Collections.emptyList());
    }

    public boolean replace(Attachment attachment) {
        List<Attachment> next = new ArrayList<>(content.attachments);
        for (int i = 0; i < next.size(); i++)
            if (next.get(i).localPath.equals(attachment.localPath)) {
                next.set(i, attachment);
                content = new Content(content.text, content.quote, next);
                return true;
            }
        return false;
    }

    public List<Attachment> remove(String path) {
        List<Attachment> removed = new ArrayList<>(), remaining = new ArrayList<>();
        for (Attachment attachment : content.attachments)
            (attachment.localPath.equals(path) ? removed : remaining).add(attachment);
        content = new Content(content.text, content.quote, remaining);
        return removed;
    }

    public boolean retireUploads() {
        boolean changed = false;
        for (Attachment attachment : content.attachments)
            if (attachment.state == Attachment.UPLOADING) {
                replace(attachment.failed());
                changed = true;
            }
        return changed;
    }

    public void reset() {
        content = new Content("", "", Collections.emptyList());
        generation++;
    }

    public void restore(String text, String quote, String encoded) {
        List<Attachment> attachments = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(encoded);
            for (int i = 0; i < values.length(); i++) {
                Attachment value = Attachment.from(values.optJSONObject(i));
                if (value != null && new File(value.localPath).isFile())
                    attachments.add(value.state == Attachment.UPLOADING ? value.failed() : value);
            }
        } catch (JSONException ignored) {
        }
        content = new Content(text, quote, attachments);
        generation++;
    }
}
