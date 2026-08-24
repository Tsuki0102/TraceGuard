package com.traceguard.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

/**
 * 密钥派生统一工具（SEC-12 整改）：
 * 新数据统一使用 PBKDF2WithHmacSHA256（默认 600,000 迭代 + 16 字节随机盐，派生 256 位 AES 密钥），
 * 替换各加密工具原先"单轮无盐 SHA-256(密码)"的弱派生；同时保留 {@link #deriveKeyLegacy}
 * 用于解密历史存量数据（向后兼容，版本标记在加密格式中体现）。
 */
public final class KeyDerivationUtil {

    /** OWASP 推荐 PBKDF2-SHA256 最低迭代次数 */
    public static final int PBKDF2_ITERATIONS = 600_000;

    /** 随机盐长度（字节） */
    public static final int SALT_LENGTH = 16;

    /** 派生密钥长度（位），256 位 = 32 字节 AES-256 */
    public static final int KEY_LENGTH_BITS = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private KeyDerivationUtil() {
    }

    /**
     * PBKDF2 高迭代密钥派生（新格式默认）
     *
     * @param password   口令/密钥字符串
     * @param salt       随机盐（调用方生成并随密文存储）
     * @param iterations 迭代次数（PBKDF2_ITERATIONS 或密文中记录的旧值）
     */
    public static byte[] deriveKey(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    }

    /** 生成随机盐 */
    public static byte[] randomSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** 旧版单轮 SHA-256 派生（仅用于解密历史存量密文，禁止用于新数据） */
    public static byte[] deriveKeyLegacy(String password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
