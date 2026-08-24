package com.traceguard.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GAP-031：ZIP 上传安全工具类
 * 提供安全的 ZIP 解压：路径穿越防护、压缩炸弹防护、魔数校验
 */
public class SecureZipUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecureZipUtil.class);

    /** ZIP 文件魔数: PK (50 4B) */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};

    /** 最大解压后文件大小（500MB），超过视为压缩炸弹 */
    private static final long MAX_EXTRACTED_SIZE = 500L * 1024 * 1024;

    /** 最大单文件解压比例（解压后/压缩前），超过 1000 倍视为压缩炸弹 */
    private static final long MAX_EXPANSION_RATIO = 1000;

    /**
     * 校验文件魔数（Magic Number）
     * @param file 要校验的文件
     * @return true=合法 ZIP 文件
     */
    public static boolean validateMagicNumber(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[2];
            int read = fis.read(header);
            if (read < 2) {
                return false;
            }
            // 校验 ZIP 魔数: PK
            if (header[0] != ZIP_MAGIC[0] || header[1] != ZIP_MAGIC[1]) {
                LOGGER.warn("文件魔数校验失败: 期望 PK (50 4B), 实际 {}",
                        String.format("%02X %02X", header[0], header[1]));
                return false;
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("校验文件魔数失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验文件扩展名是否为允许的类型
     * @param filename 文件名
     * @return true=允许的扩展名
     */
    public static boolean validateExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".war");
    }

    /**
     * 安全解压 ZIP 文件
     * 1. 校验魔数
     * 2. 路径穿越防护（拒绝 ../、绝对路径）
     * 3. 压缩炸弹防护
     *
     * @param zipFile 源 ZIP 文件
     * @param destDir 目标目录
     * @throws IOException 解压失败时抛出
     */
    public static void safeUnzip(File zipFile, File destDir) throws IOException {
        // 1. 魔数校验
        if (!validateMagicNumber(zipFile)) {
            throw new IOException("非法的 ZIP 文件格式或文件已被篡改");
        }

        // 2. 扩展名校验
        if (!validateExtension(zipFile.getName())) {
            throw new IOException("不支持的文件类型，仅允许 .zip/.jar/.war");
        }

        // 确保目标目录存在
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        // 解析目标目录的绝对路径，用于路径穿越检测
        String destDirPath = destDir.getCanonicalPath();

        long totalExtractedSize = 0;
        long totalCompressedSize = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // 3. 路径穿越防护
                validateEntryPath(entryName, destDirPath);

                // 计算压缩后大小
                long compressedSize = entry.getCompressedSize();
                if (compressedSize > 0) {
                    totalCompressedSize += compressedSize;
                }

                // 获取解压后大小
                long entrySize = entry.getSize();
                if (entrySize < 0) {
                    // 如果 ZIP 中未记录大小，则边解压边计算
                    entrySize = estimateEntrySize(zis, entry);
                }

                // 4. 压缩炸弹防护 - 检查解压比例
                if (compressedSize > 0 && entrySize > 0) {
                    long ratio = entrySize / compressedSize;
                    if (ratio > MAX_EXPANSION_RATIO) {
                        throw new IOException("检测到压缩炸弹攻击: 文件 " + entryName +
                                " 压缩比异常 (" + ratio + " 倍)，拒绝解压");
                    }
                }

                // 5. 总大小限制
                totalExtractedSize += entrySize;
                if (totalExtractedSize > MAX_EXTRACTED_SIZE) {
                    throw new IOException("解压后总大小超过限制 (500MB)，可能存在压缩炸弹攻击");
                }

                // 解压文件
                File outFile = new File(destDir, entryName);

                // 二次路径校验（防止通过符号链接绕过）
                String outFilePath = outFile.getCanonicalPath();
                if (!outFilePath.startsWith(destDirPath + File.separator) &&
                        !outFilePath.equals(destDirPath)) {
                    throw new IOException("检测到路径穿越尝试: " + entryName);
                }

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("无法创建目录: " + entryName);
                    }
                } else {
                    // 确保父目录存在
                    File parent = outFile.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建父目录: " + parent.getPath());
                    }

                    // 解压文件
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        LOGGER.info("ZIP 安全解压完成: {} -> {}, 压缩: {} bytes, 解压: {} bytes",
                zipFile.getName(), destDirPath, totalCompressedSize, totalExtractedSize);
    }

    /**
     * 校验 ZIP 条目路径安全性
     * @param entryName 条目名称
     * @param destDirPath 目标目录规范路径
     * @throws IOException 路径不安全时抛出
     */
    private static void validateEntryPath(String entryName, String destDirPath) throws IOException {
        if (entryName == null || entryName.isEmpty()) {
            throw new IOException("ZIP 条目名称为空");
        }

        // 规范化路径
        String normalized = entryName.replace('\\', '/');

        // 检查是否包含路径穿越序列
        if (normalized.contains("../") || normalized.contains("..\\")) {
            throw new IOException("检测到路径穿越尝试: " + entryName);
        }

        // 检查是否以 / 开头（绝对路径）
        if (normalized.startsWith("/")) {
            throw new IOException("检测到绝对路径: " + entryName);
        }

        // 检查是否包含 Windows 盘符
        if (normalized.matches("^[a-zA-Z]:/.*") || normalized.matches("^[a-zA-Z]:\\\\.*")) {
            throw new IOException("检测到 Windows 绝对路径: " + entryName);
        }
    }

    /**
     * 估算单个条目的解压后大小（在无法从元数据获取时使用）
     */
    private static long estimateEntrySize(ZipInputStream zis, ZipEntry entry) throws IOException {
        // 临时读取全部内容来估算大小（不太准确但作为兜底）
        long size = 0;
        byte[] buffer = new byte[8192];
        int len;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while ((len = zis.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.size();
    }
}
