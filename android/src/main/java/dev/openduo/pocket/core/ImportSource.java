// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.DocumentsContract;

import java.io.Closeable;
import java.io.IOException;

/** Retains a provider until its source closes, including Android 8 document cancellation. */
final class ImportSource implements Closeable {
    final AssetFileDescriptor asset;
    private final ContentProviderClient provider;

    private ImportSource(AssetFileDescriptor asset, ContentProviderClient provider)
            throws IOException {
        if (asset == null) throw new IOException("Missing content stream");
        this.asset = asset;
        this.provider = provider;
    }

    static ImportSource open(Context context, Uri uri, ImportRead read) throws IOException {
        read.check();
        if (!DocumentsContract.isDocumentUri(context, uri))
            return new ImportSource(
                    context.getContentResolver().openAssetFileDescriptor(uri, "r", read.signal()),
                    null);

        // Android 8's wildcard typed-asset path drops the signal before openDocument. Document
        // URIs represent complete files, so call openFile directly and retain its provider lease.
        ContentProviderClient provider =
                context.getContentResolver().acquireUnstableContentProviderClient(uri);
        if (provider == null) throw new IOException("Missing document provider");
        try {
            ParcelFileDescriptor file = provider.openFile(uri, "r", read.signal());
            if (file == null) throw new IOException("Missing document stream");
            return new ImportSource(
                    new AssetFileDescriptor(file, 0, AssetFileDescriptor.UNKNOWN_LENGTH), provider);
        } catch (RemoteException failure) {
            provider.close();
            throw new IOException("Cannot open document provider", failure);
        } catch (IOException | RuntimeException failure) {
            provider.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            asset.close();
        } finally {
            if (provider != null) provider.close();
        }
    }
}
