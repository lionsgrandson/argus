package com.example.babymonitor;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "baby_monitor_pairing_v2";

    static String encrypt(String clear) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(clear.getBytes("UTF-8"));
            ByteBuffer b = ByteBuffer.allocate(1 + iv.length + encrypted.length);
            b.put((byte) iv.length).put(iv).put(encrypted);
            return Base64.encodeToString(b.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect pairing code with Android Keystore", e);
        }
    }

    static String decrypt(String encoded) {
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer b = ByteBuffer.wrap(all);
            int ivLen = b.get() & 0xFF;
            if (ivLen < 12 || ivLen > 32 || b.remaining() <= ivLen + 16) throw new IllegalArgumentException("Bad protected secret");
            byte[] iv = new byte[ivLen]; b.get(iv);
            byte[] encrypted = new byte[b.remaining()]; b.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read protected pairing code", e);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private SecretStore() {}
}
