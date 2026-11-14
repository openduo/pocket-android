// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

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

    public Attachment importFile(Uri uri, File file, long remoteLimit) throws IOException {
        return importFile(uri, file, remoteLimit, ImportRoute.Kind.MEDIA);
    }

    public Attachment importFile(Uri uri, File file, long remoteLimit, ImportRoute.Kind kind)
            throws IOException {
        return importFile(uri, file, remoteLimit, kind, new ImportRead());
    }

    public Attachment importFile(
            Uri uri, File file, long remoteLimit, ImportRoute.Kind kind, ImportRead read)
            throws IOException {
        read.check();
        if (uri == null || !"content".equals(uri.getScheme()))
            throw new IOException("Import requires a content URI");
        String name = "附件", mime = context.getContentResolver().getType(uri);
        try (android.database.Cursor c =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null,
                                read.signal())) {
            if (c != null && c.moveToFirst()) {
                int column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && c.getString(column) != null && !c.getString(column).isEmpty())
                    name = c.getString(column);
            }
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Some readable providers do not support a display-name query.
        }
        read.check();
        if (mime == null || mime.isEmpty()) {
            int dot = name.lastIndexOf('.');
            mime =
                    dot < 0
                            ? null
                            : android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(
                                            name.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
        if (mime == null) mime = "application/octet-stream";
        read.copied(name, 0);
        Attachment metadata =
                new Attachment(name, mime, file.getPath(), "", "", 0, Attachment.UPLOADING);
        ensure(file.getParentFile());
        long size = 0;
        long limit =
                remoteLimit > 0
                        ? Math.min(LOCAL_IMPORT_MAX_BYTES, remoteLimit)
                        : LOCAL_IMPORT_MAX_BYTES;
        long readLimit =
                dev.openduo.pocket.image.ImageFiles.converts(metadata, kind)
                        ? LOCAL_IMPORT_MAX_BYTES
                        : limit;
        try (ImportSource source = ImportSource.open(context, uri, read)) {
            read.check();
            try (InputStream in = source.asset.createInputStream();
                    FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                while (true) {
                    read.readable(source.asset.getFileDescriptor());
                    int n = in.read(buffer);
                    if (n == -1) break;
                    size += n;
                    if (size > readLimit) throw new IOException("文件超过大小上限");
                    out.write(buffer, 0, n);
                    read.copied(name, size);
                }
                out.getFD().sync();
            }
        } catch (Exception failure) {
            file.delete();
            throw failure;
        }
        try {
            Attachment result =
                    dev.openduo.pocket.image.ImageFiles.normalize(
                            new Attachment(
                                    name, mime, file.getPath(), "", "", size, Attachment.UPLOADING),
                            kind,
                            limit,
                            read);
            read.check();
            if (result.size > limit) throw new IOException("文件超过大小上限");
            return result;
        } catch (Exception failure) {
            file.delete();
            throw failure;
        }
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
