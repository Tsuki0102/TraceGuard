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

import java.util.HashMap;
import java.util.Map;

/**
 * GAP-009：禅道 REST API实现
 * 遵循"开关+超时+熔断+兜底"四要素
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "traceguard.integration.zentao", name = "enabled", havingValue = "true")
public class ZentaoClient implements ProjectToolClient {
    
    private final RestTemplate restTemplate;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 熔断状态（任务级，内存实现）
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenTime = 0;
    private volatile int failureCount = 0;
    
    @Override
    public ToolType type() {
        return ToolType.ZENTAO;
    }
    
    @Override
    public boolean enabled() {
        return properties.getZentao().isEnabled();
    }
    
    /**
     * 连通性测试：GET /api.php/v1/user
     */
    @Override
    public ConnectivityResult testConnection() {
        try {
            String url = String.format("%s/api.php/v1/user", properties.getZentao().getBaseUrl());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()), String.class);
            
            return ConnectivityResult.builder()
                    .toolType(ToolType.ZENTAO)
                    .reachable(true)
                    .message("200 OK")
                    .build();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ConnectivityResult.builder()
                        .toolType(ToolType.ZENTAO)
                        .reachable(false)
                        .message("认证失败，请检查Token")
                        .build();
            }
            return ConnectivityResult.builder()
                    .toolType(ToolType.ZENTAO)
                    .reachable(false)
                    .message(e.getMessage())
                    .build();
        } catch (Exception e) {
            return ConnectivityResult.builder()
                    .toolType(ToolType.ZENTAO)
                    .reachable(false)
                    .message(e.getMessage())
                    .build();
        }
    }
    
    /**
     * 新建issue：
     * - STORY: POST /api.php/v1/products/{id}/stories
     * - BUG: POST /api.php/v1/products/{id}/bugs
     */
    @Override
    public IssueRef createIssue(IssuePayload payload) {
        checkCircuit();
        
        try {
            String path = payload.getType() == IssueType.STORY ? "stories" : "bugs";
            String url = String.format("%s/api.php/v1/products/%s/%s",
                    properties.getZentao().getBaseUrl(), properties.getZentao().getProductId(), path);
            
            Map<String, Object> issueData = buildIssuePayload(payload);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(issueData),
                    createAuthHeaders());
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());
            
            String id = result.has("id") ? result.get("id").asText() : 
                        result.has("data") && result.get("data").has("id") ? 
                        result.get("data").get("id").asText() : "unknown";
            
            String type = payload.getType() == IssueType.STORY ? "story" : "bug";
            String issueUrl = String.format("%s/%s-view-%s.html",
                    properties.getZentao().getBaseUrl(), type, id);
            
            resetFailureCount();
            log.info("Zentao issue created: {}-{}", type, id);
            
            return IssueRef.builder()
                    .remoteKey(type + "-" + id)
                    .remoteUrl(issueUrl)
                    .created(true)
                    .build();
        } catch (Exception e) {
            recordFailure(e);
            throw new RuntimeException("Zentao createIssue failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新issue：PUT /api.php/v1/bugs/{id} 或 /api.php/v1/stories/{id}
     */
    @Override
    public IssueRef updateIssue(String remoteKey, IssuePayload payload) {
        checkCircuit();
        
        try {
            // 解析remoteKey格式：bug-123 或 story-456
            String[] parts = remoteKey.split("-");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid remoteKey format: " + remoteKey);
            }
            String type = parts[0];
            String id = parts[1];
            
            String url = String.format("%s/api.php/v1/%s/%s",
                    properties.getZentao().getBaseUrl(), type.equals("story") ? "stories" : "bugs", id);
            
            Map<String, Object> issueData = buildIssuePayload(payload);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(issueData),
                    createAuthHeaders());
            
            restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            String issueUrl = String.format("%s/%s-view-%s.html",
                    properties.getZentao().getBaseUrl(), type, id);
            
            resetFailureCount();
            log.info("Zentao issue updated: {}", remoteKey);
            
            return IssueRef.builder()
                    .remoteKey(remoteKey)
                    .remoteUrl(issueUrl)
                    .created(false)
                    .build();
        } catch (Exception e) {
            recordFailure(e);
            throw new RuntimeException("Zentao updateIssue failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 状态流转：POST /api.php/v1/bugs/{id}/resolve / close
     * 本地状态 -> 禅道映射：
     * - processing -> bug指派/进行中
     * - resolved -> bug解决
     * - ignored -> bug关闭（不予解决）
     */
    @Override
    public void transitionStatus(String remoteKey, RemoteStatus status) {
        checkCircuit();
        
        try {
            // 仅支持bug状态流转
            if (!remoteKey.startsWith("bug-")) {
                log.warn("Zentao status transition only supported for bugs, got: {}", remoteKey);
                return;
            }
            
            String[] parts = remoteKey.split("-");
            if (parts.length != 2) return;
            String id = parts[1];
            
            String action = switch (status.getStatus()) {
                case "RESOLVED" -> "resolve";
                case "IGNORED" -> "close";
                default -> null;
            };
            
            if (action == null) {
                log.info("Zentao: no transition needed for status {}", status.getStatus());
                return;
            }
            
            String url = String.format("%s/api.php/v1/bugs/%s/%s",
                    properties.getZentao().getBaseUrl(), id, action);
            
            HttpEntity<String> entity = new HttpEntity<>("{}", createAuthHeaders());
            restTemplate.postForEntity(url, entity, String.class);
            
            resetFailureCount();
            log.info("Zentao bug {} transitioned to {}", remoteKey, status.getStatus());
        } catch (Exception e) {
            recordFailure(e);
            log.error("Zentao transitionStatus failed for {}: {}", remoteKey, e.getMessage());
        }
    }
    
    /**
     * 查询远程issue当前状态（入站同步轮询用）
     * 仅支持 bug-（需求在禅道无简单状态流转概念，留作扩展）。
     * GET /api.php/v1/bugs/{id}
     */
    @Override
    public RemoteIssueStatus fetchRemoteStatus(String remoteKey) {
        try {
            if (remoteKey == null || !remoteKey.startsWith("bug-")) {
                log.debug("Zentao fetchRemoteStatus only supported for bug-, got: {}", remoteKey);
                return null;
            }
            String id = remoteKey.substring("bug-".length());
            String url = String.format("%s/api.php/v1/bugs/%s",
                    properties.getZentao().getBaseUrl(), id);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            // 禅道返回 {id, status, ...} 或 {data:{...}}
            JsonNode data = root.has("data") ? root.get("data") : root;
            String raw = data.path("status").asText(null);
            if (raw == null) return null;
            return RemoteIssueStatus.builder()
                    .rawStatus(raw)
                    .status(mapZentaoStatusToLocal(raw))
                    .description(raw)
                    .build();
        } catch (Exception e) {
            log.warn("Zentao fetchRemoteStatus failed for {}: {}", remoteKey, e.getMessage());
            return null;
        }
    }

    /**
     * 禅道原生状态 -> 本地统一状态
     * 禅道 bug 状态：active(激活)/resolved(已解决)/closed(已关闭)
     */
    private String mapZentaoStatusToLocal(String raw) {
        return switch (raw.toLowerCase()) {
            case "active", "open", "unconfirmed" -> "processing";
            case "resolved", "fixing" -> "resolved";
            case "closed" -> "resolved";  // 关闭视为已解决回写
            default -> "processing";
        };
    }

    /**
     * 创建认证请求头（Token）
     */
    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Token", properties.getZentao().getToken());
        return headers;
    }
    
    /**
     * 构建禅道issue请求体
     */
    private Map<String, Object> buildIssuePayload(IssuePayload payload) {
        Map<String, Object> issueData = new HashMap<>();
        
        issueData.put("title", payload.getTitle());
        issueData.put("content", payload.getDescription());
        
        // 严重程度/优先级映射
        if (payload.getPriority() != null) {
            int severity = mapPriorityToSeverity(payload.getPriority());
            issueData.put("severity", severity);
            issueData.put("pri", mapPriorityToPri(payload.getPriority()));
        }
        
        // 关联需求
        if (payload.getRelatedReqCode() != null) {
            issueData.put("story", payload.getRelatedReqCode());
        }
        
        return issueData;
    }
    
    /**
     * 优先级映射到禅道严重程度(1-4)
     */
    private int mapPriorityToSeverity(String priority) {
        return switch (priority.toUpperCase()) {
            case "HIGHEST", "HIGH" -> 1;  // 严重/致命
            case "MEDIUM" -> 2;           // 较严重
            case "LOW" -> 3;              // 一般
            case "LOWEST" -> 4;           // 轻微
            default -> 3;
        };
    }
    
    /**
     * 优先级映射到禅道优先级(1-4)
     */
    private int mapPriorityToPri(String priority) {
        return switch (priority.toUpperCase()) {
            case "HIGHEST" -> 1;
            case "HIGH" -> 2;
            case "MEDIUM" -> 3;
            case "LOW", "LOWEST" -> 4;
            default -> 3;
        };
    }
    
    /**
     * 检查熔断状态
     */
    private void checkCircuit() {
        if (circuitOpen) {
            long elapsed = System.currentTimeMillis() - circuitOpenTime;
            if (elapsed < properties.getCircuitOpenSeconds() * 1000L) {
                throw new RuntimeException("禅道集成暂不可用（熔断中），请稍后重试");
            } else {
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
                return;
            }
        }
        failureCount++;
        if (failureCount >= properties.getCircuitFailureThreshold()) {
            circuitOpen = true;
            circuitOpenTime = System.currentTimeMillis();
            log.warn("禅道集成熔断触发，连续失败{}次", failureCount);
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