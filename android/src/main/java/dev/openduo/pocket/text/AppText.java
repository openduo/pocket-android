// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.text;

import android.content.Context;
import android.content.res.Configuration;

import dev.openduo.pocket.app.AppIdentity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Android preferences/resources adapter. Catalogs are immutable and can be scoped for previews. */
public final class AppText {
    private static TextCatalog cached;

    private AppText() {}

    public static TextCatalog get(Context context) {
        Locale system =
                context.getApplicationContext()
                        .getResources()
                        .getConfiguration()
                        .getLocales()
                        .get(0);
        return base(context).language(preference(context), system);
    }

    public static Context context(Context base) {
        String language = preference(base);
        boolean english =
                language.equals("system")
                        ? TextCatalog.english(
                                language,
                                base.getApplicationContext()
                                        .getResources()
                                        .getConfiguration()
                                        .getLocales()
                                        .get(0))
                        : language.equals("en");
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
        return base.createConfigurationContext(configuration);
    }

    private static String preference(Context context) {
        return context.getSharedPreferences(AppIdentity.SETTINGS, Context.MODE_PRIVATE)
                .getString("language", "system");
    }

    private static synchronized TextCatalog base(Context context) {
        if (cached != null) return cached;
        try (InputStream input = context.getAssets().open("i18n/en.json");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
            cached =
                    new TextCatalog(
                            "zh-Hans",
                            Locale.SIMPLIFIED_CHINESE,
                            new JSONObject(
                                    new String(output.toByteArray(), StandardCharsets.UTF_8)));
            return cached;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load application translations", failure);
        }
    }
}
