package com.gathin.tvhome;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore AES-256-GCM seals credentials with a key that never leaves the keystore. The key
 * is app-scoped, so uninstalling the launcher removes it and the ciphertext becomes undecryptable.
 *
 * The stored format is {@code [1][iv length][iv][ciphertext]}; a version byte keeps a future format
 * change explicit instead of silently misreading old data.
 */
final class KeystoreVault implements CredentialStore.Vault {
    private static final String ALIAS = "cleanyourtv-credentials-v1";
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;

    private final SecureRandom random = new SecureRandom();

    @Override
    public byte[] seal(byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] sealed = cipher.doFinal(plaintext);
        byte[] result = new byte[2 + iv.length + sealed.length];
        result[0] = VERSION;
        result[1] = (byte) iv.length;
        System.arraycopy(iv, 0, result, 2, iv.length);
        System.arraycopy(sealed, 0, result, 2 + iv.length, sealed.length);
        return result;
    }

    @Override
    public byte[] open(byte[] sealed) throws Exception {
        if (sealed == null || sealed.length < 2 + IV_BYTES || sealed[0] != VERSION) {
            throw new IllegalArgumentException("unsupported credential format");
        }
        int length = sealed[1] & 255;
        if (length < 12 || length > 16 || sealed.length <= 2 + length) {
            throw new IllegalArgumentException("invalid credential format");
        }
        byte[] iv = new byte[length];
        System.arraycopy(sealed, 2, iv, 0, length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(sealed, 2 + length, sealed.length - 2 - length);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
