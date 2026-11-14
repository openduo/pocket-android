// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Only files inside cache/shared can be granted to camera or preview applications. */
public final class PocketFiles extends ContentProvider {
    public static Uri uri(Context context, File file) {
        return uri(context, file, file.getName(), null);
    }

    public static Uri uri(Context context, File file, String displayName, String mime) {
        try {
            File root = new File(context.getCacheDir(), "shared").getCanonicalFile();
            if (!file.getCanonicalFile().getParentFile().equals(root))
                throw new IllegalArgumentException("File is outside shared cache");
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException(failure);
        }
        Uri.Builder uri =
                new Uri.Builder()
                        .scheme("content")
                        .authority(context.getPackageName() + ".files")
                        .appendPath(file.getName())
                        .appendQueryParameter("name", displayName(displayName, file.getName()));
        if (validMime(mime)) uri.appendQueryParameter("mime", mime);
        return uri.build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".files").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        try {
            File root = new File(getContext().getCacheDir(), "shared").getCanonicalFile();
            File file = new File(root, uri.getLastPathSegment()).getCanonicalFile();
            if (!root.equals(file.getParentFile())) throw new FileNotFoundException();
            return file;
        } catch (java.io.IOException failure) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String declared = uri.getQueryParameter("mime");
        if (validMime(declared)) return declared;
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String extension =
                dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }

    private static boolean validMime(String mime) {
        return mime != null && mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+");
    }

    private static String displayName(String declared, String fallback) {
        String name = declared == null ? fallback : declared.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = fallback;
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] names =
                    projection == null
                            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                            : projection;
            MatrixCursor result = new MatrixCursor(names);
            Object[] row = new Object[names.length];
            for (int i = 0; i < names.length; i++)
                row[i] =
                        names[i].equals(OpenableColumns.DISPLAY_NAME)
                                ? displayName(uri.getQueryParameter("name"), file.getName())
                                : names[i].equals(OpenableColumns.SIZE) ? file.length() : null;
            result.addRow(row);
            return result;
        } catch (FileNotFoundException failure) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
