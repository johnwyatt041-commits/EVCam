package com.kooo.evcam.update;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 AndroidKeyStore 硬件/TEE AES-256-GCM 的 GitHub PAT 加解密实现（minSdk 28 原生支持，零额外依赖）。
 * 格式：enc:v1:&lt;Base64(iv)&gt;:&lt;Base64(ciphertext)&gt;。
 * 若 Keystore 损坏或不可用直接抛出异常，严禁悄悄降级为明文落盘。
 */
public final class AndroidKeyStorePatCipher implements PatCipher {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "evcam_github_pat_aes256_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String FORMAT_PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;

    @Override
    public String encrypt(String plaintext) throws GeneralSecurityException {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new GeneralSecurityException("Empty PAT cannot be encrypted");
        }
        SecretKey key = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return FORMAT_PREFIX
                + Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    @Override
    public String decrypt(String encodedCiphertext) throws GeneralSecurityException {
        if (encodedCiphertext == null || !encodedCiphertext.startsWith(FORMAT_PREFIX)) {
            throw new GeneralSecurityException("Unsupported encrypted PAT payload");
        }
        String payload = encodedCiphertext.substring(FORMAT_PREFIX.length());
        int split = payload.indexOf(':');
        if (split <= 0 || split >= payload.length() - 1) {
            throw new GeneralSecurityException("Malformed encrypted PAT payload");
        }
        byte[] iv;
        byte[] cipherBytes;
        try {
            iv = Base64.decode(payload.substring(0, split), Base64.NO_WRAP);
            cipherBytes = Base64.decode(payload.substring(split + 1), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid Base64 in encrypted PAT", e);
        }

        SecretKey key = getExistingSecretKey();
        if (key == null) {
            throw new GeneralSecurityException("KeyStore key missing for encrypted PAT");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateSecretKey() throws GeneralSecurityException {
        SecretKey existing = getExistingSecretKey();
        if (existing != null) {
            return existing;
        }
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            generator.init(spec);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Failed to create KeyStore key", e);
        }
    }

    private SecretKey getExistingSecretKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return null;
            }
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Failed to access AndroidKeyStore", e);
        }
    }
}
