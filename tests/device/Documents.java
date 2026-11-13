// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Disposable external-UID source used through the real system document picker. */
public final class Documents extends DocumentsProvider {
    public static final String AUTHORITY = "top.kuaner.pockete2.qa.documents";
    public static final String TITLE = "Pocket E2 QA source", NAME = "Pocket E2 QA document.txt";
    public static final String CONTENT = "Native picker bytes, not a simulated result.\n";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(DocumentsContract.Root.COLUMN_ROOT_ID, "qa-root");
        values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root");
        values.put(DocumentsContract.Root.COLUMN_TITLE, TITLE);
        values.put(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
        values.put(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda);
        values.put(DocumentsContract.Root.COLUMN_MIME_TYPES, "text/plain");
        return row(projection, values);
    }

    @Override
    public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        values.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
        if (id.equals("root")) {
            values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, TITLE);
            values.put(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.MIME_TYPE_DIR);
        } else if (id.equals("document")) {
            values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, NAME);
            values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, "text/plain");
            values.put(
                    DocumentsContract.Document.COLUMN_SIZE,
                    CONTENT.getBytes(StandardCharsets.UTF_8).length);
        } else throw new FileNotFoundException();
        return row(projection, values);
    }

    @Override
    public Cursor queryChildDocuments(String parent, String[] projection, String order)
            throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException();
        return queryDocument("document", projection);
    }

    @Override
    public boolean isChildDocument(String parent, String child) {
        return parent.equals("root") && child.equals("document");
    }

    @Override
    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancel)
            throws FileNotFoundException {
        if (!id.equals("document") || !mode.equals("r")) throw new FileNotFoundException();
        if (cancel != null) cancel.throwIfCanceled();
        return openPipeHelper(
                DocumentsContract.buildDocumentUri(AUTHORITY, id),
                "text/plain",
                null,
                CONTENT.getBytes(StandardCharsets.UTF_8),
                (output, uri, mime, options, bytes) -> {
                    try (OutputStream out =
                            new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                        out.write(bytes);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static Cursor row(String[] projection, Map<String, Object> values) {
        String[] names = projection == null ? values.keySet().toArray(new String[0]) : projection;
        MatrixCursor cursor = new MatrixCursor(names);
        Object[] fields = new Object[names.length];
        for (int i = 0; i < fields.length; i++) fields[i] = values.get(names[i]);
        cursor.addRow(fields);
        return cursor;
    }
}
