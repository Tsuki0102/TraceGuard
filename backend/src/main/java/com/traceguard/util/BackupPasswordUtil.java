package com.traceguard.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * GAP-017：用户备份口令派生密钥工具
 * 使用 PBKDF2WithHmacSHA256 将用户口令派生为加密密钥，
 * 存储 salt 和 iterations 用于后续密钥验证和重新派生。
 *
 * 文件格式：[salt (16 bytes)][iterations (4 bytes)][encrypted data]
 */
public final class BackupPasswordUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupPasswordUtil.class);

    /** PBKDF2 推荐最小迭代次数（OWASP 2023 推荐） */
    public static final int DEFAULT_ITERATIONS = 310000;

    /** 推荐盐长度 16 字节 */
    private static final int SALT_LENGTH = 16;

    /** GCM IV 长度 12 字节，认证标签 128 位 */
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    /** 密钥派生目标长度 256 位（AES-256） */
    private static final int KEY_LENGTH = 256;

    private BackupPasswordUtil() {
    }

    /**
     * 从用户口令派生加密密钥
     *
     * @param password   用户口令
     * @param salt       盐（Base64 编码）
     * @param iterations 迭代次数
     * @return 派生后的 AES-256 密钥
     */
    public static SecretKey deriveKey(String password, String salt, int iterations) throws GeneralSecurityException {
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, iterations, KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 生成随机盐（Base64 编码）
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 加密数据（使用 PBKDF2 派生的密钥）
     *
     * @param data        明文数据
     * @param password    用户口令
     * @param salt        盐（Base64 编码）
     * @param iterations  迭代次数
     * @return 加密后的数据（格式：[salt (16)][iterations (4)][IV (12)][密文（含 tag）]）
     */
    public static byte[] encrypt(byte[] data, String password, String salt, int iterations) throws GeneralSecurityException {
        SecretKey key = deriveKey(password, salt, iterations);

        // 生成随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // 创建 GCM 模式的 Cipher
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        // 加密数据
        byte[] encrypted = cipher.doFinal(data);

        // 组装输出：[salt (16)][iterations (4)][IV (12)][密文]
        byte[] output = new byte[SALT_LENGTH + 4 + GCM_IV_LENGTH + encrypted.length];

        // 写入 salt
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        System.arraycopy(saltBytes, 0, output, 0, SALT_LENGTH);

        // 写入 iterations（大端序）
        output[SALT_LENGTH] = (byte) (iterations >> 24);
        output[SALT_LENGTH + 1] = (byte) (iterations >> 16);
        output[SALT_LENGTH + 2] = (byte) (iterations >> 8);
        output[SALT_LENGTH + 3] = (byte) iterations;

        // 写入 IV
        System.arraycopy(iv, 0, output, SALT_LENGTH + 4, GCM_IV_LENGTH);

        // 写入密文
        System.arraycopy(encrypted, 0, output, SALT_LENGTH + 4 + GCM_IV_LENGTH, encrypted.length);

        return output;
    }

    /**
     * 解密数据（使用 PBKDF2 派生的密钥）
     *
     * @param encryptedData 加密数据
     * @param password      用户口令
     * @return 解密后的明文
     */
    public static byte[] decrypt(byte[] encryptedData, String password) throws GeneralSecurityException {
        if (encryptedData.length < SALT_LENGTH + 4 + GCM_IV_LENGTH) {
            throw new GeneralSecurityException("加密数据格式错误：长度不足");
        }

        // 提取 salt
        byte[] saltBytes = new byte[SALT_LENGTH];
        System.arraycopy(encryptedData, 0, saltBytes, 0, SALT_LENGTH);
        String salt = Base64.getEncoder().encodeToString(saltBytes);

        // 提取 iterations
        int iterations = ((encryptedData[SALT_LENGTH] & 0xFF) << 24) |
                ((encryptedData[SALT_LENGTH + 1] & 0xFF) << 16) |
                ((encryptedData[SALT_LENGTH + 2] & 0xFF) << 8) |
                (encryptedData[SALT_LENGTH + 3] & 0xFF);

        // 提取 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, SALT_LENGTH + 4, iv, 0, GCM_IV_LENGTH);

        // 提取密文
        int cipherLen = encryptedData.length - SALT_LENGTH - 4 - GCM_IV_LENGTH;
        byte[] cipherText = new byte[cipherLen];
        System.arraycopy(encryptedData, SALT_LENGTH + 4 + GCM_IV_LENGTH, cipherText, 0, cipherLen);

        // 派生密钥
        SecretKey key = deriveKey(password, salt, iterations);

        // 解密
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        return cipher.doFinal(cipherText);
    }

    /**
     * 验证口令是否正确（用于用户设置备份口令时的验证）
     *
     * @param testData     测试数据
     * @param encrypted    加密后的测试数据
     * @param password     用户口令
     * @return true=口令正确，false=口令错误
     */
    public static boolean verifyPassword(byte[] testData, byte[] encrypted, String password) {
        try {
            byte[] decrypted = decrypt(encrypted, password);
            return java.util.Arrays.equals(testData, decrypted);
        } catch (GeneralSecurityException e) {
            LOGGER.debug("口令验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 派生密钥字节（用于与其他加密工具配合，如 BackupCryptoUtil）
     *
     * @param password   用户口令
     * @param salt       盐（Base64 编码）
     * @param iterations 迭代次数
     * @return 256 位密钥字节
     */
    public static byte[] deriveKeyBytes(String password, String salt, int iterations) throws GeneralSecurityException {
        SecretKey key = deriveKey(password, salt, iterations);
        return key.getEncoded();
    }

    /**
     * 将密钥字节转换为 Base64 字符串
     */
    public static String keyToString(byte[] keyBytes) {
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    /**
     * 从 Base64 字符串恢复密钥字节
     */
    public static byte[] stringToKey(String keyBase64) {
        return Base64.getDecoder().decode(keyBase64);
    }
}