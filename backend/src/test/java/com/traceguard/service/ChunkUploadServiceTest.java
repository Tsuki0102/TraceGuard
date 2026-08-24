package com.traceguard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片上传服务单元测试：init -> chunk -> status -> merge 全链路（含缺片报错与断点续传复用）
 */
@DisplayName("分片上传服务单元测试")
class ChunkUploadServiceTest {

    @TempDir
    Path tempDir;

    private ChunkUploadService service;

    @BeforeEach
    void setUp() {
        service = new ChunkUploadService();
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString() + "/");
    }

    private MockMultipartFile chunk(String content) {
        return new MockMultipartFile("file", "chunk.part",
                "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("完整流程：分片按序上传后合并还原原始内容与文件名")
    void fullUploadAndMerge() throws Exception {
        String uploadId = service.init(null, "需求文档.txt", 3);
        service.saveChunk(uploadId, 0, chunk("Hello "));
        service.saveChunk(uploadId, 1, chunk("Tra"));
        service.saveChunk(uploadId, 2, chunk("ceGuard!"));

        assertThat(service.uploadedChunks(uploadId)).containsExactly(0, 1, 2);

        ChunkUploadService.MergedFile merged = service.merge(uploadId);
        assertThat(merged.getOriginalName()).isEqualTo("需求文档.txt");
        assertThat(new String(Files.readAllBytes(merged.getFile().toPath()), StandardCharsets.UTF_8))
                .isEqualTo("Hello TraceGuard!");
    }

    @Test
    @DisplayName("缺片时合并报错并指明缺失分片序号")
    void mergeFailsWhenChunksMissing() throws Exception {
        String uploadId = service.init(null, "code.zip", 4);
        service.saveChunk(uploadId, 0, chunk("aa"));
        service.saveChunk(uploadId, 2, chunk("cc"));

        assertThatThrownBy(() -> service.merge(uploadId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("3");
    }

    @Test
    @DisplayName("断点续传：传入旧uploadId复用会话并返回已传分片索引")
    void resumeExistingSession() throws Exception {
        String uploadId = service.init(null, "big.zip", 3);
        service.saveChunk(uploadId, 0, chunk("aa"));
        service.saveChunk(uploadId, 1, chunk("bb"));

        // 模拟前端刷新后重新 init 并携带旧 uploadId
        String reused = service.init(uploadId, "big.zip", 3);
        assertThat(reused).isEqualTo(uploadId);
        assertThat(service.uploadedChunks(reused)).containsExactly(0, 1);

        // 续传最后一片后可正常合并
        service.saveChunk(reused, 2, chunk("cc"));
        ChunkUploadService.MergedFile merged = service.merge(reused);
        assertThat(new String(Files.readAllBytes(merged.getFile().toPath()), StandardCharsets.UTF_8))
                .isEqualTo("aabbcc");
    }

    @Test
    @DisplayName("cleanup清理分片目录，会话失效后再操作报错")
    void cleanupRemovesSession() throws Exception {
        String uploadId = service.init(null, "a.txt", 1);
        service.saveChunk(uploadId, 0, chunk("x"));
        service.cleanup(uploadId);

        assertThat(service.uploadedChunks(uploadId)).isEmpty();
        assertThatThrownBy(() -> service.saveChunk(uploadId, 0, chunk("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("会话不存在时初始化新目录且分片文件落盘到会话目录")
    void initCreatesChunkDirectory() throws Exception {
        String uploadId = service.init(null, "doc.pdf", 1);
        File chunkDir = new File(tempDir.toFile(), "chunks/" + uploadId);
        assertThat(chunkDir).exists();

        service.saveChunk(uploadId, 0, chunk("data"));
        assertThat(new File(chunkDir, "0.part")).exists();
        List<Integer> uploaded = service.uploadedChunks(uploadId);
        assertThat(uploaded).containsExactly(0);
    }

    @Test
    @DisplayName("SEC-08：非法 uploadId（目录穿越/非 UUID）被拒绝")
    void rejectsMaliciousUploadId() throws Exception {
        assertThatThrownBy(() -> service.saveChunk("../../etc/passwd", 0, chunk("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
        assertThatThrownBy(() -> service.uploadedChunks("..\\..\\etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cleanup("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SEC-08：分片序号越界（>=total 或 <0）被拒绝")
    void rejectsOutOfRangeChunkIndex() throws Exception {
        String uploadId = service.init(null, "a.txt", 3);
        assertThatThrownBy(() -> service.saveChunk(uploadId, 3, chunk("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> service.saveChunk(uploadId, -1, chunk("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
