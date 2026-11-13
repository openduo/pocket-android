// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.*;

/**
 * Intent adaptation only. Sources remain ordered, and repeated URI representations are one file.
 */
public final class InboundContent {
    public final String text;
    public final List<Uri> files;
    private static final Map<Uri, Integer> READERS = new HashMap<>();

    private InboundContent(String text, Collection<Uri> files) {
        this.text = text == null ? "" : text;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public static InboundContent share(Intent intent) {
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        android.os.Bundle extras = intent.getExtras();
        Object stream = extras == null ? null : extras.get(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) add(files, (Uri) stream);
        else if (stream instanceof ArrayList)
            for (Object item : (ArrayList<?>) stream)
                if (item instanceof Uri) add(files, (Uri) item);
        ClipData clip = intent.getClipData();
        StringBuilder clipText = new StringBuilder();
        if (clip != null)
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                add(files, item.getUri());
                Uri uri = item.getUri();
                CharSequence plain = item.getText();
                if (plain == null
                        && uri != null
                        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
                    plain = uri.toString();
                if (text == null
                        && (uri == null || !"content".equals(uri.getScheme()))
                        && plain != null) {
                    if (clipText.length() > 0) clipText.append('\n');
                    clipText.append(plain);
                }
            }
        add(files, intent.getData());
        return new InboundContent(text == null ? clipText.toString() : text.toString(), files);
    }

    public static InboundContent picked(Intent intent) {
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        ClipData clip = intent.getClipData();
        if (clip != null)
            for (int i = 0; i < clip.getItemCount(); i++) add(files, clip.getItemAt(i).getUri());
        add(files, intent.getData());
        return new InboundContent("", files);
    }

    private static void add(Set<Uri> files, Uri uri) {
        if (uri != null && "content".equals(uri.getScheme())) files.add(uri);
    }

    /** Reference-count document grants so overlapping deliveries cannot revoke another's read. */
    public Runnable retainReads(Context context, int flags) {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        List<Uri> retained = new ArrayList<>();
        synchronized (READERS) {
            for (Uri uri : files) {
                Integer readers = READERS.get(uri);
                if (readers == null) {
                    boolean persisted = false;
                    for (android.content.UriPermission grant :
                            resolver.getPersistedUriPermissions())
                        if (grant.isReadPermission() && grant.getUri().equals(uri))
                            persisted = true;
                    if (!persisted
                            && (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                            && (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                        try {
                            resolver.takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            persisted = true;
                        } catch (SecurityException | IllegalArgumentException ignored) {
                            // The returned temporary grant still covers the immediate copy.
                        }
                    }
                    if (!persisted) continue;
                    readers = 0;
                }
                READERS.put(uri, readers + 1);
                retained.add(uri);
            }
        }
        java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (READERS) {
                for (Uri uri : retained) {
                    int readers = READERS.get(uri) - 1;
                    if (readers > 0) READERS.put(uri, readers);
                    else {
                        READERS.remove(uri);
                        try {
                            resolver.releasePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException | IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        };
    }
}
