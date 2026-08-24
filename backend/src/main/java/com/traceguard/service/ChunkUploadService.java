package com.traceguard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 大文件分片上传服务：仅负责分片的文件管理（初始化/保存/查询/合并/清理），
 * 不涉及数据库与业务落库，便于单元测试。合并产物由 Controller 交给 AnalysisService 落库。
 *
 * SEC-08 加固：
 * - uploadId 强制 UUID 格式校验，杜绝目录穿越写入面；
 * - saveChunk 校验分片序号不越界（0 <= index < totalChunks）；
 * - 并发会话数量上限 + 2 小时 TTL 惰性清理（防磁盘耗尽 DoS）。
 */
@Service
public class ChunkUploadService {

    @Value("${traceguard.storage.upload-path:./uploads/}")
    private String uploadPath;

    /** 合法 uploadId：32 位 hex（服务端生成的 UUID 去连字符形式） */
    private static final Pattern UUID32 = Pattern.compile("[0-9a-fA-F]{32}");
    /** 合法 uploadId：36 位带连字符 UUID（兼容客户端原样回传） */
    private static final Pattern UUID36 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** SEC-08：并发上传会话数量上限（防磁盘耗尽） */
    private static final int MAX_SESSIONS = 50;
    /** SEC-08：会话 TTL，超过该时长无活动即视为过期并被惰性清理 */
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    /** 合并产物：服务器本地临时文件 + 客户端原始文件名 */
    public static class MergedFile {
        private final File file;
        private final String originalName;

        public MergedFile(File file, String originalName) {
            this.file = file;
            this.originalName = originalName;
        }

        public File getFile() {
            return file;
        }

        public String getOriginalName() {
            return originalName;
        }
    }

    /**
     * 初始化上传会话。若传入已存在的 uploadId（刷新页面后断点续传场景），则复用原会话并返回已传分片。
     * SEC-08：uploadId 必须为合法 UUID；并发会话数受限；旧会话惰性清理。
     *
     * @return uploadId
     */
    public String init(String uploadId, String filename, int totalChunks) throws IOException {
        // uploadId 可选：为 null/空 表示新建会话；传入非空则必须是合法 UUID（断点续传）
        String id = (uploadId == null || uploadId.trim().isEmpty()) ? null : normalizeUploadId(uploadId);
        // SEC-08：惰性清理过期会话（含无 meta 的残留目录）
        cleanupExpiredSessions();
        if (id != null && metaFile(id).exists()) {
            touchSession(id);
            return id; // 复用旧会话，前端通过 status 接口获取已传分片
        }
        String newId = UUID.randomUUID().toString().replace("-", "");
        // SEC-08：会话数量上限（统计活跃会话数，含当前即将创建的）
        if (activeSessionCount() >= MAX_SESSIONS) {
            throw new IllegalArgumentException("上传会话数量已达上限，请完成或放弃未结束的上传后再试");
        }
        File dir = chunkDir(newId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 元数据：首行=原始文件名，次行=总分片数
        Files.write(metaFile(newId).toPath(), (filename + "\n" + totalChunks)
                .getBytes(StandardCharsets.UTF_8));
        return newId;
    }

    /**
     * 保存单个分片（index 从 0 开始）。SEC-08：uploadId 校验 UUID；index 不得越界 [0, totalChunks)。
     */
    public void saveChunk(String uploadId, int index, MultipartFile file) throws IOException {
        String id = normalizeUploadId(uploadId);
        File meta = metaFile(id);
        if (!meta.exists()) {
            throw new IllegalArgumentException("上传会话不存在或已过期，请重新初始化");
        }
        if (index < 0 || index >= readTotalChunks(meta)) {
            throw new IllegalArgumentException("分片序号越界（应为 0~"
                    + (readTotalChunks(meta) - 1) + "）");
        }
        File chunk = new File(chunkDir(id), index + ".part");
        file.transferTo(chunk.getAbsoluteFile());
        touchSession(id); // 刷新活动时间，避免 TTL 误清
    }

    /** 已上传的分片索引列表（断点续传时前端跳过这些分片） */
    public List<Integer> uploadedChunks(String uploadId) {
        String id = normalizeUploadId(uploadId);
        File dir = chunkDir(id);
        if (!dir.exists()) {
            return Collections.emptyList();
        }
        List<Integer> indexes = new ArrayList<>();
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        if (parts == null) {
            return indexes;
        }
        for (File p : parts) {
            try {
                indexes.add(Integer.parseInt(p.getName().replace(".part", "")));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(indexes);
        return indexes;
    }

    /** 校验分片齐全后按序合并为单文件，返回合并产物（文件名无业务含义，落库时用 originalName） */
    public MergedFile merge(String uploadId) throws IOException {
        String id = normalizeUploadId(uploadId);
        File meta = metaFile(id);
        if (!meta.exists()) {
            throw new IllegalArgumentException("上传会话不存在或已过期，请重新初始化");
        }
        List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
        String originalName = lines.get(0);
        int total = Integer.parseInt(lines.get(1));
        List<Integer> uploaded = uploadedChunks(id);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!uploaded.contains(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("分片未上传完整，缺失分片序号: " + missing);
        }
        File merged = new File(chunkDir(id), "merged.tmp");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(merged))) {
            for (int i = 0; i < total; i++) {
                Files.copy(new File(chunkDir(id), i + ".part").toPath(), out);
            }
        }
        touchSession(id);
        return new MergedFile(merged, originalName);
    }

    /** 上传完成或放弃后清理分片临时目录 */
    public void cleanup(String uploadId) {
        String id = normalizeUploadId(uploadId);
        File dir = chunkDir(id);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    /**
     * SEC-08：uploadId 归一化并强校验（仅接受 UUID 格式），非法输入（含路径穿越字符串）直接拒绝。
     */
    private String normalizeUploadId(String uploadId) {
        if (uploadId == null || uploadId.trim().isEmpty()) {
            throw new IllegalArgumentException("非法的上传会话标识");
        }
        String id = uploadId.trim();
        if (UUID36.matcher(id).matches()) {
            return id.replace("-", "");
        }
        if (UUID32.matcher(id).matches()) {
            return id.toLowerCase();
        }
        throw new IllegalArgumentException("非法的上传会话标识（必须为 UUID 格式）");
    }

    /** 读取元数据中的总分片数 */
    private int readTotalChunks(File meta) throws IOException {
        List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
        return Integer.parseInt(lines.get(1));
    }

    /** 刷新会话活动时间（meta 文件 mtime 作为 TTL 依据） */
    private void touchSession(String id) {
        File meta = metaFile(id);
        if (meta.exists()) {
            meta.setLastModified(System.currentTimeMillis());
        }
    }

    /** SEC-08：惰性清理超过 TTL 未活动的会话目录（含无 meta 的残留目录） */
    private void cleanupExpiredSessions() {
        File root = new File(uploadPath, "chunks");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            File meta = new File(dir, "meta.txt");
            boolean expired = !meta.exists()
                    || now - meta.lastModified() > SESSION_TTL.toMillis();
            if (expired) {
                deleteDirQuietly(dir);
            }
        }
    }

    /** SEC-08：统计当前活跃会话数 */
    private int activeSessionCount() {
        File root = new File(uploadPath, "chunks");
        File[] dirs = root.listFiles(File::isDirectory);
        return dirs == null ? 0 : dirs.length;
    }

    private void deleteDirQuietly(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private File chunkDir(String uploadId) {
        return new File(uploadPath, "chunks/" + uploadId);
    }

    private File metaFile(String uploadId) {
        return new File(chunkDir(uploadId), "meta.txt");
    }
}
