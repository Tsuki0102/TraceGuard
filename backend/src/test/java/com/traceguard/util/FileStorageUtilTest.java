package com.traceguard.util;

import com.traceguard.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GAP-031：上传文件魔数校验测试
 * 验证点（设计 4.3.11）：
 * 1. 伪扩展名（改名 .zip 的实际 txt）被拒绝
 * 2. 伪 PDF（内容非 %PDF）被拒绝
 * 3. 合法文本 .txt 上传不受影响
 * 4. 合法 ZIP（PK 魔数）上传不受影响
 */
@DisplayName("GAP-031 上传魔数校验")
class FileStorageUtilTest {

    @Test
    @DisplayName("伪扩展名：txt 内容改名 .zip -> 拒绝")
    void pseudoZipRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.zip",
                "application/zip", "this is not a zip file, just plain text".getBytes());
        assertThatThrownBy(() -> FileStorageUtil.validateFileType(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ZIP")
                .hasMessageContaining("魔数");
    }

    @Test
    @DisplayName("伪 PDF：非 %PDF 头 -> 拒绝")
    void pseudoPdfRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf",
                "application/pdf", "not a pdf at all".getBytes());
        assertThatThrownBy(() -> FileStorageUtil.validateFileType(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF")
                .hasMessageContaining("魔数");
    }

    @Test
    @DisplayName("合法 txt 文本 -> 通过")
    void validTxtAccepted() {
        MockMultipartFile file = new MockMultipartFile("file", "req.txt",
                "text/plain", "需求：系统应支持用户登录与权限管理。".getBytes());
        assertThatCode(() -> FileStorageUtil.validateFileType(file)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("合法 ZIP（PK 魔数）-> 通过")
    void validZipAccepted() {
        // 最小 ZIP 空文件头（PK 0x03 0x04）
        byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "code.zip",
                "application/zip", zipHeader);
        assertThatCode(() -> FileStorageUtil.validateFileType(file)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不支持扩展名 -> 拒绝")
    void unsupportedExtRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", new byte[]{0x50, 0x4B, 0x03, 0x04});
        assertThatThrownBy(() -> FileStorageUtil.validateFileType(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }
}
