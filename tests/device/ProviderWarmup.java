// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

/** Clears the fresh test APK's stopped state so DocumentsUI can discover its disposable root. */
public final class ProviderWarmup extends android.app.Activity {
    @Override
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        getContentResolver()
                .notifyChange(
                        android.provider.DocumentsContract.buildRootsUri(Documents.AUTHORITY),
                        null);
        for (String id : new String[] {"blocked", "status", "slow-open", "rejected"})
            grantUriPermission(
                    "top.kuaner.pockete2",
                    android.provider.DocumentsContract.buildDocumentUri(Documents.AUTHORITY, id),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        finish();
    }
}
