package com.sample.apiservice;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 通知服务 - 核心业务逻辑层
 * 负责通知的发送、去重、限流、重试、统计等核心业务处理。
 *
 * @author sample
 * @version 1.0
 */
public class NotificationService {

    private static final Logger logger = Logger.getLogger(NotificationService.class.getName());

    /** 模拟通知存储 */
    private final Map<String, Notification> notificationStore = new ConcurrentHashMap<>();

    /** 幂等去重缓存: key -> 上次发送时间 */
    private final Map<String, LocalDateTime> idempotentCache = new ConcurrentHashMap<>();

    /** 限流计数器: userId -> 发送时间列表 */
    private final Map<String, List<LocalDateTime>> rateLimitStore = new ConcurrentHashMap<>();

    /** 批量任务进度: batchTaskId -> [total, processed] */
    private final Map<String, int[]> batchTaskProgress = new ConcurrentHashMap<>();

    /** 黑名单管理器 */
    private final BlacklistManager blacklistManager = new BlacklistManager();

    /** 限流阈值：每分钟最大发送数 */
    // 【缺陷】需求 REQ-003 要求每分钟最多10条，这里设置为20，与需求不一致
    private static final int RATE_LIMIT_PER_MINUTE = 20;

    /** 幂等去重时间窗口（秒） */
    // 【缺陷】需求 REQ-002 要求60秒窗口，这里设置为30秒，与需求不一致
    private static final int IDEMPOTENT_WINDOW_SECONDS = 30;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    // ===== 1. 发送通知（主入口） =====

    /**
     * 执行通知发送流程
     *
     * @param userId  用户ID
     * @param channel 渠道类型
     * @param content 通知内容
     * @return 通知ID
     */
    public String doSend(String userId, String channel, String content) {
        // 1. 参数校验
        validateParams(userId, channel, content);

        // 2. 黑名单检查
        checkBlacklist(userId);

        // 3. 幂等去重检查
        checkIdempotent(userId, channel, content);

        // 4. 限流检查
        checkRateLimit(userId);

        // 5. 选择发送渠道
        String actualChannel = selectChannel(channel);

        // 6. 格式化内容
        String formattedContent = formatContent(content, actualChannel);

        // 7. 创建通知记录
        Notification notification = new Notification(UUID.randomUUID().toString(), userId, actualChannel, formattedContent);
        notification.setStatus("SENDING");

        // 8. 执行发送
        try {
            simulateChannelSend(actualChannel, formattedContent);
            notification.setStatus("SUCCESS");
        } catch (Exception e) {
            notification.setStatus("FAILED");
            notification.setFailReason(e.getMessage());
            // 发送失败，触发重试
            retryWithBackoff(notification.getId());
        }

        // 9. 保存记录
        saveHistory(notification);

        return notification.getId();
    }

    // ===== 2. 参数校验 =====

    /**
     * 校验发送参数合法性
     *
     * @param userId  用户ID
     * @param channel 渠道
     * @param content 内容
     */
    public void validateParams(String userId, String channel, String content) {
        // 【缺陷】只校验了 userId 是否为空，没有校验 channel 和 content
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        // channel 和 content 的校验缺失
    }

    // ===== 3. 幂等去重检查 =====

    /**
     * 检查是否为重复请求（幂等去重）
     * 相同用户、相同渠道、相同内容在时间窗口内不得重复发送
     *
     * @param userId  用户ID
     * @param channel 渠道
     * @param content 内容
     */
    public void checkIdempotent(String userId, String channel, String content) {
        String key = userId + ":" + channel + ":" + content;
        LocalDateTime lastSendTime = idempotentCache.get(key);

        if (lastSendTime != null) {
            Duration elapsed = Duration.between(lastSendTime, LocalDateTime.now());
            if (elapsed.getSeconds() < IDEMPOTENT_WINDOW_SECONDS) {
                throw new IllegalStateException("重复请求，" + IDEMPOTENT_WINDOW_SECONDS + "秒内已发送过相同内容");
            }
        }
        // 记录本次发送时间
        idempotentCache.put(key, LocalDateTime.now());
    }

    // ===== 4. 限流检查 =====

