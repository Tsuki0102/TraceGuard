package com.traceguard.controller;

import com.traceguard.common.BusinessException;
import com.traceguard.common.Result;
import com.traceguard.entity.Defect;
import com.traceguard.service.InsightService;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ResultService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 质量洞察接口（W5 波）：跨项目缺陷趋势 / 缺陷工单看板 / 组合简报 / 缺陷模式库。
 * 全部需登录；数据隔离在 InsightService 内按角色执行。
 */
@RestController
@RequestMapping("/insight")
@Api(tags = "12-质量洞察")
public class InsightController {

    @Autowired
    private InsightService insightService;

    @Autowired
    private ResultService resultService;

    @Autowired
    private ProjectService projectService;

    @ApiOperation(value = "缺陷趋势", notes = "GET /insight/trend?days=30&projectId=；按天 严重/一般 缺陷数 + 类型 Top")
    @GetMapping("/trend")
    public Result trend(@RequestParam(defaultValue = "30") int days,
                        @RequestParam(required = false) Long projectId) {
        return Result.success(insightService.trend(days, projectId));
    }

    @ApiOperation(value = "缺陷工单看板", notes = "GET /insight/board；按状态汇总 + 分页明细（带项目名），支持 projectId/status/level/q 过滤")
    @GetMapping("/board")
    public Result board(@RequestParam(defaultValue = "1") int pageNum,
                        @RequestParam(defaultValue = "50") int pageSize,
                        @RequestParam(required = false) Long projectId,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String level,
                        @RequestParam(required = false) String q) {
        return Result.success(insightService.board(pageNum, pageSize, projectId, status, level, q));
    }

    @ApiOperation(value = "更新缺陷工单状态", notes = "PUT /insight/defect/{id}/status body:{\"status\":\"processing\"}；含合法流转校验与审计")
    @PutMapping("/defect/{id}/status")
    public Result<Defect> updateDefectStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Defect defect = resultService.getDefectById(id);
        if (defect == null) {
            throw new BusinessException(404, "缺陷不存在");
        }
        projectService.checkOwnership(defect.getProjectId());
        String status = body.get("status");
        if (status == null || status.isEmpty()) {
            throw new BusinessException(400, "status 参数不能为空");
        }
        return Result.success(resultService.updateDefectStatus(id, status, UserContext.getUserId()));
    }

    @ApiOperation(value = "组合质量简报", notes = "GET /insight/portfolio；可见项目的覆盖/缺陷/未决指标 + 全组合汇总")
    @GetMapping("/portfolio")
    public Result portfolio() {
        return Result.success(insightService.portfolio());
    }

    @ApiOperation(value = "缺陷模式库", notes = "GET /insight/patterns；代码检测规则图鉴 + 真实命中统计 + 最近命中示例")
    @GetMapping("/patterns")
    public Result patterns() {
        return Result.success(insightService.patterns());
    }

    // ==================== W6 二期：阈值实验室 / Alloy 工作台 / 评测中心 / 样例导入 ====================

    @ApiOperation(value = "阈值重放", notes = "GET /insight/threshold/replay?projectId=&alpha=&beta=&gamma=&t1=&t2=；库内重算判定桶与翻转明细")
    @GetMapping("/threshold/replay")
    public Result replay(@RequestParam Long projectId,
                         @RequestParam(required = false) Double alpha,
                         @RequestParam(required = false) Double beta,
                         @RequestParam(required = false) Double gamma,
                         @RequestParam(required = false) Double t1,
                         @RequestParam(required = false) Double t2) {
        return Result.success(insightService.replayThresholds(projectId, alpha, beta, gamma, t1, t2));
    }

    @ApiOperation(value = "T1 阈值扫描", notes = "GET /insight/threshold/sweep?projectId=；0.60→0.95 各档判定桶分布")
    @GetMapping("/threshold/sweep")
    public Result sweep(@RequestParam Long projectId) {
        return Result.success(insightService.thresholdSweep(projectId));
    }

    @ApiOperation(value = "Alloy 规约清单", notes = "GET /insight/alloy/specs?projectId=&limit=；规约 + 需求摘要 + 校验状态统计")
    @GetMapping("/alloy/specs")
    public Result alloySpecs(@RequestParam Long projectId, @RequestParam(defaultValue = "50") int limit) {
        return Result.success(insightService.alloySpecs(projectId, limit));
    }

    @ApiOperation(value = "Alloy 在线试算校验", notes = "POST /insight/alloy/verify/{specId}；真实求解器试算，不写档案状态")
    @PostMapping("/alloy/verify/{specId}")
    public Result verifyAlloy(@PathVariable Long specId) {
        return Result.success(insightService.verifyAlloySpec(specId));
    }

    @ApiOperation(value = "标注资产盘点", notes = "GET /insight/eval/assets；samples 样例需求行数 + 标注缺陷类别分布")
    @GetMapping("/eval/assets")
    public Result evalAssets() {
        return Result.success(insightService.evalAssets());
    }

    @ApiOperation(value = "样例一键导入", notes = "POST /insight/samples/import body:{\"key\":\"api-service\",\"projectName\":\"可选\"}；创建独立项目并落盘解压")
    @PostMapping("/samples/import")
    public Result importSample(@RequestBody Map<String, String> body) {
        return Result.success(insightService.importSample(body.get("key"), body.get("projectName")));
    }
}
