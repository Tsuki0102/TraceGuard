package com.traceguard.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 短文本对称加解密工具（GAP-021：tg_llm_config.api_key 加密存储）
 * AES-256-GCM，密钥由字符串经 PBKDF2WithHmacSHA256 派生（SEC-12 整改，替换原单轮 SHA-256）。
 * 新格式密文：Base64([4字节迭代次数(BE)][16字节盐][12字节IV][GCM密文(含128位认证标签)])，
 * 以前缀 "enc:v1:" 标记；兼容解密历史存量密文（无前缀，Base64([IV][密文])，SHA-256 派生）。
 */
public final class LlmConfigCryptoUtil {

    /** 新格式版本前缀 */
    public static final String V1_PREFIX = "enc:v1:";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private LlmConfigCryptoUtil() {
    }

    /** 加密短文本，返回带版本前缀的 Base64 密文（新格式：PBKDF2 派生） */
    public static String encrypt(String plain, String key) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        byte[] salt = KeyDerivationUtil.randomSalt();
        int iterations = KeyDerivationUtil.PBKDF2_ITERATIONS;
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, iv, key, salt, iterations);
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        ByteBuffer bb = ByteBuffer.allocate(4 + salt.length + iv.length + encrypted.length);
        bb.putInt(iterations).put(salt).put(iv).put(encrypted);
        return V1_PREFIX + Base64.getEncoder().encodeToString(bb.array());
    }

    /** 解密密文：兼容新（enc:v1: 前缀）与历史（无前缀）两种格式 */
    public static String decrypt(String cipherText, String key) throws Exception {
        if (cipherText == null || cipherText.isEmpty()) {
            throw new IllegalArgumentException("密文为空");
        }
        byte[] all;
        if (cipherText.startsWith(V1_PREFIX)) {
            all = Base64.getDecoder().decode(cipherText.substring(V1_PREFIX.length()));
            if (all.length < 4 + KeyDerivationUtil.SALT_LENGTH + GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("密文格式错误");
            }
            ByteBuffer bb = ByteBuffer.wrap(all);
            int iterations = bb.getInt();
            byte[] salt = new byte[KeyDerivationUtil.SALT_LENGTH];
            bb.get(salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            bb.get(iv);
            byte[] encrypted = new byte[bb.remaining()];
            bb.get(encrypted);
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, iv, key, salt, iterations);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        // 历史格式：Base64([IV][密文])，SHA-256 派生
        all = Base64.getDecoder().decode(cipherText);
        if (all.length < GCM_IV_LENGTH + 16) {
            throw new IllegalArgumentException("密文格式错误");
        }
        byte[] iv = Arrays.copyOfRange(all, 0, GCM_IV_LENGTH);
        Cipher cipher = createCipherLegacy(Cipher.DECRYPT_MODE, iv, key);
        byte[] plain = cipher.doFinal(all, GCM_IV_LENGTH, all.length - GCM_IV_LENGTH);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 判断是否为加密文本（enc:v1: 前缀，或历史无 { 前缀的 Base64 密文；明文 JSON 以 { 开头） */
    public static boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.startsWith(V1_PREFIX) || !value.startsWith("{");
    }

    /** 初始化 GCM Cipher：PBKDF2 派生 AES-256 密钥 */
    private static Cipher createCipher(int mode, byte[] iv, String key, byte[] salt, int iterations) throws Exception {
        byte[] keyBytes = KeyDerivationUtil.deriveKey(key, salt, iterations);
        return initCipher(mode, iv, keyBytes);
    }

    /** 初始化 GCM Cipher：历史单轮 SHA-256 派生（仅用于解密存量密文） */
    private static Cipher createCipherLegacy(int mode, byte[] iv, String key) throws Exception {
        byte[] keyBytes = KeyDerivationUtil.deriveKeyLegacy(key);
        return initCipher(mode, iv, keyBytes);
    }

    private static Cipher initCipher(int mode, byte[] iv, byte[] keyBytes) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }
}
