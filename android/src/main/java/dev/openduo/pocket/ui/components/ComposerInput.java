// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.components;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.*;
import android.widget.EditText;

import java.util.function.BiConsumer;

/** Image paste and API-25 keyboard content share the same normal attachment admission path. */
public final class ComposerInput extends EditText {
    private final BiConsumer<Uri, Runnable> attach;

    public ComposerInput(Context context, BiConsumer<Uri, Runnable> attach) {
        super(context);
        this.attach = attach;
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getDescription().hasMimeType("image/*")) {
                boolean admitted = false;
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null && "content".equals(uri.getScheme())) {
                        attach.accept(uri, () -> {});
                        admitted = true;
                    }
                }
                if (admitted) return true;
            }
        }
        return super.onTextContextMenuItem(id);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection original = super.onCreateInputConnection(info);
        if (original == null) return null;
        info.contentMimeTypes = new String[] {"image/png", "image/jpeg", "image/webp", "image/gif"};
        return new InputConnectionWrapper(original, false) {
            @Override
            public boolean commitContent(InputContentInfo content, int flags, Bundle options) {
                if (content == null || !content.getDescription().hasMimeType("image/*"))
                    return false;
                try {
                    if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0)
                        content.requestPermission();
                    attach.accept(content.getContentUri(), content::releasePermission);
                    return true;
                } catch (SecurityException denied) {
                    return false;
                }
            }
        };
    }
}
