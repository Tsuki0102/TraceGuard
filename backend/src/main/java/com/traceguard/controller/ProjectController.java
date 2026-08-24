package com.traceguard.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.common.Result;
import com.traceguard.entity.Project;
import com.traceguard.service.DataChangeLogService;
import com.traceguard.service.ProjectService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/project")
@Api(tags = "03-项目管理")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DataChangeLogService dataChangeLogService;

    @ApiOperation(value = "分页查询项目列表", notes = "数据隔离：非管理员强制只查询自己创建的项目")
    @GetMapping("/page")
    public Result<Page<Project>> page(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer size,
                                       @RequestParam(required = false) Long userId) {
        // 数据隔离：非管理员强制只查询自己创建的项目
        Long filterUserId = UserContext.isAdmin() ? userId : UserContext.getUserId();
        return Result.success(projectService.pageList(page, size, filterUserId));
    }

    @ApiOperation(value = "查询项目列表（不分页）", notes = "数据隔离：非管理员强制只返回自己创建的项目")
    @GetMapping("/list")
    public Result<List<Project>> list(@RequestParam(required = false) Long userId) {
        Long filterUserId = UserContext.isAdmin() ? userId : UserContext.getUserId();
        return Result.success(projectService.list(filterUserId));
    }

    @ApiOperation(value = "查询项目详情", notes = "需通过项目归属权限校验，非本人项目不可查看")
    @GetMapping("/{id}")
    public Result<Project> getById(@PathVariable Long id) {
        projectService.checkOwnership(id);
        return Result.success(projectService.getById(id));
    }

    @ApiOperation(value = "新建项目", notes = "创建项目，创建人默认归属当前登录用户")
    @PostMapping("/create")
    public Result<Project> create(@RequestBody Project project) {
        return Result.success(projectService.create(project));
    }

    @ApiOperation(value = "更新项目信息", notes = "需通过项目归属权限校验；禁止通过更新接口变更项目创建人")
    @PutMapping("/update")
    public Result<Project> update(@RequestBody Project project) {
        if (project.getId() != null) {
            projectService.checkOwnership(project.getId());
        }
        // 2.8 整改：字段级变更日志（对比旧值与新值，仅记录非空的传入字段）
        Project db = project.getId() != null ? projectService.getById(project.getId()) : null;
        // 禁止通过更新接口变更项目创建人
        project.setCreateUserId(null);
        Project saved = projectService.update(project);
        if (db != null) {
            java.util.Map<String, String> oldMap = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> newMap = new java.util.LinkedHashMap<>();
            java.util.List<String> trackFields = java.util.Arrays.asList("projectName", "industryType", "techStack", "description", "status");
            for (String f : trackFields) {
        String oldVal = fieldOf(db, f);
        String newVal = fieldOf(saved, f);
        oldMap.put(f, oldVal);
        newMap.put(f, newVal);
            }
            dataChangeLogService.recordFieldChanges("project", String.valueOf(project.getId()), project.getId(),
                    oldMap, newMap, trackFields);
        }
        return Result.success(saved);
    }

    @ApiOperation(value = "删除项目", notes = "需通过项目归属权限校验后删除项目（逻辑删除，进入回收站，可恢复）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.checkOwnership(id);
        projectService.delete(id);
        return Result.success();
    }

    @ApiOperation(value = "回收站分页查询", notes = "GAP-012：查询已删除（回收站）项目，数据隔离：非管理员强制只查自己")
    @GetMapping("/recycle/page")
    public Result<IPage<Project>> recyclePage(@RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "10") Integer size,
                                               @RequestParam(required = false) Long userId) {
        Long filterUserId = UserContext.isAdmin() ? userId : UserContext.getUserId();
        return Result.success(projectService.listDeleted(page, size, filterUserId));
    }

    @ApiOperation(value = "恢复回收站项目", notes = "GAP-012：将回收站项目恢复为正常状态（deleted=0）")
    @PostMapping("/recycle/restore/{id}")
    public Result<Void> recycleRestore(@PathVariable Long id) {
        projectService.restoreDeleted(id);
        return Result.success();
    }

    @ApiOperation(value = "彻底删除回收站项目", notes = "GAP-012：物理删除回收站项目，不可恢复，需二次确认")
    @PostMapping("/recycle/purge/{id}")
    public Result<Void> recyclePurge(@PathVariable Long id) {
        projectService.purgeDeleted(id);
        return Result.success();
    }

    @ApiOperation(value = "归档项目", notes = "需通过项目归属权限校验；归档后项目状态变更为已归档")
    @PostMapping("/archive/{id}")
    public Result<Void> archive(@PathVariable Long id) {
        projectService.checkOwnership(id);
        projectService.archive(id);
        return Result.success();
    }

    @ApiOperation(value = "恢复归档项目", notes = "需通过项目归属权限校验；将已归档项目恢复正常状态")
    @PostMapping("/restore/{id}")
    public Result<Void> restore(@PathVariable Long id) {
        projectService.checkOwnership(id);
        projectService.restore(id);
        return Result.success();
    }

    /** 2.8 整改：从 Project 安全提取字段字符串值（供变更日志对比） */
    private String fieldOf(Project p, String field) {
        if (p == null) return null;
        switch (field) {
            case "projectName": return p.getProjectName();
            case "industryType": return p.getIndustryType();
            case "techStack": return p.getTechStack();
            case "description": return p.getDescription();
            case "status": return p.getStatus();
            default: return null;
        }
    }
}
