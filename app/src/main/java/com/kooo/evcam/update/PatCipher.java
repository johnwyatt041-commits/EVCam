package com.kooo.evcam.update;

import java.security.GeneralSecurityException;

/**
 * GitHub PAT 本地加密存储抽象（生产端由 AndroidKeyStore AES-GCM 实现，JVM 单测可注入验证异常与防降级策略）。
 */
public interface PatCipher {
    String encrypt(String plaintext) throws GeneralSecurityException;

    String decrypt(String encodedCiphertext) throws GeneralSecurityException;
}
