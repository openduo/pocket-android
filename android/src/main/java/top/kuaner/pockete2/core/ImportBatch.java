// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One complete, ordered delivery. An empty scope is content received before pairing. */
public final class ImportBatch {
    public final String id, scope, text, ownedPhoto;
    public final long at;
    public final List<Attachment> attachments;

    public ImportBatch(String id, String scope, String text, long at, List<Attachment> files) {
        this(id, scope, text, at, files, "");
    }

    public ImportBatch(
            String id,
            String scope,
            String text,
            long at,
            List<Attachment> files,
            String ownedPhoto) {
        requireId(id);
        requirePhotoName(ownedPhoto);
        this.id = id;
        this.scope = scope == null ? "" : scope;
        this.text = text == null ? "" : text.trim();
        this.at = at;
        this.ownedPhoto = ownedPhoto;
        attachments = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public String appendTo(String draft) {
        String existing = draft == null ? "" : draft;
        return DraftText.append(existing, text);
    }

    public static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Invalid import delivery id");
    }

    public static void requirePhotoName(String name) {
        if (name == null || !name.isEmpty() && !name.matches("photo-[a-zA-Z0-9._-]{1,128}\\.jpg"))
            throw new IllegalArgumentException("Invalid owned photo name");
    }
}
