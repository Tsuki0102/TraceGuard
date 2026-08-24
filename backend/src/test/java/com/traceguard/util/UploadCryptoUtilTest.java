package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传文件加密工具单元测试（需求5.2.1 敏感数据加密存储）：
 * 加解密往返、魔数识别、错误密钥被GCM认证拒绝、FileStorageUtil落盘与读取链路
 */
class UploadCryptoUtilTest {

    @TempDir
    Path tempDir;

    private static final String KEY = "upload-test-key";

    @Test
    @DisplayName("加密后解密能完整还原原文（含中文），密文不含明文")
    void encryptDecryptRoundTrip() throws IOException {
        String content = "# TraceGuard 需求文档\nFR-REQ-001 用户登录校验\n中文内容完整性校验";
        File target = tempDir.resolve("req.md").toFile();

        UploadCryptoUtil.encryptStream(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), target, KEY);

        // 落盘文件被识别为加密格式，且密文中找不到明文
        assertThat(UploadCryptoUtil.isEncryptedFile(target)).isTrue();
        byte[] encBytes = Files.readAllBytes(target.toPath());
        assertThat(new String(encBytes, StandardCharsets.ISO_8859_1)).doesNotContain("FR-REQ-001");

        File restored = tempDir.resolve("restored.md").toFile();
        UploadCryptoUtil.decryptFile(target, restored, KEY);
        assertThat(new String(Files.readAllBytes(restored.toPath()), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    @DisplayName("历史明文文件不被误判为加密格式")
    void plainFileNotDetectedAsEncrypted() throws IOException {
        File plain = tempDir.resolve("legacy.docx").toFile();
        Files.write(plain.toPath(), "legacy-plain-content".getBytes(StandardCharsets.UTF_8));
        assertThat(UploadCryptoUtil.isEncryptedFile(plain)).isFalse();
    }

    @Test
    @DisplayName("错误密钥解密被GCM认证拒绝")
    void decryptWithWrongKeyFails() throws IOException {
        File target = tempDir.resolve("secret.md").toFile();
        UploadCryptoUtil.encryptStream(
                new ByteArrayInputStream("secret-content".getBytes(StandardCharsets.UTF_8)), target, KEY);

        File restored = tempDir.resolve("restored.md").toFile();
        assertThatThrownBy(() -> UploadCryptoUtil.decryptFile(target, restored, "wrong-key"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("FileStorageUtil保存自动加密落盘，readFile自动解密还原（5.2.1）")
    void fileStorageRoundTrip() throws IOException {
        FileStorageUtil storage = new FileStorageUtil();
        ReflectionTestUtils.setField(storage, "uploadPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(storage, "encryptKey", KEY);

        byte[] content = "需求文档内容：FR-REQ-002 数据加密存储".getBytes(StandardCharsets.UTF_8);
        String path = storage.saveFile(content, "requirements/1", "req.md");

        // 磁盘上为密文（识别为加密格式且不含明文）
        File saved = new File(path);
        assertThat(UploadCryptoUtil.isEncryptedFile(saved)).isTrue();
        assertThat(new String(Files.readAllBytes(saved.toPath()), StandardCharsets.ISO_8859_1))
                .doesNotContain("FR-REQ-002");

        // readFile 自动解密还原，且解密临时文件用完即删
        assertThat(storage.readFile(path)).isEqualTo(content);
        assertThat(new File(path).getParentFile().list((d, n) -> n.contains(".plain"))).isNullOrEmpty();

        // ensurePlainFile 对历史明文文件原样返回
        File legacy = tempDir.resolve("legacy.txt").toFile();
        Files.write(legacy.toPath(), "legacy".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.ensurePlainFile(legacy.getAbsolutePath())).isEqualTo(legacy);
    }
}
