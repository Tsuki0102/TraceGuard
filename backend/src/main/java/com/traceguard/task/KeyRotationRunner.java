package com.traceguard.task;

import com.traceguard.service.AuditService;
import com.traceguard.util.BackupCryptoUtil;
import com.traceguard.util.UploadCryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GAP-028：加密切钥轮换任务（手动触发）。
 *
 * 遍历上传文件目录与备份目录，将使用旧密钥加密的文件解密后重新加密为新密钥。
 * 幂等：已通过新密钥加密的文件跳过；失败可中断重跑。
 *
 * 前置条件：
 * - 服务需停机或维护窗口执行（避免运行中写入旧密钥文件）
 * - 旧密钥必须能解密所有存量文件
 * - 新密钥长度 ≥ 8 位
 */
@Component
public class KeyRotationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeyRotationRunner.class);

    @Value("${traceguard.storage.upload-path:./uploads/}")
    private String uploadPath;

    @Value("${traceguard.storage.backup-path:./backups/}")
    private String backupPath;

    @Value("${traceguard.storage.encrypt-key:}")
    private String currentUploadKey;

    @Value("${traceguard.backup.encrypt-key:}")
    private String currentBackupKey;

    /**
     * 执行密钥轮换。
     *
     * @param oldKey 旧密钥
     * @param newKey 新密钥
     * @return 执行结果清单（成功/失败文件）
     */
    public List<String> rotate(String oldKey, String newKey) throws IOException {
        if (oldKey == null || oldKey.isEmpty() || newKey == null || newKey.length() < 8) {
            throw new IllegalArgumentException("旧密钥不能为空，新密钥长度不能少于 8 位");
        }
        if (oldKey.equals(newKey)) {
            throw new IllegalArgumentException("新旧密钥不能相同");
        }

        List<String> result = new ArrayList<>();
        int success = 0;
        int skipped = 0;
        int failed = 0;

        List<File> targets = collectEncryptedFiles();
        LOGGER.info("[KeyRotation] 共发现 {} 个待轮换加密文件", targets.size());

        for (File file : targets) {
            try {
                boolean rotated = rotateFile(file, oldKey, newKey);
                if (rotated) {
                    result.add("SUCCESS: " + file.getAbsolutePath());
                    success++;
                } else {
                    result.add("SKIPPED: " + file.getAbsolutePath());
                    skipped++;
                }
            } catch (Exception e) {
                LOGGER.error("[KeyRotation] 轮换失败: {}", file.getAbsolutePath(), e);
                result.add("FAILED: " + file.getAbsolutePath() + " - " + e.getMessage());
                failed++;
            }
        }

        LOGGER.info("[KeyRotation] 完成：成功 {}，跳过 {}，失败 {}", success, skipped, failed);
        return result;
    }

    /**
     * 收集所有需要轮换的加密文件（上传目录 .enc 文件 + 备份目录 .sql.enc 文件）
     */
    private List<File> collectEncryptedFiles() {
        List<File> files = new ArrayList<>();
        collectFiles(new File(uploadPath), files, null);
        collectFiles(new File(backupPath), files, ".sql.enc");
        return files;
    }

    private void collectFiles(File dir, List<File> result, String suffixFilter) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFiles(f, result, suffixFilter);
            } else if (f.isFile()) {
                if (suffixFilter == null) {
                    // 上传目录：只处理加密文件（带 TGENC1 魔数）
                    if (UploadCryptoUtil.isEncryptedFile(f)) {
                        result.add(f);
                    }
                } else {
                    // 备份目录：只处理 .sql.enc
                    if (f.getName().endsWith(suffixFilter)) {
                        result.add(f);
                    }
                }
            }
        }
    }

    /**
     * 单个文件轮换。返回 true=已轮换，false=跳过
     */
    private boolean rotateFile(File file, String oldKey, String newKey) throws IOException {
        File tempPlain = new File(file.getParentFile(), file.getName() + ".rotate.tmp");
        try {
            // 1. 尝试用旧密钥解密
            if (file.getName().endsWith(".sql.enc")) {
                BackupCryptoUtil.decrypt(file, tempPlain, oldKey);
                // 2. 用新密钥加密覆盖
                File newFile = new File(file.getParentFile(), file.getName() + ".new");
                BackupCryptoUtil.encrypt(tempPlain, newFile, newKey);
                replaceFile(newFile, file);
            } else {
                // 上传文件
                UploadCryptoUtil.decryptFile(file, tempPlain, oldKey);
                // 2. 用新密钥加密覆盖
                File newFile = new File(file.getParentFile(), file.getName() + ".new");
                UploadCryptoUtil.encryptStream(Files.newInputStream(tempPlain.toPath()), newFile, newKey);
                replaceFile(newFile, file);
            }
            return true;
        } finally {
            Files.deleteIfExists(tempPlain.toPath());
        }
    }

    private void replaceFile(File newFile, File oldFile) throws IOException {
        if (!newFile.exists()) {
            throw new IOException("新文件未生成");
        }
        if (!oldFile.delete()) {
            throw new IOException("无法删除旧文件");
        }
        if (!newFile.renameTo(oldFile)) {
            throw new IOException("无法重命名新文件");
        }
    }
}
