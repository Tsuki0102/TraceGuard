package com.traceguard.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.traceguard.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 上传文件存储工具（需求5.2.1 敏感数据加密存储）：
 * 所有上传文件（需求文档/代码压缩包/分片合并产物）落盘前经 AES-256-GCM 加密，
 * 明文不落盘；读取时自动识别加密格式解密，兼容历史存量明文文件。
 * 加密格式见 {@link UploadCryptoUtil}。
 */
@Component
public class FileStorageUtil {

    /** ZIP/DOCX 文件头魔数：PK */
    private static final byte[] MAGIC_PK = {0x50, 0x4B};
    /** PDF 文件头魔数：%PDF */
    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};

    @Value("${traceguard.storage.upload-path:./uploads/}")
    private String uploadPath;

    /** 上传文件落盘加密密钥（生产环境应通过环境变量注入并妥善保管） */
    @Value("${traceguard.storage.encrypt-key:TraceGuard#2026!UploadSecret}")
    private String encryptKey;

    /**
     * GAP-031：上传文件魔数校验（MultipartFile 入口）。
     * 按扩展名读取文件头魔数，与扩展名不一致即拒绝：
     * - docx / zip / jar / war：PK（0x50 0x4B）
     * - pdf：%PDF
     * - md / markdown / txt：文本检测（前 8 字节无可疑二进制控制字符）
     */
    public static void validateFileType(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件为空");
        }
        try (InputStream in = file.getInputStream()) {
            validateFileType(in, file.getOriginalFilename());
        }
    }

    /**
     * GAP-031：上传文件魔数校验（服务器本地文件入口，分片合并产物复用）。
     */
    public static void validateFileType(File file, String originalName) throws IOException {
        if (file == null || !file.exists()) {
            throw new BusinessException(400, "上传文件不存在");
        }
        try (InputStream in = new FileInputStream(file)) {
            validateFileType(in, originalName);
        }
    }

    private static void validateFileType(InputStream in, String originalName) throws IOException {
        String ext = FileUtil.extName(originalName == null ? "" : originalName).toLowerCase();
        if (ext.isEmpty()) {
            throw new BusinessException(400, "无法识别上传文件的扩展名");
        }

        byte[] header = new byte[8];
        int len = in.read(header);
        if (len <= 0) {
            throw new BusinessException(400, "上传文件内容为空，无法校验文件类型");
        }

        boolean matched;
        switch (ext) {
            case "docx":
            case "zip":
            case "jar":
            case "war":
                matched = startsWith(header, len, MAGIC_PK);
                if (!matched) {
                    throw new BusinessException(400, "文件[" + originalName + "]不是合法的 "
                            + ext.toUpperCase() + " 文件（文件头魔数应为 PK，疑似伪造扩展名）");
                }
                break;
            case "pdf":
                matched = startsWith(header, len, MAGIC_PDF);
                if (!matched) {
                    throw new BusinessException(400, "文件[" + originalName + "]不是合法的 PDF 文件（文件头魔数应为 %PDF，疑似伪造扩展名）");
                }
                break;
            case "md":
            case "markdown":
            case "txt":
            case "java":
                // SEC-09：.java 源码与文本文件同一校验口径——含 NUL 等二进制控制字符即拒绝（防伪造扩展名）
                if (hasBinaryControl(header, len)) {
                    throw new BusinessException(400, "文件[" + originalName + "]不是合法的"
                            + (ext.equals("java") ? " Java 源文件" : "文本文件") + "（包含二进制控制字符，疑似伪造扩展名）");
                }
                break;
            default:
                throw new BusinessException(400, "不支持的文件类型: ." + ext
                        + "（仅支持 .docx/.pdf/.md/.markdown/.txt 需求文档与 .zip/.jar/.war 代码压缩包与 .java 源码）");
        }
    }

    private static boolean startsWith(byte[] data, int len, byte[] magic) {
        if (len < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** 文本检测：前 8 字节出现 NUL（0x00）即视为非文本（UTF-8 中文高位字节合法，不误判） */
    private static boolean hasBinaryControl(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            if (data[i] == 0x00) {
                return true;
            }
        }
        return false;
    }

    public String saveFile(MultipartFile file, String subDir) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String ext = FileUtil.extName(originalFilename);
        String newFilename = IdUtil.simpleUUID() + "." + ext;
        File dest = newFile(subDir, newFilename);
        // 加密落盘：流式读取上传内容并加密写出，磁盘上不出现明文
        try (InputStream in = file.getInputStream()) {
            UploadCryptoUtil.encryptStream(in, dest, encryptKey);
        }
        return dest.getAbsolutePath();
    }

    public String saveFile(byte[] content, String subDir, String filename) throws IOException {
        File dest = newFile(subDir, filename);
        UploadCryptoUtil.encryptStream(new ByteArrayInputStream(content), dest, encryptKey);
        return dest.getAbsolutePath();
    }

    /** 将服务器本地文件（如分片合并产物）按 UUID 命名加密保存到指定子目录，返回绝对路径 */
    public String saveFile(File src, String originalName, String subDir) throws IOException {
        String ext = FileUtil.extName(originalName);
        String newFilename = IdUtil.simpleUUID() + "." + ext;
        File dest = newFile(subDir, newFilename);
        try (InputStream in = new FileInputStream(src)) {
            UploadCryptoUtil.encryptStream(in, dest, encryptKey);
        }
        return dest.getAbsolutePath();
    }

    /**
     * 读取文件内容：加密文件自动解密返回明文字节，历史明文文件原样返回
     */
    public byte[] readFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (UploadCryptoUtil.isEncryptedFile(file)) {
            File plain = ensurePlainFile(filePath);
            try {
                return FileUtil.readBytes(plain);
            } finally {
                cleanupPlainFile(plain, file);
            }
        }
        return FileUtil.readBytes(file);
    }

    /**
     * 获取可按明文读取的文件对象：加密文件解密到同目录临时文件（*.plain.tmp）返回，
     * 明文文件原样返回。调用方使用完毕后须调用 {@link #cleanupPlainFile(File, File)} 清理临时文件。
     */
    public File ensurePlainFile(String filePath) throws IOException {
        File src = new File(filePath);
        if (!UploadCryptoUtil.isEncryptedFile(src)) {
            return src;
        }
        // 临时文件保留原扩展名（xxx.md -> xxx.plain.md），解析器按扩展名分发放行
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String plainName = dot > 0
                ? name.substring(0, dot) + ".plain" + name.substring(dot)
                : name + ".plain.tmp";
        File plain = new File(src.getParentFile(), plainName);
        UploadCryptoUtil.decryptFile(src, plain, encryptKey);
        return plain;
    }

    /** 删除解密产生的临时明文文件（plain 与原文件相同时不动） */
    public void cleanupPlainFile(File plain, File original) {
        if (plain != null && original != null
                && !plain.getAbsolutePath().equals(original.getAbsolutePath())) {
            FileUtil.del(plain);
        }
    }

    public void deleteFile(String filePath) {
        FileUtil.del(filePath);
    }

    public String getUploadPath() {
        return uploadPath;
    }

    private File newFile(String subDir, String filename) throws IOException {
        File dir = new File(uploadPath, subDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, filename);
    }
}
