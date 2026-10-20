package com.report.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置敏感字段加解密（AES-GCM）。
 * 密文格式：enc:&lt;base64(iv + ciphertext)&gt;
 * 密钥来源：环境变量 APP_CRYPTO_KEY（或系统属性），未配置时不加密、原样返回。
 */
public final class SecretCrypto {

    public static final String PREFIX = "enc:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            this.key = null;
        } else {
            this.key = new SecretKeySpec(sha256(rawKey), "AES");
        }
    }

    public static SecretCrypto fromEnv() {
        String k = System.getenv("APP_CRYPTO_KEY");
        if (k == null || k.isBlank()) {
            k = System.getProperty("APP_CRYPTO_KEY");
        }
        return new SecretCrypto(k);
    }

    public boolean isEnabled() {
        return key != null;
    }

    /**
     * 加密明文。已是 enc: 前缀或 ${ENV} 引用则不处理。
     */
    public String encryptIfNeeded(String plain) {
        if (plain == null || plain.isEmpty() || key == null) {
            return plain;
        }
        if (plain.startsWith(PREFIX) || plain.contains("${")) {
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密；非 enc: 前缀则原样返回。
     */
    public String decryptIfNeeded(String value) {
        if (value == null || value.isEmpty() || !value.startsWith(PREFIX)) {
            return value;
        }
        if (key == null) {
            throw new IllegalStateException("配置了 enc: 密文但未设置 APP_CRYPTO_KEY，无法解密");
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
