package com.traceguard.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.common.DefectStatus;
import com.traceguard.entity.Defect;
import com.traceguard.mapper.DefectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GAP-009 入站同步（远程->本地）：定时轮询已推送缺陷在第三方平台的状态变化并回写本地缺陷状态。
 * 设计要点：
 * - 仅处理 remote_issue_key 非空的缺陷（已推送到 Jira/禅道）
 * - 根据 remote_issue_key 前缀判断平台（bug-/story- 为禅道，其余为 Jira）
 * - 查询远程状态后映射为本地统一状态，若与本地不一致则调用 ResultService 走合法流转回写
 * - 旁路容错：单条失败不影响整体；受 IntegrationProperties.inboundSync 配置控制
 */
@Slf4j
@Service
public class IssueInboundSyncService {

    @Autowired(required = false)
    private JiraClient jiraClient;
    @Autowired(required = false)
    private ZentaoClient zentaoClient;
    @Autowired
    private DefectMapper defectMapper;
    @Autowired
    private com.traceguard.service.ResultService resultService;
    @Autowired
    private IntegrationProperties properties;

    /** 最近一次同步结果（供接口查询，线程安全） */
    private final AtomicLong lastRunTime = new AtomicLong(0);
    private final Map<String, Object> lastRunStats = new ConcurrentHashMap<>();

    /**
     * 定时轮询（由配置 cron 控制；enabled=false 时直接跳过）
     */
    @Scheduled(cron = "${traceguard.integration.inbound-sync.cron:0 */5 * * * *}")
    public void scheduledSync() {
        if (!properties.getInboundSync().isEnabled()) {
            return;
        }
        if (!isAnyClientEnabled()) {
            return;
        }
        doSync();
    }

    private boolean isAnyClientEnabled() {
        return (jiraClient != null && jiraClient.enabled())
                || (zentaoClient != null && zentaoClient.enabled());
    }

    /**
     * 手动触发同步（Controller 调用）
     */
    public Map<String, Object> triggerManualSync() {
        if (!isAnyClientEnabled()) {
            throw new com.traceguard.common.BusinessException(400, "入站同步未启用：Jira/禅道集成均未启用");
        }
        return doSync();
    }

    /**
     * 执行同步核心逻辑
     */
    public Map<String, Object> doSync() {
        long start = System.currentTimeMillis();
        int scanned = 0;
        int changed = 0;
        int failed = 0;

        // 查所有已推送（remote_issue_key 非空）的缺陷
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(Defect::getRemoteIssueKey);
        wrapper.ne(Defect::getRemoteIssueKey, "");
        List<Defect> defects = defectMapper.selectList(wrapper);

        int batchSize = properties.getInboundSync().getBatchSize();
        List<Defect> batch = new ArrayList<>();
        for (Defect defect : defects) {
            batch.add(defect);
            if (batch.size() >= batchSize) {
                // 分批处理（防止单次拉取过多）
                scanned += processBatch(batch);
                changed += lastChanged;
                failed += lastFailed;
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            scanned += processBatch(batch);
            changed += lastChanged;
            failed += lastFailed;
        }

        long cost = System.currentTimeMillis() - start;
        Map<String, Object> stats = Map.of(
                "total", defects.size(),
                "scanned", scanned,
                "changed", changed,
                "failed", failed,
                "costMs", cost,
                "timestamp", LocalDateTime.now().toString()
        );
        lastRunTime.set(System.currentTimeMillis());
        lastRunStats.clear();
        lastRunStats.putAll(stats);
        log.info("[入站同步] 完成：扫描{} 变更{} 失败{} 耗时{}ms", defects.size(), changed, failed, cost);
        return stats;
    }

    private int lastChanged = 0;
    private int lastFailed = 0;

    /**
     * 处理一批缺陷：查询远程状态，必要时回写本地
     * @return 处理的缺陷数
     */
    private int processBatch(List<Defect> batch) {
        int processed = 0;
        lastChanged = 0;
        lastFailed = 0;
        for (Defect defect : batch) {
            processed++;
            try {
                RemoteIssueStatus remote = fetchStatus(defect.getRemoteIssueKey());
                if (remote == null || remote.getStatus() == null) {
                    continue;
                }
                String localTarget = remote.getStatus();
                String current = defect.getStatus() != null ? defect.getStatus() : DefectStatus.PENDING.getCode();
                if (current.equalsIgnoreCase(localTarget)) {
                    continue; // 无变化
                }
                // 合法流转校验：若远程状态无法合法流转到，则跳过（不强行覆盖）
                if (!DefectStatus.isValid(localTarget)
                        || !DefectStatus.canTransition(current, localTarget)) {
                    log.debug("[入站同步] 跳过非法流转：{} -> {} (缺陷 {})", current, localTarget, defect.getId());
                    continue;
                }
                // 回写本地（走统一流转方法，含审计日志）
                resultService.updateDefectStatus(defect.getId(), localTarget, 0L);
                lastChanged++;
                log.info("[入站同步] 缺陷 {} 状态回写：{} -> {} (远程 {})",
                        defect.getId(), current, localTarget, defect.getRemoteIssueKey());
            } catch (Exception e) {
                lastFailed++;
                log.warn("[入站同步] 处理缺陷 {} 失败：{}", defect.getId(), e.getMessage());
            }
        }
        return processed;
    }

    /**
     * 根据 remote_issue_key 调对应平台查询状态
     */
    private RemoteIssueStatus fetchStatus(String remoteKey) {
        if (remoteKey.startsWith("bug-") || remoteKey.startsWith("story-")) {
            return (zentaoClient != null && zentaoClient.enabled())
                    ? zentaoClient.fetchRemoteStatus(remoteKey) : null;
        }
        return (jiraClient != null && jiraClient.enabled())
                ? jiraClient.fetchRemoteStatus(remoteKey) : null;
    }

    /**
     * 查询最近一次同步状态（含启用开关与 cron 配置回显，供前端展示）
     */
    public Map<String, Object> getLastRunStats() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", properties.getInboundSync().isEnabled());
        result.put("cron", properties.getInboundSync().getCron());
        if (lastRunStats.isEmpty()) {
            result.put("lastRun", "never");
            result.put("message", "尚未执行过入站同步");
        } else {
            result.putAll(lastRunStats);
        }
        return result;
    }
}
