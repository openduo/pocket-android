// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import dev.openduo.pocket.app.AppIdentity;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM key never leaves AndroidKeyStore. Connection secrets are excluded from backup. */
final class SecretStore {
    private static final String ALIAS = AppIdentity.TOKEN_KEY;
    private final SharedPreferences prefs;

    SecretStore(Context context) {
        prefs = context.getSharedPreferences("pocket-secrets", Context.MODE_PRIVATE);
    }

    synchronized void write(String value, String binding) {
        try {
            if (value == null || value.isEmpty()) {
                if (!prefs.edit().clear().commit()) throw new IllegalStateException("无法保存配对状态");
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            if (!prefs.edit()
                    .putString("binding", binding)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("data", Base64.encodeToString(data, Base64.NO_WRAP))
                    .commit()) {
                throw new IllegalStateException("无法保存配对状态");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("无法保存设备凭据，请检查存储空间", failure);
        }
    }

    synchronized String read(String binding) {
        String iv = prefs.getString("iv", ""), data = prefs.getString("data", "");
        if (binding.isEmpty()
                || !binding.equals(prefs.getString("binding", ""))
                || iv.isEmpty()
                || data.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return "";
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }
}
