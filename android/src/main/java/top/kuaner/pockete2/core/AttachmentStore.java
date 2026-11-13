// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** File I/O only. The caller chooses its executor and admits results into the current room. */
public final class AttachmentStore {
    public static final long LOCAL_IMPORT_MAX_BYTES = 64L * 1024 * 1024;
    private final Context context;

    public AttachmentStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public Attachment importFile(Uri uri, boolean trying, long remoteLimit) throws IOException {
        String name = "附件", mime = context.getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        try (android.database.Cursor c =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        }
        File dir =
                new File(
                        trying ? context.getCacheDir() : context.getFilesDir(),
                        trying ? "try-attachments" : "attachments/drafts");
        ensure(dir);
        File file = new File(dir, UUID.randomUUID().toString());
        long size = 0;
        long limit =
                remoteLimit > 0
                        ? Math.min(LOCAL_IMPORT_MAX_BYTES, remoteLimit)
                        : LOCAL_IMPORT_MAX_BYTES;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Missing content stream");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > limit) throw new IOException("文件超过大小上限");
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        } catch (Exception failure) {
            file.delete();
            throw failure;
        }
        return new Attachment(name, mime, file.getPath(), "", "", size, Attachment.UPLOADING);
    }

    public File localPreview(Attachment attachment) throws IOException {
        return shareable(new File(attachment.localPath), attachment, false);
    }

    public File shareable(File local, Attachment attachment, boolean trying) throws IOException {
        if (!local.isFile()) throw new FileNotFoundException();
        File file =
                File.createTempFile(trying ? "try-" : "shared-", safeSuffix(attachment), shared());
        Files.copy(local.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    public File cached(Attachment attachment) {
        return cached(attachment, "");
    }

    public File cached(Attachment attachment, String temporaryScope) {
        if (!attachment.sha256.matches("[0-9a-fA-F]{64}")) return null;
        File file =
                new File(
                        downloadDirectory(temporaryScope),
                        attachment.sha256.toLowerCase(Locale.ROOT)
                                + "-"
                                + safeName(attachment.name));
        return file.isFile() ? file : null;
    }

    public File cache(Attachment attachment, byte[] bytes) throws IOException {
        return cache(attachment, bytes, "");
    }

    public File cache(Attachment attachment, byte[] bytes, String temporaryScope)
            throws IOException {
        String hash =
                attachment.sha256.matches("[0-9a-fA-F]{64}")
                        ? attachment.sha256.toLowerCase(Locale.ROOT)
                        : UUID.randomUUID().toString();
        if (attachment.sha256.matches("[0-9a-fA-F]{64}")) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder value = new StringBuilder();
                for (byte d : digest) value.append(String.format(Locale.ROOT, "%02x", d & 255));
                if (!hash.equals(value.toString()))
                    throw new IOException("Attachment digest mismatch");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
        File dir = downloadDirectory(temporaryScope);
        ensure(dir);
        File file = new File(dir, hash + "-" + safeName(attachment.name)),
                partial = File.createTempFile("attachment-", ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!partial.renameTo(file)) throw new IOException("Cannot admit attachment");
        } finally {
            partial.delete();
        }
        return file;
    }

    private File shared() throws IOException {
        File dir = new File(context.getCacheDir(), "shared");
        ensure(dir);
        return dir;
    }

    private File downloadDirectory(String temporaryScope) {
        if (temporaryScope.isEmpty()) return new File(context.getCacheDir(), "shared");
        if (!temporaryScope.matches("[a-zA-Z0-9-]{1,64}"))
            throw new IllegalArgumentException("Invalid temporary attachment scope");
        return new File(context.getCacheDir(), "try-downloads/" + temporaryScope);
    }

    private static void ensure(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create attachment directory");
    }

    private static String safeSuffix(Attachment a) {
        String extension =
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mime);
        return extension == null ? ".bin" : "." + extension;
    }

    private static String safeName(String name) {
        String value = new File(name).getName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "attachment";
        return value.length() > 100 ? value.substring(value.length() - 100) : value;
    }
}
