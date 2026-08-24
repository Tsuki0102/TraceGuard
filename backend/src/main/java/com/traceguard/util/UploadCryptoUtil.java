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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * 上传文件加密工具（需求5.2.1 敏感数据加密存储）：AES-256-GCM 加密，
 * 新格式文件头：[6字节魔数 TGENC1][1字节格式标记 0x01][4字节迭代次数(BE)][16字节盐][12字节IV][GCM密文(含128位认证标签)]，
 * 密钥由字符串经 PBKDF2WithHmacSHA256 派生（SEC-12 整改，替换原单轮 SHA-256）。
 * 兼容解密历史存量格式：[6字节魔数 TGENC1][12字节IV][GCM密文]（SHA-256 派生）。
 * 与备份加密（BackupCryptoUtil）相互独立：本工具不压缩（上传的docx/pdf/zip本身已是压缩格式），
 * 并带魔数头以便读取时区分加密文件与历史存量明文文件。
 */
public final class UploadCryptoUtil {

    /** 加密文件魔数，用于区分加密文件与历史明文文件 */
    static final byte[] MAGIC = "TGENC1".getBytes(StandardCharsets.UTF_8);

    /** 新格式标记字节（0x01 = PBKDF2 派生格式；魔数后无此标记即为历史 SHA-256 格式） */
    private static final byte FORMAT_PBKDF2 = 0x01;

    /** GCM推荐IV长度12字节，认证标签128位 */
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private UploadCryptoUtil() {
    }

    /** 流式加密落盘：从输入流逐块读出并加密写入目标文件，明文不落盘（新格式：PBKDF2 派生） */
    public static void encryptStream(InputStream in, File target, String key) throws IOException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        byte[] salt = KeyDerivationUtil.randomSalt();
        int iterations = KeyDerivationUtil.PBKDF2_ITERATIONS;
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, iv, key, salt, iterations);
        try (FileOutputStream out = new FileOutputStream(target);
             CipherOutputStream cos = new CipherOutputStream(out, cipher)) {
            out.write(MAGIC);
            out.write(FORMAT_PBKDF2);
            out.write(ByteBuffer.allocate(4).putInt(iterations).array());
            out.write(salt);
            out.write(iv);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                cos.write(buf, 0, n);
            }
        }
    }

    /** 解密文件：兼容新（PBKDF2）与历史（SHA-256）两种格式，还原明文到目标文件 */
    public static void decryptFile(File encrypted, File target, String key) throws IOException {
        try (FileInputStream in = new FileInputStream(encrypted)) {
            byte[] magic = new byte[MAGIC.length];
            readFully(in, magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("非加密格式文件，无法解密");
            }
            Cipher cipher = readHeaderAndCreateCipher(in, key);
            try (CipherInputStream cis = new CipherInputStream(in, cipher);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = cis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /** 判断文件是否为本工具加密格式（读头部魔数） */
    public static boolean isEncryptedFile(File file) {
        if (file == null || !file.exists() || file.length() < MAGIC.length + GCM_IV_LENGTH) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[MAGIC.length];
            readFully(in, magic);
            return java.util.Arrays.equals(magic, MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private static void readFully(FileInputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("加密文件损坏：文件头不完整");
            }
            read += n;
        }
    }

    /** 解析文件头：魔数后首个字节为格式标记，读取派生参数与IV并初始化 Cipher */
    private static Cipher readHeaderAndCreateCipher(FileInputStream in, String key) throws IOException {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte flag = (byte) in.read();
            if (flag == FORMAT_PBKDF2) {
                byte[] meta = new byte[4 + KeyDerivationUtil.SALT_LENGTH];
                readFully(in, meta);
                ByteBuffer bb = ByteBuffer.wrap(meta);
                int iterations = bb.getInt();
                byte[] salt = new byte[KeyDerivationUtil.SALT_LENGTH];
                bb.get(salt);
                readFully(in, iv);
                byte[] keyBytes = KeyDerivationUtil.deriveKey(key, salt, iterations);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                return cipher;
            }
            // 历史格式：魔数后直接是 12 字节 IV，key 经单轮 SHA-256 派生
            iv[0] = flag;
            byte[] rest = new byte[GCM_IV_LENGTH - 1];
            readFully(in, rest);
            System.arraycopy(rest, 0, iv, 1, rest.length);
            byte[] keyBytes = KeyDerivationUtil.deriveKeyLegacy(key);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("上传文件加密初始化失败: " + e.getMessage(), e);
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
            throw new IOException("上传文件加密初始化失败: " + e.getMessage(), e);
        }
    }
}
