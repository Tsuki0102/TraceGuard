package com.traceguard.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.traceguard.common.Result;
import com.traceguard.entity.DataChangeLog;
import com.traceguard.service.DataChangeLogService;
import com.traceguard.service.ProjectService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/data-change-log")
@Api(tags = "07-数据变更日志")
public class DataChangeLogController {

    @Autowired
    private DataChangeLogService changeLogService;

    @Autowired
    private ProjectService projectService;

    /**
     * 2.8 整改（FR-PLAT-004）：查询项目维度数据变更日志（字段级 diff）。
     * 校验项目归属权限；管理员可查任意项目。
     */
    @ApiOperation(value = "查询项目数据变更日志", notes = "2.8 返回项目维度字段级变更历史；校验项目归属权限")
    @GetMapping("/project/{projectId}")
    public Result<IPage<DataChangeLog>> pageByProject(@PathVariable Long projectId,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(changeLogService.pageByProject(projectId, pageNum, pageSize));
    }

    /**
     * 查询全部变更日志（默认视图）：管理员全量；普通用户仅限本人可见项目（系统级日志除外）。
     */
    @ApiOperation(value = "查询全部数据变更日志", notes = "管理员全量；普通用户仅限本人项目的变更记录")
    @GetMapping("/all")
    public Result<IPage<DataChangeLog>> pageAll(@RequestParam(defaultValue = "1") int pageNum,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        if (UserContext.isAdmin()) {
            return Result.success(changeLogService.pageAll(null, pageNum, pageSize));
        }
        List<Long> visibleIds = projectService.list(UserContext.getUserId())
                .stream().map(p -> p.getId()).collect(java.util.stream.Collectors.toList());
        return Result.success(changeLogService.pageAll(visibleIds, pageNum, pageSize));
    }

    /**
     * 2.8 整改（FR-PLAT-004）：查询某实体的变更历史（如 user/1001）。
     * 用户维度（entityType=user）仅管理员可查；项目维度复用归属校验。
     * 细粒度数据权限：非 project 实体（用户、系统配置等）仅管理员可查，防普通用户越权窥探他人数据变更。
     */
    @ApiOperation(value = "查询实体数据变更日志", notes = "2.8 返回指定实体的字段级变更历史（如 user / project）；项目维度校验归属，非项目维度仅管理员可查")
    @GetMapping("/entity")
    public Result<IPage<DataChangeLog>> pageByEntity(@RequestParam String entityType,
                                                      @RequestParam String entityId,
                                                      @RequestParam(defaultValue = "1") int pageNum,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        if ("project".equals(entityType)) {
            projectService.checkOwnership(Long.valueOf(entityId));
        } else if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可查询非项目维度变更日志");
        }
        return Result.success(changeLogService.pageByEntity(entityType, entityId, pageNum, pageSize));
    }
}
