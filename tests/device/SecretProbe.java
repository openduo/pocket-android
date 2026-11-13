// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import android.content.*;

/** Uses a separate preference file and the actual AndroidKeyStore implementation. */
public final class SecretProbe {
    public static int run(Context context) {
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return super.getSharedPreferences(name + ".qa", mode);
                    }
                };
        SecretStore store = new SecretStore(isolated);
        try {
            store.write("qa-only-token", "https://a.test|qa");
            if (!store.read("https://a.test|qa").equals("qa-only-token"))
                throw new AssertionError("keystore encryption roundtrip");
            if (!store.read("https://b.test|qa").isEmpty())
                throw new AssertionError("token is bound to original origin");
            store.write("", "");
            if (!store.read("https://a.test|qa").isEmpty())
                throw new AssertionError("secret deletion");
            return 3;
        } finally {
            isolated.getSharedPreferences("pocket-secrets", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        }
    }
}
