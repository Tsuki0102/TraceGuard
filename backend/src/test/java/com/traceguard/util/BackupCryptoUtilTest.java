package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 备份加密工具单元测试：加解密往返、密文不含明文、错误密钥被GCM认证拒绝
 */
class BackupCryptoUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("加密压缩后解密解压能完整还原原文（含中文）")
    void encryptDecryptRoundTrip() throws IOException {
        String content = "-- TraceGuard 备份测试\nCREATE TABLE `tg_user` (...);\n-- 中文注释校验UTF-8完整性";
        File plain = tempDir.resolve("plain.sql").toFile();
        Files.write(plain.toPath(), content.getBytes(StandardCharsets.UTF_8));
        File encrypted = tempDir.resolve("backup_20260818_120000.sql.enc").toFile();

        BackupCryptoUtil.encrypt(plain, encrypted, "test-key-123");

        // 密文不含明文内容，且带12字节IV头
        byte[] encBytes = Files.readAllBytes(encrypted.toPath());
        assertThat(encBytes.length).isGreaterThan(12);
        assertThat(new String(encBytes, StandardCharsets.ISO_8859_1)).doesNotContain("tg_user");

        File restored = tempDir.resolve("restored.sql").toFile();
        BackupCryptoUtil.decrypt(encrypted, restored, "test-key-123");
        assertThat(new String(Files.readAllBytes(restored.toPath()), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    @DisplayName("错误密钥解密被GCM认证拒绝")
    void decryptWithWrongKeyFails() throws IOException {
        File plain = tempDir.resolve("plain.sql").toFile();
        Files.write(plain.toPath(), "secret-content".getBytes(StandardCharsets.UTF_8));
        File encrypted = tempDir.resolve("backup_20260818_120001.sql.enc").toFile();
        BackupCryptoUtil.encrypt(plain, encrypted, "right-key");

        File restored = tempDir.resolve("restored.sql").toFile();
        // GCM认证失败在流式读取阶段抛出，还原文件不应包含任何明文（可能未创建或为空）
        assertThatThrownBy(() -> BackupCryptoUtil.decrypt(encrypted, restored, "wrong-key"))
                .isInstanceOf(IOException.class);
        if (restored.exists()) {
            assertThat(new String(Files.readAllBytes(restored.toPath()), StandardCharsets.UTF_8))
                    .doesNotContain("secret-content");
        }
    }
}
