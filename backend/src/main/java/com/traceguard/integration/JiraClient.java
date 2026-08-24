package com.traceguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.integration.IssuePayload.IssueType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * GAP-009：Jira REST API实现
 * 遵循"开关+超时+熔断+兜底"四要素
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "traceguard.integration.jira", name = "enabled", havingValue = "true")
public class JiraClient implements ProjectToolClient {
    
    private final RestTemplate restTemplate;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 熔断状态（任务级，内存实现）
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenTime = 0;
    private volatile int failureCount = 0;
    
    @Override
    public ToolType type() {
        return ToolType.JIRA;
    }
    
    @Override
    public boolean enabled() {
        return properties.getJira().isEnabled();
    }
    
    /**
     * 连通性测试：GET /rest/api/2/myself
     */
    @Override
    public ConnectivityResult testConnection() {
        try {
            String url = String.format("%s/rest/api/2/myself", properties.getJira().getBaseUrl());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()), String.class);
            
            JsonNode user = objectMapper.readTree(response.getBody());
            String displayName = user.has("displayName") ? user.get("displayName").asText() : "";
            
            return ConnectivityResult.builder()
                    .toolType(ToolType.JIRA)
                    .reachable(true)
                    .message("200 OK")
                    .version(displayName)
                    .build();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ConnectivityResult.builder()
                        .toolType(ToolType.JIRA)
                        .reachable(false)
                        .message("认证失败，请检查用户名和Token")
                        .build();
            }
            return ConnectivityResult.builder()
                    .toolType(ToolType.JIRA)
                    .reachable(false)
                    .message(e.getMessage())
                    .build();
        } catch (Exception e) {
            return ConnectivityResult.builder()
                    .toolType(ToolType.JIRA)
                    .reachable(false)
                    .message(e.getMessage())
                    .build();
        }
    }
    
    /**
     * 新建issue：POST /rest/api/2/issue
     */
    @Override
    public IssueRef createIssue(IssuePayload payload) {
        checkCircuit();
        
        try {
            String url = String.format("%s/rest/api/2/issue", properties.getJira().getBaseUrl());
            
            Map<String, Object> issueData = buildIssuePayload(payload);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(issueData),
                    createAuthHeaders());
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());
            String key = result.get("key").asText();
            String issueUrl = String.format("%s/browse/%s", properties.getJira().getBaseUrl(), key);
            
            resetFailureCount();
            log.info("Jira issue created: {}", key);
            
            return IssueRef.builder()
                    .remoteKey(key)
                    .remoteUrl(issueUrl)
                    .created(true)
                    .build();
        } catch (Exception e) {
            recordFailure(e);
            throw new RuntimeException("Jira createIssue failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新issue：PUT /rest/api/2/issue/{key}
     */
    @Override
    public IssueRef updateIssue(String remoteKey, IssuePayload payload) {
        checkCircuit();
        
        try {
            String url = String.format("%s/rest/api/2/issue/%s", properties.getJira().getBaseUrl(), remoteKey);
            
            Map<String, Object> issueData = buildIssuePayload(payload);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(issueData),
                    createAuthHeaders());
            
            restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            String issueUrl = String.format("%s/browse/%s", properties.getJira().getBaseUrl(), remoteKey);
            
            resetFailureCount();
            log.info("Jira issue updated: {}", remoteKey);
            
            return IssueRef.builder()
                    .remoteKey(remoteKey)
                    .remoteUrl(issueUrl)
                    .created(false)
                    .build();
        } catch (Exception e) {
            recordFailure(e);
            throw new RuntimeException("Jira updateIssue failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 状态流转：POST /rest/api/2/issue/{key}/transitions
     * 本地状态 -> Jira目标映射：
     * - processing -> 进行中类流转
     * - resolved -> 已解决/Done
     * - ignored -> Won't Fix
     */
    @Override
    public void transitionStatus(String remoteKey, RemoteStatus status) {
        checkCircuit();
        
        try {
            // 先获取可用的transitions
            String transitionsUrl = String.format("%s/rest/api/2/issue/%s/transitions",
                    properties.getJira().getBaseUrl(), remoteKey);
            ResponseEntity<String> transitionsResp = restTemplate.exchange(transitionsUrl, HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()), String.class);
            JsonNode transitions = objectMapper.readTree(transitionsResp.getBody()).get("transitions");
            
            // 根据状态查找对应的transition ID
            String transitionId = findTransitionId(transitions, status);
            if (transitionId == null) {
                log.warn("Jira transition not found for status: {}", status.getStatus());
                return;
            }
            
            // 执行流转
            Map<String, Object> transitionData = new HashMap<>();
            Map<String, String> transition = new HashMap<>();
            transition.put("id", transitionId);
            transitionData.put("transition", transition);
            
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(transitionData),
                    createAuthHeaders());
            restTemplate.postForEntity(transitionsUrl, entity, String.class);
            
            resetFailureCount();
            log.info("Jira issue {} transitioned to {}", remoteKey, status.getStatus());
        } catch (Exception e) {
            recordFailure(e);
            log.error("Jira transitionStatus failed for {}: {}", remoteKey, e.getMessage());
        }
    }
    
    /**
     * 查询远程issue当前状态（入站同步轮询用）
     * GET /rest/api/2/issue/{key}?fields=status
     */
    @Override
    public RemoteIssueStatus fetchRemoteStatus(String remoteKey) {
        try {
            String url = String.format("%s/rest/api/2/issue/%s?fields=status",
                    properties.getJira().getBaseUrl(), remoteKey);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode statusNode = root.path("fields").path("status");
            String raw = statusNode.path("name").asText(null);
            if (raw == null) return null;
            return RemoteIssueStatus.builder()
                    .rawStatus(raw)
                    .status(mapJiraStatusToLocal(raw))
                    .description(raw)
                    .build();
        } catch (Exception e) {
            log.warn("Jira fetchRemoteStatus failed for {}: {}", remoteKey, e.getMessage());
            return null;
        }
    }

    /**
     * Jira 原生状态名 -> 本地统一状态（pending/processing/resolved/ignored）
     */
    private String mapJiraStatusToLocal(String raw) {
        return switch (raw.toLowerCase()) {
            case "to do", "open", "待办", "新建" -> "pending";
            case "in progress", "进行中" -> "processing";
            case "done", "resolved", "fixed", "closed", "已解决", "完成", "关闭" -> "resolved";
            case "won't fix", "wont fix", "cancelled", "canceled", "不予解决", "已取消" -> "ignored";
            default -> "pending";
        };
    }

    /**
     * 创建认证请求头（Basic Auth）
     */
    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String auth = properties.getJira().getUsername() + ":" + properties.getJira().getToken();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.ISO_8859_1));
        headers.set("Authorization", "Basic " + encodedAuth);
        return headers;
    }
    
    /**
     * 构建Jira issue请求体
     */
    private Map<String, Object> buildIssuePayload(IssuePayload payload) {
        Map<String, Object> issueData = new HashMap<>();
        Map<String, Object> fields = new HashMap<>();
        
        // 项目
        Map<String, String> project = new HashMap<>();
        project.put("key", properties.getJira().getProjectKey());
        fields.put("project", project);
        
        // 摘要
        fields.put("summary", payload.getTitle());
        
        // 描述（纯文本）
        fields.put("description", payload.getDescription());
        
        // Issue Type
        Map<String, String> issueType = new HashMap<>();
        issueType.put("name", payload.getType() == IssueType.STORY ? "Story" : "Bug");
        fields.put("issuetype", issueType);
        
        // 优先级映射
        if (payload.getPriority() != null) {
            Map<String, String> priority = new HashMap<>();
            priority.put("name", mapPriority(payload.getPriority()));
            fields.put("priority", priority);
        }
        
        issueData.put("fields", fields);
        return issueData;
    }
    
    /**
     * 优先级映射
     */
    private String mapPriority(String priority) {
        return switch (priority.toUpperCase()) {
            case "HIGHEST" -> "Highest";
            case "HIGH" -> "High";
            case "MEDIUM" -> "Medium";
            case "LOW" -> "Low";
            case "LOWEST" -> "Lowest";
            default -> "Medium";
        };
    }
    
    /**
     * 查找状态对应的transition ID
     */
    private String findTransitionId(JsonNode transitions, RemoteStatus status) {
        String targetName = switch (status.getStatus()) {
            case "IN_PROGRESS" -> "In Progress";
            case "RESOLVED" -> "Done";
            case "IGNORED" -> "Won't Fix";
            default -> null;
        };
        
        if (targetName == null) return null;
        
        for (JsonNode transition : transitions) {
            String name = transition.get("name").asText();
            if (name.equalsIgnoreCase(targetName)) {
                return transition.get("id").asText();
            }
        }
        return null;
    }
    
    /**
     * 检查熔断状态
     */
    private void checkCircuit() {
        if (circuitOpen) {
            long elapsed = System.currentTimeMillis() - circuitOpenTime;
            if (elapsed < properties.getCircuitOpenSeconds() * 1000L) {
                throw new RuntimeException("Jira集成暂不可用（熔断中），请稍后重试");
            } else {
                // 半开状态，允许试探
                circuitOpen = false;
            }
        }
    }
    
    /**
     * 记录失败（非401/403）
     */
    private void recordFailure(Exception e) {
        if (e instanceof HttpClientErrorException httpErr) {
            if (httpErr.getStatusCode() == HttpStatus.UNAUTHORIZED || 
                httpErr.getStatusCode() == HttpStatus.FORBIDDEN) {
                // 认证失败不计入熔断
                return;
            }
        }
        failureCount++;
        if (failureCount >= properties.getCircuitFailureThreshold()) {
            circuitOpen = true;
            circuitOpenTime = System.currentTimeMillis();
            log.warn("Jira集成熔断触发，连续失败{}次", failureCount);
        }
    }
    
    /**
     * 重置失败计数
     */
    private void resetFailureCount() {
        failureCount = 0;
        circuitOpen = false;
    }
}