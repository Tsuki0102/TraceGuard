package com.traceguard.controller;

import com.traceguard.common.BusinessException;
import com.traceguard.common.Result;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.integration.*;
import com.traceguard.integration.IssuePayload.IssueType;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ResultService;
import com.traceguard.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GAP-009：第三方工具集成控制器
 * 暴露REST接口：连通测试、需求导出、缺陷推送
 */
@Slf4j
@RestController
@RequestMapping("/integration")
@RequiredArgsConstructor
public class IntegrationController {
    
    private final ResultService resultService;
    private final IntegrationProperties properties;
    private final ProjectService projectService;
    // GAP-009：各客户端为条件装配 Bean（enabled=false 时不创建），需可选注入，null 由 getToolClient 兜底
    @Autowired(required = false)
    private JiraClient jiraClient;
    @Autowired(required = false)
    private ZentaoClient zentaoClient;
    @Autowired(required = false)
    private WeComClient wecomClient;
    @Autowired(required = false)
    private DingTalkClient dingtalkClient;
    @Autowired(required = false)
    private IssueInboundSyncService inboundSyncService;

    /**
     * SEC-04：管理员校验（连通测试/通知发送可触发外部请求与内网探测，仅管理员）
     */
    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作集成配置与通知");
        }
        return null;
    }

    /**
     * 连通测试（JIRA/ZENTAO 验证 REST 可达；WECOM/DINGTALK 发送一条测试消息）；仅管理员（SEC-04）
     * @param tool 工具类型
     */
    @PostMapping("/test")
    public ResponseEntity<Result<ConnectivityResult>> testConnection(@RequestParam ToolType tool) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return ResponseEntity.ok(Result.error(denied.getCode(), denied.getMessage()));
        ToolClient client = getToolClient(tool);
        ConnectivityResult result = client.testConnection();
        return ResponseEntity.ok(Result.success(result));
    }

    /**
     * 发送 Webhook 通知（企业微信 / 钉钉机器人，仅限 WECOM/DINGTALK）；仅管理员（SEC-04）
     * @param tool 工具类型
     * @param message 通知消息体
     */
    @PostMapping("/notify")
    public ResponseEntity<Result<Boolean>> notify(@RequestParam ToolType tool,
                                                  @RequestBody NotificationMessage message) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return ResponseEntity.ok(Result.error(denied.getCode(), denied.getMessage()));
        WebhookClient client = getWebhookClient(tool);
        if (!client.enabled()) {
            throw new BusinessException(400, tool + "集成未启用");
        }
        boolean ok = client.sendMessage(message);
        if (!ok) {
            throw new BusinessException(502, "通知发送失败，请检查机器人配置与网络");
        }
        return ResponseEntity.ok(Result.success(true));
    }

    /**
     * 需求变更联动：把已导出需求的最新内容同步到远程 issue（幂等 update，仅 JIRA/ZENTAO）。
     * 本地需求内容变更后调用，远程 issue 的标题/描述更新为最新需求信息。
     * @param projectId 项目ID（用于权限与审计口径，与导出接口一致）
     * @param requirementId 需求ID
     * @param tool 工具类型（JIRA/ZENTAO）
     */
    @PutMapping("/requirements/sync/{requirementId}")
    public ResponseEntity<Result<IssueRef>> syncRequirement(
            @RequestParam Long projectId,
            @PathVariable Long requirementId,
            @RequestParam ToolType tool) {
        // 细粒度数据权限：非管理员仅可操作自己项目下的数据
        projectService.checkOwnership(projectId);
        ProjectToolClient client = getProjectToolClient(tool);
        if (!client.enabled()) {
            throw new BusinessException(400, tool + "集成未启用");
        }
        Requirement req = resultService.getRequirementById(requirementId);
        if (req == null) {
            throw new BusinessException(404, "需求不存在");
        }
        if (req.getRemoteIssueKey() == null || req.getRemoteIssueKey().isEmpty()) {
            throw new BusinessException(400, "该需求尚未导出到" + tool + "，无法同步（请先在需求列表执行导出）");
        }
        IssuePayload payload = buildRequirementPayload(req);
        IssueRef ref = client.updateIssue(req.getRemoteIssueKey(), payload);
        log.info("[GAP-009] 需求变更联动：{} {} -> {} (update {})", tool, requirementId, req.getRemoteIssueKey(), ref.getRemoteKey());
        return ResponseEntity.ok(Result.success(ref));
    }
    
    /**
     * 需求导出为issue
     * @param projectId 项目ID
     * @param requirementIds 需求ID列表
     * @param tool 工具类型
     */
    @PostMapping("/requirements/export")
    public ResponseEntity<List<IssueRef>> exportRequirements(
            @RequestParam Long projectId,
            @RequestBody List<Long> requirementIds,
            @RequestParam ToolType tool) {
        // 细粒度数据权限：非管理员仅可操作自己项目下的数据
        projectService.checkOwnership(projectId);
        ProjectToolClient client = getProjectToolClient(tool);
        if (!client.enabled()) {
            throw new BusinessException(400, tool + "集成未启用");
        }
        
        List<IssueRef> results = new ArrayList<>();
        for (Long reqId : requirementIds) {
            Requirement req = resultService.getRequirementById(reqId);
            if (req == null) continue;
            
            IssuePayload payload = buildRequirementPayload(req);
            
            // 幂等：remote_issue_key已存在则走update
            IssueRef ref;
            if (req.getRemoteIssueKey() != null && !req.getRemoteIssueKey().isEmpty()) {
                ref = client.updateIssue(req.getRemoteIssueKey(), payload);
            } else {
                ref = client.createIssue(payload);
                // 回写remote_issue_key
                req.setRemoteIssueKey(ref.getRemoteKey());
                resultService.updateRequirementRemoteKey(req);
            }
            results.add(ref);
        }
        
        return ResponseEntity.ok(results);
    }
    
    /**
     * 缺陷推送
     * @param projectId 项目ID
     * @param defectIds 缺陷ID列表
     * @param tool 工具类型
     */
    @PostMapping("/defects/push")
    public ResponseEntity<List<IssueRef>> pushDefects(
            @RequestParam Long projectId,
            @RequestBody List<Long> defectIds,
            @RequestParam ToolType tool) {
        // 细粒度数据权限：非管理员仅可操作自己项目下的数据
        projectService.checkOwnership(projectId);
        ProjectToolClient client = getProjectToolClient(tool);
        if (!client.enabled()) {
            throw new BusinessException(400, tool + "集成未启用");
        }
        
        List<IssueRef> results = new ArrayList<>();
        for (Long defectId : defectIds) {
            Defect defect = resultService.getDefectById(defectId);
            if (defect == null) continue;
            
            IssuePayload payload = buildDefectPayload(defect);
            
            // 幂等：remote_issue_key已存在则走update
            IssueRef ref;
            if (defect.getRemoteIssueKey() != null && !defect.getRemoteIssueKey().isEmpty()) {
                ref = client.updateIssue(defect.getRemoteIssueKey(), payload);
            } else {
                ref = client.createIssue(payload);
                // 回写remote_issue_key
                defect.setRemoteIssueKey(ref.getRemoteKey());
                resultService.updateDefectRemoteKey(defect);
            }
            results.add(ref);
        }
        
        return ResponseEntity.ok(results);
    }
    
    /**
     * 集成状态总览（前端连通测试页/演示用）：返回各工具是否已启用及配置摘要。
     * 不触发真实网络请求，仅读取本地配置；含凭据配置摘要，仅管理员可见（SEC-04）。
     */
    @GetMapping("/status")
    public ResponseEntity<Result<List<ConnectivityResult>>> status() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return ResponseEntity.ok(Result.error(denied.getCode(), denied.getMessage()));
        List<ConnectivityResult> list = new ArrayList<>();
        ToolType[] all = ToolType.values();
        for (ToolType tool : all) {
            ToolClient client = safeGetToolClient(tool);
            boolean enabled = client != null && client.enabled();
            ConnectivityResult.ConnectivityResultBuilder b = ConnectivityResult.builder()
                    .toolType(tool)
                    .reachable(enabled)
                    .message(enabled ? "已启用" : "未启用（未配置环境变量或 enabled=false）");
            switch (tool) {
                case JIRA -> {
                    String baseUrl = properties.getJira().getBaseUrl();
                    String projectKey = properties.getJira().getProjectKey();
                    String token = properties.getJira().getToken();
                    b.baseUrl(isPlaceholderOrEmpty(baseUrl) ? null : baseUrl)
                     .projectKey(isPlaceholderOrEmpty(projectKey) ? null : projectKey)
                     .tokenConfigured(!isPlaceholderOrEmpty(token));
                }
                case ZENTAO -> {
                    String baseUrl = properties.getZentao().getBaseUrl();
                    String productId = properties.getZentao().getProductId();
                    String token = properties.getZentao().getToken();
                    b.baseUrl(isPlaceholderOrEmpty(baseUrl) ? null : baseUrl)
                     .projectKey(isPlaceholderOrEmpty(productId) ? null : productId)
                     .tokenConfigured(!isPlaceholderOrEmpty(token));
                }
                case WECOM -> {
                    String baseUrl = properties.getWecom().getBaseUrl();
                    String key = properties.getWecom().getKey();
                    b.baseUrl(isPlaceholderOrEmpty(baseUrl) ? null : baseUrl)
                     .projectKey(isPlaceholderOrEmpty(key) ? null : key)
                     .tokenConfigured(!isPlaceholderOrEmpty(key));
                }
                case DINGTALK -> {
                    String baseUrl = properties.getDingtalk().getBaseUrl();
                    String token = properties.getDingtalk().getAccessToken();
                    b.baseUrl(isPlaceholderOrEmpty(baseUrl) ? null : baseUrl)
                     .projectKey(isPlaceholderOrEmpty(token) ? null : token)
                     .tokenConfigured(!isPlaceholderOrEmpty(token));
                }
            }
            list.add(b.build());
        }
        return ResponseEntity.ok(Result.success(list));
    }

    /**
     * 手动触发入站同步（远程->本地）：拉取已推送缺陷在第三方平台的状态变化并回写本地。
     * 定时任务默认每5分钟自动执行（配置 traceguard.integration.inbound-sync.enabled=true 启用）。
     */
    @PostMapping("/sync/inbound")
    public ResponseEntity<Result<Map<String, Object>>> triggerInboundSync() {
        // 入站同步操作第三方平台凭据与全局推送数据，仅管理员可触发
        if (!UserContext.isAdmin()) {
            return ResponseEntity.ok(Result.error(403, "无权限：仅管理员可触发入站同步"));
        }
        Map<String, Object> stats = inboundSyncService.triggerManualSync();
        return ResponseEntity.ok(Result.success(stats));
    }

    /**
     * 查询入站同步最近一次执行结果
     */
    @GetMapping("/sync/inbound/status")
    public ResponseEntity<Result<Map<String, Object>>> inboundSyncStatus() {
        // 入站同步状态含第三方平台凭据与全局推送数据，仅管理员可查看
        if (!UserContext.isAdmin()) {
            return ResponseEntity.ok(Result.error(403, "无权限：仅管理员可查看入站同步状态"));
        }
        return ResponseEntity.ok(Result.success(inboundSyncService.getLastRunStats()));
    }

    /**
     * 判断字符串是否为空、或仍是未替换的占位符（如 ${JIRA_BASE_URL:}）
     */
    private static boolean isPlaceholderOrEmpty(String s) {
        if (s == null || s.isEmpty()) return true;
        // Spring 占位符未解析时会保留 ${XXX:default} 形式
        return s.startsWith("${") && s.endsWith("}");
    }

    /**
     * 获取工具客户端（失败返回 null，而非抛异常）
     */
    private ToolClient safeGetToolClient(ToolType tool) {
        try {
            return getToolClient(tool);
        } catch (BusinessException e) {
            return null;
        }
    }

    /**
     * 获取工具客户端（统一：项目管理工具 + Webhook 机器人）
     */
    private ToolClient getToolClient(ToolType tool) {
        return switch (tool) {
            case JIRA -> {
                if (jiraClient == null) {
                    throw new BusinessException(400, "Jira集成未启用");
                }
                yield jiraClient;
            }
            case ZENTAO -> {
                if (zentaoClient == null) {
                    throw new BusinessException(400, "禅道集成未启用");
                }
                yield zentaoClient;
            }
            case WECOM -> {
                if (wecomClient == null) {
                    throw new BusinessException(400, "企业微信集成未启用");
                }
                yield wecomClient;
            }
            case DINGTALK -> {
                if (dingtalkClient == null) {
                    throw new BusinessException(400, "钉钉集成未启用");
                }
                yield dingtalkClient;
            }
        };
    }

    /**
     * 获取项目管理工具客户端（需求导出/缺陷推送，仅 JIRA/ZENTAO）
     */
    private ProjectToolClient getProjectToolClient(ToolType tool) {
        if (tool != ToolType.JIRA && tool != ToolType.ZENTAO) {
            throw new BusinessException(400, "工具 " + tool + " 不支持需求导出/缺陷推送（仅 Jira/禅道）");
        }
        return (ProjectToolClient) getToolClient(tool);
    }

    /**
     * 获取 Webhook 通知客户端（仅 WECOM/DINGTALK）
     */
    private WebhookClient getWebhookClient(ToolType tool) {
        if (tool != ToolType.WECOM && tool != ToolType.DINGTALK) {
            throw new BusinessException(400, "工具 " + tool + " 不支持 Webhook 通知（仅企业微信/钉钉）");
        }
        return (WebhookClient) getToolClient(tool);
    }
    
    /**
     * 构建需求IssuePayload
     */
    private IssuePayload buildRequirementPayload(Requirement req) {
        StringBuilder desc = new StringBuilder();
        desc.append("需求编码：").append(req.getRequirementId()).append("\n\n");
        desc.append(req.getOriginalText());
        
        return IssuePayload.builder()
                .type(IssueType.STORY)
                .title(req.getRequirementId() + " " + (req.getOriginalText() != null ? 
                        req.getOriginalText().substring(0, Math.min(50, req.getOriginalText().length())) : ""))
                .description(desc.toString())
                .build();
    }
    
    /**
     * 构建缺陷IssuePayload
     */
    private IssuePayload buildDefectPayload(Defect defect) {
        StringBuilder desc = new StringBuilder();
        desc.append("缺陷类型：").append(defect.getDefectType());
        if (defect.getSubType() != null) {
            desc.append("（").append(defect.getSubType()).append("）");
        }
        desc.append("\n");
        desc.append("严重程度：").append(defect.getDefectLevel()).append("\n\n");
        desc.append("原因：\n").append(defect.getDefectReason()).append("\n\n");
        desc.append("修复建议：\n").append(defect.getRepairSuggestion());
        
        return IssuePayload.builder()
                .type(IssueType.BUG)
                .title(defect.getDefectType() + " - " + 
                        (defect.getDefectReason() != null ? 
                                defect.getDefectReason().substring(0, Math.min(50, defect.getDefectReason().length())) : ""))
                .description(desc.toString())
                .priority(mapDefectLevelToPriority(defect.getDefectLevel()))
                .relatedReqCode(defect.getRequirementId() != null ? 
                        String.valueOf(defect.getRequirementId()) : null)
                .defectType(defect.getDefectType())
                .subType(defect.getSubType())
                .build();
    }
    
    /**
     * 缺陷严重程度映射到优先级
     */
    private String mapDefectLevelToPriority(String level) {
        if (level == null) return "MEDIUM";
        return switch (level.toLowerCase()) {
            case "serious" -> "HIGHEST";
            case "general" -> "HIGH";
            case "minor" -> "MEDIUM";
            case "suggestion" -> "LOW";
            default -> "MEDIUM";
        };
    }
}