package com.traceguard.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.traceguard.common.Result;
import com.traceguard.entity.AnalysisTask;
import com.traceguard.service.AnalysisService;
import com.traceguard.service.ProjectService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/analysis")
@Api(tags = "04-分析任务")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectService projectService;

    @ApiOperation(value = "上传单个需求文档", notes = "需通过项目归属权限校验；返回文档存储路径")
    @PostMapping("/upload/requirement/{projectId}")
    public Result<String> uploadRequirement(@PathVariable Long projectId,
                                             @RequestParam("file") MultipartFile file) throws Exception {
        projectService.checkOwnership(projectId);
        String path = analysisService.uploadRequirement(file, projectId);
        return Result.success(path);
    }

    /** 批量导入需求文档（FR-REQ-001 支持多文档批量导入） */
    @ApiOperation(value = "批量导入需求文档", notes = "FR-REQ-001 支持多文档批量导入；需通过项目归属权限校验")
    @PostMapping("/upload/requirements/{projectId}")
    public Result<String> uploadRequirements(@PathVariable Long projectId,
                                              @RequestParam("files") MultipartFile[] files) throws Exception {
        projectService.checkOwnership(projectId);
        String paths = analysisService.uploadRequirements(files, projectId);
        return Result.success(paths);
    }

    @ApiOperation(value = "上传代码工程", notes = "需通过项目归属权限校验；返回代码包存储路径")
    @PostMapping("/upload/code/{projectId}")
    public Result<String> uploadCode(@PathVariable Long projectId,
                                      @RequestParam("file") MultipartFile file) {
        projectService.checkOwnership(projectId);
        String path = analysisService.uploadCodeProject(file, projectId);
        return Result.success(path);
    }

    /**
     * FR-CODE-001 规则4（2.4 整改项）：单 Java 文件批量导入入口。
     * 适用于零散 .java 源文件场景，与 ZIP 代码工程上传（/upload/code）互为补充；校验项目归属权限。
     */
    @ApiOperation(value = "单 Java 文件批量导入", notes = "FR-CODE-001 2.4：批量上传 .java 源文件到项目代码目录，替换当前代码工程")
    @PostMapping("/upload/code-files/{projectId}")
    public Result<String> uploadCodeFiles(@PathVariable Long projectId,
                                          @RequestParam("files") MultipartFile[] files) {
        projectService.checkOwnership(projectId);
        String path = analysisService.uploadCodeFiles(files, projectId);
        return Result.success(path);
    }

    @ApiOperation(value = "创建分析任务", notes = "需通过项目归属权限校验；可配置权重（alpha/beta/gamma）与阈值（T1/T2）")
    @PostMapping("/task/create")
    public Result<AnalysisTask> createTask(@RequestBody AnalysisTask task) throws Exception {
        projectService.checkOwnership(task.getProjectId());
        AnalysisTask created = analysisService.createTask(
        task.getProjectId(),
        task.getTaskName(),
        task.getWeightAlpha(),
        task.getWeightBeta(),
        task.getWeightGamma(),
        task.getThresholdT1(),
        task.getThresholdT2()
        );
        return Result.success(created);
    }

    @ApiOperation(value = "启动分析任务", notes = "需通过任务访问权限校验；启动后任务进入运行状态")
    @PostMapping("/task/run/{taskId}")
    public Result<String> runTask(@PathVariable Long taskId) throws Exception {
        analysisService.checkTaskAccess(taskId);
        analysisService.runAnalysis(taskId);
        return Result.success("任务已启动");
    }

    /** 历史任务分页查询（FR-PLAT-002） */
    @ApiOperation(value = "分页查询历史任务", notes = "FR-PLAT-002 历史任务分页查询；需通过项目归属权限校验")
    @GetMapping("/task/list/{projectId}")
    public Result<IPage<AnalysisTask>> listTasks(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(analysisService.listTasks(projectId, pageNum, pageSize));
    }

    @ApiOperation(value = "查询任务详情", notes = "需通过任务访问权限校验")
    @GetMapping("/task/{taskId}")
    public Result<AnalysisTask> getTask(@PathVariable Long taskId) {
        analysisService.checkTaskAccess(taskId);
        return Result.success(analysisService.getTask(taskId));
    }

    /** 重新分析：复用原任务配置创建新任务并立即执行（FR-PLAT-002 结果复现/重新分析） */
    @ApiOperation(value = "重新分析任务", notes = "FR-PLAT-002 结果复现/重新分析：复用原任务配置创建新任务并立即执行；需通过任务访问权限校验")
    @PostMapping("/task/rerun/{taskId}")
    public Result<AnalysisTask> rerunTask(@PathVariable Long taskId) throws Exception {
        analysisService.checkTaskAccess(taskId);
        AnalysisTask created = analysisService.rerunTask(taskId);
        analysisService.runAnalysis(created.getId());
        return Result.success(created);
    }

    /** 暂停运行中的分析任务 */
    @ApiOperation(value = "暂停分析任务", notes = "暂停运行中的分析任务；需通过任务访问权限校验")
    @PostMapping("/task/pause/{taskId}")
    public Result<Void> pauseTask(@PathVariable Long taskId) throws Exception {
        analysisService.checkTaskAccess(taskId);
        analysisService.pauseTask(taskId);
        return Result.success();
    }

    /** 恢复已暂停的分析任务 */
    @ApiOperation(value = "恢复分析任务", notes = "恢复已暂停的任务继续执行；需通过任务访问权限校验")
    @PostMapping("/task/resume/{taskId}")
    public Result<Void> resumeTask(@PathVariable Long taskId) throws Exception {
        analysisService.checkTaskAccess(taskId);
        analysisService.resumeTask(taskId);
        return Result.success();
    }

    /** 终止分析任务 */
    @ApiOperation(value = "终止分析任务", notes = "终止任务执行；需通过任务访问权限校验")
    @PostMapping("/task/terminate/{taskId}")
    public Result<Void> terminateTask(@PathVariable Long taskId) throws Exception {
        analysisService.checkTaskAccess(taskId);
        analysisService.terminateTask(taskId);
        return Result.success();
    }
}
