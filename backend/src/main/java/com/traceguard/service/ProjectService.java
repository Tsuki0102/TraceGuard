package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.common.BusinessException;
import com.traceguard.entity.Project;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.File;
import java.util.List;

@Service
public class ProjectService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectService.class);

    /** GAP-039：最大项目数上限（含进行中/已完成/归档，不含回收站逻辑删除项由 MP 自动过滤） */
    private static final long MAX_PROJECT_COUNT = 100;
    /** GAP-039：单项目存储容量上限 10GB */
    private static final long MAX_PROJECT_STORAGE_BYTES = 10L * 1024 * 1024 * 1024;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private FileStorageUtil fileStorageUtil;

    /** GAP-012：回收站超期保留天数（超期后定时物理删除） */
    @Value("${traceguard.recycle.retention-days:30}")
    private int recycleRetentionDays;

    public Page<Project> pageList(Integer page, Integer size, Long userId) {
        Page<Project> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Project::getCreateUserId, userId);
        }
        wrapper.orderByDesc(Project::getCreateTime);
        return projectMapper.selectPage(pageParam, wrapper);
    }

    public List<Project> list(Long userId) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Project::getCreateUserId, userId);
        }
        wrapper.orderByDesc(Project::getCreateTime);
        return projectMapper.selectList(wrapper);
    }

    public Project getById(Long id) {
        return projectMapper.selectById(id);
    }

    public Project create(Project project) {
        // GAP-039：项目总数（不含回收站，MP 逻辑删除自动过滤 deleted=0）达上限则拒绝
        long existing = projectMapper.selectCount(new LambdaQueryWrapper<>());
        if (existing >= MAX_PROJECT_COUNT) {
            throw new BusinessException("项目数量已达上限（" + MAX_PROJECT_COUNT + " 个），请先清理不再需要的项目");
        }
        project.setStatus("created");
        project.setRequirementCount(0);
        project.setCoverageRate(0.0);
        project.setDefectCount(0);
        project.setCreateUserId(UserContext.getUserId());
        projectMapper.insert(project);
        return project;
    }

    /**
     * GAP-039：单项目存储容量校验——统计该项目在 uploads 下的需求文档与代码工程（含解压产物）
     * 累计大小，超过 10GB 上限抛出业务异常，由上传/合并落盘后调用方触发拦截。
     */
    public void checkStorageQuota(Long projectId) {
        if (projectId == null) {
            return;
        }
        String base = fileStorageUtil.getUploadPath();
        long total = 0;
        total += computeDirSize(new File(base, "requirements/" + projectId));
        total += computeDirSize(new File(base, "code/" + projectId));
        if (total > MAX_PROJECT_STORAGE_BYTES) {
            throw new BusinessException("单项目存储容量已超过 10GB 上限（当前约 "
                    + String.format("%.2f", total / 1024.0 / 1024.0 / 1024.0) + "GB），请精简上传内容后重试");
        }
    }

    /** 递归统计目录总字节数（目录不存在返回 0） */
    private long computeDirSize(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long size = 0;
        for (File c : children) {
            if (c.isDirectory()) {
                size += computeDirSize(c);
            } else {
                size += c.length();
            }
        }
        return size;
    }

    public Project update(Project project) {
        projectMapper.updateById(project);
        return project;
    }

    public void delete(Long id) {
        // MyBatis-Plus @TableLogic：逻辑删除（deleted=1），项目进入回收站
        projectMapper.deleteById(id);
    }

    /** GAP-012：回收站分页查询（deleted=1，数据隔离：普通用户仅查自己） */
    public IPage<Project> listDeleted(Integer page, Integer size, Long userId) {
        return projectMapper.selectDeletedPage(new Page<>(page, size), userId);
    }

    /** GAP-012：恢复回收站项目（deleted=0） */
    public void restoreDeleted(Long id) {
        checkDeletedOwnership(id);
        if (projectMapper.restoreDeleted(id) == 0) {
            throw new BusinessException(404, "项目不存在或已彻底删除");
        }
    }

    /** GAP-012：彻底删除回收站项目（物理删除，不可恢复） */
    public void purgeDeleted(Long id) {
        checkDeletedOwnership(id);
        if (projectMapper.purgeDeleted(id) == 0) {
            throw new BusinessException(404, "项目不存在或已彻底删除");
        }
    }

    /** GAP-012：定时清理回收站超期项目（默认 30 天），仅作用于回收站项目 */
    @Scheduled(cron = "${traceguard.recycle.cleanup-cron:0 30 3 * * ?}")
    public void cleanExpiredDeletedProjects() {
        try {
            int purged = projectMapper.purgeExpiredDeleted(recycleRetentionDays);
            if (purged > 0) {
                LOGGER.info("回收站超期清理完成：物理删除 {} 个项目（保留 {} 天）", purged, recycleRetentionDays);
            }
        } catch (Exception e) {
            LOGGER.error("回收站超期清理失败: {}", e.getMessage(), e);
        }
    }

    /** GAP-012：回收站项目归属校验（非管理员仅可操作自己删除的项目） */
    private void checkDeletedOwnership(Long id) {
        Project project = projectMapper.selectDeletedById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在或已彻底删除");
        }
        if (!UserContext.isAdmin()
                && project.getCreateUserId() != null
                && !project.getCreateUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(403, "无权限：仅项目创建者可操作该项目");
        }
    }

    /** 项目归档：归档后项目数据保留，状态置为archived */
    public void archive(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        if ("archived".equals(project.getStatus())) {
            throw new BusinessException("项目已处于归档状态");
        }
        project.setStatus("archived");
        projectMapper.updateById(project);
    }

    /** 项目恢复：从归档状态恢复为已创建状态 */
    public void restore(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        if (!"archived".equals(project.getStatus())) {
            throw new BusinessException("项目未归档，无需恢复");
        }
        project.setStatus("created");
        projectMapper.updateById(project);
    }

    public void updateStats(Long projectId, int reqCount, double coverage, int defectCount) {
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setRequirementCount(reqCount);
            project.setCoverageRate(coverage);
            project.setDefectCount(defectCount);
            projectMapper.updateById(project);
        }
    }

    /**
     * 数据隔离校验（需求5.2.3）：普通用户仅可访问自己创建的项目，管理员不受限制。
     * 历史数据（创建人为空）不限制访问，保证升级兼容。
     */
    /**
     * 项目批量操作（个性化增强 BATCH-4）
     * action ∈ delete（进回收站）/ archive（归档）/ restore（恢复归档）
     * 逐条走与单条操作一致的归属校验；失败项不影响其余项。
     */
    public java.util.Map<String, Object> batchAction(String action, List<Long> ids) {
        int success = 0;
        java.util.List<Long> failed = new java.util.ArrayList<>();
        for (Long id : ids) {
            try {
                checkOwnership(id);
                switch (action) {
                    case "delete": delete(id); break;
                    case "archive": archive(id); break;
                    case "restore": restore(id); break;
                    default: throw new BusinessException("未知操作: " + action);
                }
                success++;
            } catch (Exception e) {
                LOGGER.warn("批量操作失败 id={} action={}: {}", id, action, e.getMessage());
                failed.add(id);
            }
        }
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("success", success);
        r.put("failed", failed);
        return r;
    }

    public void checkOwnership(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(400, "项目ID不能为空");
        }
        if (UserContext.isAdmin()) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        if (project.getCreateUserId() != null && !project.getCreateUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(403, "无权限：仅项目创建者可访问该项目数据");
        }
    }
}