    /**
     * 检查用户级限流
     * 每个用户每分钟最多发送 RATE_LIMIT_PER_MINUTE 条通知
     *
     * @param userId 用户ID
     */
    public void checkRateLimit(String userId) {
        LocalDateTime now = LocalDateTime.now();
        List<LocalDateTime> timestamps = rateLimitStore.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>()));

        // 清理超过1分钟的记录
        timestamps.removeIf(t -> Duration.between(t, now).getSeconds() > 60);

        if (timestamps.size() >= RATE_LIMIT_PER_MINUTE) {
            throw new IllegalStateException("发送频率超限，每分钟最多" + RATE_LIMIT_PER_MINUTE + "条");
        }
        timestamps.add(now);
    }

    // ===== 5. 黑名单检查 =====

    /**
     * 检查用户是否在黑名单中
     *
     * @param userId 用户ID
     */
    public void checkBlacklist(String userId) {
        if (blacklistManager.contains(userId)) {
            throw new SecurityException("用户在黑名单中，禁止发送通知");
        }
    }

    // ===== 6. 渠道选择 =====

    /**
     * 根据策略选择实际发送渠道
     *
     * @param requestedChannel 请求的渠道
     * @return 实际使用的渠道
     */
    public String selectChannel(String requestedChannel) {
        // 当前直接返回请求渠道，未来可扩展为智能路由
        if (!isValidChannel(requestedChannel)) {
            throw new IllegalArgumentException("不支持的渠道类型: " + requestedChannel);
        }
        return requestedChannel;
    }

    /**
     * 判断渠道是否合法
     */
    private boolean isValidChannel(String channel) {
        return "SMS".equals(channel) || "EMAIL".equals(channel) || "PUSH".equals(channel);
    }

    // ===== 7. 内容格式化 =====

    /**
     * 根据渠道特性格式化通知内容
     *
     * @param content 原始内容
     * @param channel 渠道类型
     * @return 格式化后的内容
     */
    public String formatContent(String content, String channel) {
        if ("SMS".equals(channel)) {
            // 短信有长度限制，截断处理
            if (content != null && content.length() > 70) {
                return content.substring(0, 67) + "...";
            }
        } else if ("EMAIL".equals(channel)) {
            // 邮件添加HTML包装
            return "<html><body><p>" + content + "</p></body></html>";
        } else if ("PUSH".equals(channel)) {
            // 推送通知添加前缀
            return "[通知] " + content;
        }
        return content;
    }

    // ===== 8. 保存发送历史 =====

    /**
     * 保存通知发送记录
     *
     * @param notification 通知对象
     */
    public void saveHistory(Notification notification) {
        notificationStore.put(notification.getId(), notification);
    }

    // ===== 9. 更新通知状态 =====

    /**
     * 更新通知的投递状态
     *
     * @param notificationId 通知ID
     * @param deliveryStatus 投递状态
     */
    public void updateDeliveryStatus(String notificationId, String deliveryStatus) {
        Notification notification = notificationStore.get(notificationId);
        if (notification != null) {
            notification.setDeliveryStatus(deliveryStatus);
            if ("FAILED".equals(deliveryStatus) || "TIMEOUT".equals(deliveryStatus)) {
                notification.setStatus("FAILED");
            }
        }
    }

    // ===== 10. 重试机制（指数退避） =====

    /**
     * 带指数退避的重试机制
     * 重试间隔: 1s, 2s, 4s
     *
     * @param notificationId 通知ID
     * @return 是否提交重试成功
     */
    public boolean retryWithBackoff(String notificationId) {
        Notification notification = notificationStore.get(notificationId);
        if (notification == null || !notification.canRetry()) {
            return false;
        }

        notification.incrementRetry();
        notification.setStatus("SENDING");
        int retryCount = notification.getRetryCount();

        // 指数退避等待: 2^(retryCount-1) 秒
        long waitSeconds = (long) Math.pow(2, retryCount - 1);

        // 模拟异步重试（实际应使用调度器）
        try {
            Thread.sleep(waitSeconds * 1000);
            simulateChannelSend(notification.getChannel(), notification.getContent());
            notification.setStatus("SUCCESS");
        } catch (Exception e) {
            // 【缺陷】空 catch 块，吞掉了异常信息，无法追踪重试失败原因
            // 如果重试仍然失败且已达最大次数，应该标记为最终失败
        }

        return true;
    }

    /**
     * 重载：按通知ID重试（供 Controller 调用）
     */
    public boolean retryWithBackoff(Notification notification) {
        if (notification == null || !notification.canRetry()) {
            return false;
        }
        return retryWithBackoff(notification.getId());
    }

    // ===== 11. 批量处理 =====

    /**
     * 批量发送通知并跟踪进度
     *
     * @param notifications 通知列表
     * @return 批量任务ID
     */
    public String batchProcess(List<Notification> notifications) {
        String batchTaskId = UUID.randomUUID().toString();
        batchTaskProgress.put(batchTaskId, new int[]{notifications.size(), 0});

        for (Notification n : notifications) {
            n.setBatchTaskId(batchTaskId);
            try {
                doSend(n.getUserId(), n.getChannel(), n.getContent());
            } catch (Exception e) {
                // 单条失败不影响整批
                n.setStatus("FAILED");
                n.setFailReason(e.getMessage());
                saveHistory(n);
            }
            // 更新进度
            int[] progress = batchTaskProgress.get(batchTaskId);
            if (progress != null) {
                progress[1]++;
            }
        }

        return batchTaskId;
    }

    /**
     * 查询批量任务进度
     *
     * @param batchTaskId 批量任务ID
     * @return 进度信息 [total, processed]
     */
    public int[] getBatchProgress(String batchTaskId) {
        return batchTaskProgress.get(batchTaskId);
    }

    // ===== 12. 统计 - 发送成功率 =====

    /**
     * 获取发送成功率
     *
     * @param dimension 统计维度: day/week/month
     * @return 成功率（百分比）
     */
    public double getSuccessRate(String dimension) {
        List<Notification> all = new ArrayList<>(notificationStore.values());

        // 按维度过滤（简化实现）
        LocalDateTime cutoff;
        if ("day".equals(dimension)) {
            cutoff = LocalDateTime.now().minusDays(1);
        } else if ("week".equals(dimension)) {
            cutoff = LocalDateTime.now().minusWeeks(1);
        } else if ("month".equals(dimension)) {
            cutoff = LocalDateTime.now().minusMonths(1);
        } else {
            cutoff = LocalDateTime.now().minusDays(1);
        }

        List<Notification> filtered = all.stream()
                .filter(n -> n.getCreateTime() != null && n.getCreateTime().isAfter(cutoff))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            return 0.0;
        }

        long successCount = filtered.stream()
                .filter(n -> "SUCCESS".equals(n.getStatus()))
                .count();

        // 【缺陷】整数除法导致精度丢失，successCount 和 filtered.size() 都是 long/int
        return (double) (successCount * 100) / filtered.size();
    }

    /**
     * 获取各渠道发送数量分布
     *
     * @return 渠道分布 Map
     */
    public Map<String, Integer> getChannelDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("SMS", 0);
        distribution.put("EMAIL", 0);
        distribution.put("PUSH", 0);

        for (Notification n : notificationStore.values()) {
            String channel = n.getChannel();
            // 【缺陷】潜在 NPE：如果 channel 为 null，containsKey 返回 false 但不会 NPE，
            // 但 put 操作如果 channel 是 null 会导致 NPE（ConcurrentHashMap 不允许 null key）
            if (distribution.containsKey(channel)) {
                distribution.put(channel, distribution.get(channel) + 1);
            }
        }
        return distribution;
    }

    // ===== 辅助方法 =====

    /**
     * 根据ID获取通知
     */
    public Notification getById(String notificationId) {
        return notificationStore.get(notificationId);
    }

    /**
     * 查询历史记录
     */
    public List<Notification> queryHistory(String userId, String channel) {
        return notificationStore.values().stream()
                .filter(n -> (userId == null || userId.equals(n.getUserId())))
                .filter(n -> (channel == null || channel.equals(n.getChannel())))
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .collect(Collectors.toList());
    }

    /**
     * 取消通知
     */
    public boolean cancelNotification(String notificationId) {
        Notification notification = notificationStore.get(notificationId);
        if (notification != null && "PENDING".equals(notification.getStatus())) {
            notification.setStatus("CANCELLED");
            return true;
        }
        return false;
    }

    /**
     * 模拟渠道发送（实际应调用第三方API）
     */
    private void simulateChannelSend(String channel, String content) throws Exception {
        // 模拟发送延迟
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 模拟 5% 的发送失败率（用于测试重试机制）
        if (Math.random() < 0.05) {
            throw new Exception("渠道 " + channel + " 发送失败: 网络超时");
        }
    }

    /**
     * 清理过期的幂等缓存（应定时调用）
     */
    public void cleanIdempotentCache() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(IDEMPOTENT_WINDOW_SECONDS);
        idempotentCache.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
    }

    /**
     * 清理过期限流记录（应定时调用）
     */
    public void cleanRateLimitStore() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(1);
        rateLimitStore.values().forEach(list -> list.removeIf(t -> t.isBefore(threshold)));
    }
}
