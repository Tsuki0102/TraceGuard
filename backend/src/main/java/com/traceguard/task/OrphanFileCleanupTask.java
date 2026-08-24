package com.traceguard.task;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.traceguard.entity.Project;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.util.FileStorageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 定时清理孤儿上传文件。
 * 上传目录按项目组织：uploads/requirements/{projectId}/、uploads/code/{projectId}/
 * 清理规则：
 * 1. 项目已不存在（含逻辑删除）时，整个项目目录视为孤儿，超期后整体删除；
 * 2. 项目存在时，仅保留数据库登记的当前文件（requirementFilePath/codeProjectPath），
 *    其余历史版本、解压残留超期后删除；
 * 3. 文件需超过保留期（默认24小时）才会被清理，避免误删刚上传还未入库的文件。
 */
@Component
public class OrphanFileCleanupTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanFileCleanupTask.class);

    @Value("${traceguard.storage.cleanup-retention-hours:24}")
    private long retentionHours;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private FileStorageUtil fileStorageUtil;

    @Scheduled(cron = "${traceguard.storage.cleanup-cron:0 0 3 * * ?}")
    public void cleanup() {
        try {
            File root = new File(fileStorageUtil.getUploadPath());
            if (!root.exists() || !root.isDirectory()) {
                return;
            }
            long thresholdMillis = System.currentTimeMillis() - retentionHours * 3600_000L;
            long deletedCount = 0;
            long freedBytes = 0;

            File[] categoryDirs = root.listFiles(File::isDirectory);
            if (categoryDirs == null) {
                return;
            }
            for (File categoryDir : categoryDirs) {
                File[] projectDirs = categoryDir.listFiles(File::isDirectory);
                if (projectDirs == null) {
                    continue;
                }
                for (File projectDir : projectDirs) {
                    long[] stats = cleanupProjectDir(projectDir, thresholdMillis);
                    deletedCount += stats[0];
                    freedBytes += stats[1];
                }
                // 业务目录已空则顺带删除
                File[] remain = categoryDir.listFiles();
                if (remain != null && remain.length == 0) {
                    FileUtil.del(categoryDir);
                }
            }
            if (deletedCount > 0) {
                LOGGER.info("孤儿文件清理完成：删除 {} 个文件/目录，释放 {} 字节", deletedCount, freedBytes);
            } else {
                LOGGER.debug("孤儿文件清理完成：无需清理");
            }
        } catch (Exception e) {
            LOGGER.error("孤儿文件清理任务执行失败: {}", e.getMessage(), e);
        }
    }

    /** 返回 [删除数量, 释放字节数] */
    private long[] cleanupProjectDir(File projectDir, long thresholdMillis) {
        Long projectId = parseProjectId(projectDir.getName());
        if (projectId == null) {
            return new long[]{0, 0};
        }
        Project project = projectMapper.selectById(projectId);
        // 项目不存在（含逻辑删除）：整个目录为孤儿
        if (project == null) {
            if (projectDir.lastModified() < thresholdMillis) {
                long size = dirSize(projectDir);
                if (FileUtil.del(projectDir)) {
                    LOGGER.info("清理孤儿项目目录[{}]：{} 字节", projectDir.getPath(), size);
                    return new long[]{1, size};
                }
            }
            return new long[]{0, 0};
        }
        // 项目存在：仅保留数据库登记的当前文件
        Set<String> referenced = new HashSet<>();
        addReferenced(referenced, project.getRequirementFilePath());
        addReferenced(referenced, project.getCodeProjectPath());

        // SEC-17：被引用的解压明文工作区（extracted_*）分析完成后超保留期即删除并清空 DB 引用，
        // 避免明文源码长期残留（真正的代码包为加密 ZIP，解压目录仅为分析工作区）
        clearExpiredExtractedWorkspace(projectId, referenced, thresholdMillis);

        File[] children = projectDir.listFiles();
        if (children == null) {
            return new long[]{0, 0};
        }
        long deleted = 0;
        long freed = 0;
        for (File child : children) {
            if (isReferenced(child, referenced) || child.lastModified() >= thresholdMillis) {
                continue;
            }
            long size = dirSize(child);
            if (FileUtil.del(child)) {
                deleted++;
                freed += size;
                LOGGER.info("清理未引用文件[{}]：{} 字节", child.getPath(), size);
            }
        }
        return new long[]{deleted, freed};
    }

    /** SEC-17：删除超期未更新的解压明文工作区，并同步清空 project.codeProjectPath 引用 */
    private void clearExpiredExtractedWorkspace(Long projectId, Set<String> referenced, long thresholdMillis) {
        for (String ref : referenced) {
            File refFile = new File(ref);
            if (!refFile.getName().startsWith("extracted_") || !refFile.isDirectory()
                    || refFile.lastModified() >= thresholdMillis) {
                continue;
            }
            long size = dirSize(refFile);
            if (FileUtil.del(refFile)) {
                try {
                    projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                            .eq(Project::getId, projectId)
                            .set(Project::getCodeProjectPath, null));
                } catch (Exception e) {
                    LOGGER.warn("清理解压工作区后清空 project.codeProjectPath 引用失败: {}", e.getMessage());
                }
                LOGGER.info("清理分析解压明文工作区[{}]：{} 字节（SEC-17）", refFile.getPath(), size);
            }
        }
    }

    private Long parseProjectId(String dirName) {
        try {
            return Long.valueOf(dirName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void addReferenced(Set<String> referenced, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        // 批量导入时数据库登记的是逗号分隔的多路径，须逐个拆分登记；
        // 否则整串路径与任何实际文件都不匹配，登记文件会被当作孤儿误删
        for (String p : path.split(",")) {
            String single = p.trim();
            if (single.isEmpty()) {
                continue;
            }
            try {
                referenced.add(new File(single).getCanonicalPath());
            } catch (Exception e) {
                referenced.add(new File(single).getAbsolutePath());
            }
        }
    }

    private boolean isReferenced(File file, Set<String> referenced) {
        try {
            return referenced.contains(file.getCanonicalPath());
        } catch (Exception e) {
            return referenced.contains(file.getAbsolutePath());
        }
    }

    private long dirSize(File file) {
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        return Arrays.stream(children).mapToLong(this::dirSize).sum();
    }
}
