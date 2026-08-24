package com.traceguard.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * GAP-017：备份口令派生密钥工具（PBKDF2）。
 *
 * 用于从用户设置的备份口令派生加密密钥，替代固定配置密钥。
 * 派生参数（salt、iterations）存储在备份文件头中，恢复时读取并验证口令。
 */
public class PassphraseUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(PassphraseUtil.class);

    /** PBKDF2 算法 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 默认迭代次数（OWASP 推荐 ≥ 600,000；SEC-12 整改由 100,000 提升） */
    private static final int DEFAULT_ITERATIONS = KeyDerivationUtil.PBKDF2_ITERATIONS;

    /** 派生密钥长度（256 位 = 32 字节） */
    private static final int KEY_LENGTH = 256;

    /** Salt 长度（字节） */
    private static final int SALT_LENGTH = 16;

    /** 分隔符（用于拼接 salt 和 iterations） */
    private static final char SEPARATOR = ':';

    /**
     * 从口令派生密钥，返回 "salt:iterations:derivedKey" 格式字符串
     *
     * @param passphrase 用户口令
     * @return 包含 salt、iterations、派生密钥的字符串
     */
    public static String deriveKey(String passphrase) {
        if (passphrase == null || passphrase.isEmpty()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);
            int iterations = DEFAULT_ITERATIONS;

            byte[] key = deriveKeyBytes(passphrase, salt, iterations);

            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String keyBase64 = Base64.getEncoder().encodeToString(key);

            return saltBase64 + SEPARATOR + iterations + SEPARATOR + keyBase64;
        } catch (Exception e) {
            LOGGER.error("口令派生密钥失败", e);
            throw new RuntimeException("口令派生密钥失败", e);
        }
    }

    /**
     * 验证口令是否匹配给定的派生密钥记录
     *
     * @param passphrase 用户输入的口令
     * @param derivedKeyRecord  deriveKey 返回的记录（salt:iterations:derivedKey）
     * @return true=口令匹配
     */
    public static boolean verify(String passphrase, String derivedKeyRecord) {
        if (passphrase == null || passphrase.isEmpty() || derivedKeyRecord == null || derivedKeyRecord.isEmpty()) {
            return false;
        }
        try {
            String[] parts = derivedKeyRecord.split(String.valueOf(SEPARATOR));
            if (parts.length != 3) {
                LOGGER.warn("派生密钥记录格式错误");
                return false;
            }

            byte[] salt = Base64.getDecoder().decode(parts[0]);
            int iterations = Integer.parseInt(parts[1]);
            byte[] expectedKey = Base64.getDecoder().decode(parts[2]);

            byte[] actualKey = deriveKeyBytes(passphrase, salt, iterations);

            return java.util.Arrays.equals(expectedKey, actualKey);
        } catch (Exception e) {
            LOGGER.error("口令验证失败", e);
            return false;
        }
    }

    /**
     * 从派生密钥记录中提取密钥（Base64 编码）
     *
     * @param derivedKeyRecord deriveKey 返回的记录
     * @return 密钥的 Base64 字符串
     */
    public static String extractKey(String derivedKeyRecord) {
        if (derivedKeyRecord == null || derivedKeyRecord.isEmpty()) {
            return null;
        }
        String[] parts = derivedKeyRecord.split(String.valueOf(SEPARATOR));
        if (parts.length != 3) {
            return null;
        }
        return parts[2];
    }

    /**
     * 核心 PBKDF2 派生逻辑
     */
    private static byte[] deriveKeyBytes(String passphrase, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        return factory.generateSecret(spec).getEncoded();
    }
}
