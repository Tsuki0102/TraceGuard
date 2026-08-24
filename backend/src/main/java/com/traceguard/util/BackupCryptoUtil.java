package com.traceguard.util;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 备份文件加密工具：GZIP压缩 + AES-256-GCM加密（需求6.3 备份数据安全）。
 * 新格式文件头：[5字节魔数 TGBK1][4字节迭代次数(BE)][16字节盐][12字节IV][GCM密文(含128位认证标签)]，
 * 密钥由字符串经 PBKDF2WithHmacSHA256 派生（SEC-12 整改，替换原单轮 SHA-256）。
 * 兼容解密历史存量格式：[12字节IV][GCM密文]（SHA-256 派生）。
 */
public final class BackupCryptoUtil {

    /** 新格式魔数（历史格式无魔数，直接以 12 字节 IV 开头） */
    private static final byte[] MAGIC = "TGBK1".getBytes(StandardCharsets.UTF_8);

    /** GCM推荐IV长度12字节，认证标签128位 */
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private BackupCryptoUtil() {
    }

    /** 压缩并加密文件：明文 -> GZIP -> AES-256-GCM，写入[魔数][迭代次数][盐][IV][密文]（新格式） */
    public static void encrypt(File plain, File target, String key) throws IOException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        byte[] salt = KeyDerivationUtil.randomSalt();
        int iterations = KeyDerivationUtil.PBKDF2_ITERATIONS;
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, iv, key, salt, iterations);
        try (FileInputStream in = new FileInputStream(plain);
             FileOutputStream out = new FileOutputStream(target);
             CipherOutputStream cos = new CipherOutputStream(out, cipher);
             GZIPOutputStream gz = new GZIPOutputStream(cos)) {
            out.write(MAGIC);
            out.write(ByteBuffer.allocate(4).putInt(iterations).array());
            out.write(salt);
            out.write(iv);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                gz.write(buf, 0, n);
            }
        }
    }

    /** 解密并解压文件：兼容新（PBKDF2）与历史（SHA-256）两种格式，还原明文 */
    public static void decrypt(File encrypted, File target, String key) throws IOException {
        try (FileInputStream in = new FileInputStream(encrypted)) {
            Cipher cipher = readHeaderAndCreateCipher(in, key);
            try (CipherInputStream cis = new CipherInputStream(in, cipher);
                 GZIPInputStream gz = new GZIPInputStream(cis);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gz.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /**
     * 解析文件头：
     * - 新格式：首 5 字节为魔数 TGBK1，随后 [迭代次数4B][盐16B][IV12B]，PBKDF2 派生；
     * - 历史格式：文件头即 12 字节 IV（无魔数），SHA-256 派生。
     */
    private static Cipher readHeaderAndCreateCipher(FileInputStream in, String key) throws IOException {
        try {
            byte[] head = new byte[MAGIC.length];
            readFully(in, head);
            if (Arrays.equals(head, MAGIC)) {
                byte[] meta = new byte[4 + KeyDerivationUtil.SALT_LENGTH];
                readFully(in, meta);
                ByteBuffer bb = ByteBuffer.wrap(meta);
                int iterations = bb.getInt();
                byte[] salt = new byte[KeyDerivationUtil.SALT_LENGTH];
                bb.get(salt);
                byte[] iv = new byte[GCM_IV_LENGTH];
                readFully(in, iv);
                byte[] keyBytes = KeyDerivationUtil.deriveKey(key, salt, iterations);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                return cipher;
            }
            // 历史格式：已读 5 字节为 IV 前缀，补足 12 字节 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(head, 0, iv, 0, MAGIC.length);
            byte[] rest = new byte[GCM_IV_LENGTH - MAGIC.length];
            readFully(in, rest);
            System.arraycopy(rest, 0, iv, MAGIC.length, rest.length);
            byte[] keyBytes = KeyDerivationUtil.deriveKeyLegacy(key);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("备份加密初始化失败: " + e.getMessage(), e);
        }
    }

    private static void readFully(FileInputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("备份文件损坏：缺少加密头");
            }
            read += n;
        }
    }

    /** 初始化 GCM Cipher：PBKDF2 派生 AES-256 密钥 */
    private static Cipher createCipher(int mode, byte[] iv, String key, byte[] salt, int iterations) throws IOException {
        try {
            byte[] keyBytes = KeyDerivationUtil.deriveKey(key, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("备份加密初始化失败: " + e.getMessage(), e);
        }
    }
}
