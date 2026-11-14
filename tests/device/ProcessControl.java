// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Only synthetic fixture state crosses this test-only control provider, never app data. */
public final class ProcessControl extends ContentProvider {
    static final Uri URI = Uri.parse("content://dev.openduo.pocket.qa.restart-control");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        int caller = android.os.Binder.getCallingUid();
        String[] packages = getContext().getPackageManager().getPackagesForUid(caller);
        if (caller != 2000
                && caller != android.os.Process.myUid()
                && (packages == null
                        || !java.util.Arrays.asList(packages).contains("dev.openduo.pocket")))
            throw new SecurityException("Not a process-death test caller");
        android.content.SharedPreferences prefs = ProcessSource.state(getContext());
        if (method.equals("reset")) {
            java.util.UUID.fromString(argument);
            if (ProcessSource.active.get() != 0)
                throw new IllegalStateException("Fixture is still open");
            if (!prefs.edit().clear().putString("fixture", argument).commit())
                throw new IllegalStateException("Cannot reset process fixture");
        } else if (!method.equals("state"))
            throw new IllegalArgumentException("Unknown fixture operation");
        Bundle result = new Bundle();
        result.putString("fixture", prefs.getString("fixture", ""));
        result.putInt("opens", prefs.getInt("opens", 0));
        result.putInt("active", ProcessSource.active.get());
        result.putInt("pid", android.os.Process.myPid());
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
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
