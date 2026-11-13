// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

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
        try {
            File root = new File(context.getCacheDir(), "shared").getCanonicalFile();
            if (!file.getCanonicalFile().getParentFile().equals(root))
                throw new IllegalArgumentException("File is outside shared cache");
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException(failure);
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".files")
                .appendPath(file.getName())
                .build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 1) throw new FileNotFoundException();
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
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String extension =
                dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
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
                                ? file.getName()
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
