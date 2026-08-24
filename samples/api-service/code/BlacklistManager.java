package com.sample.apiservice;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 黑名单管理器 - 负责用户黑名单的增删查及导出
 * 黑名单中的用户不得收到任何通知。
 *
 * @author sample
 * @version 1.0
 */
public class BlacklistManager {

    private static final Logger logger = Logger.getLogger(BlacklistManager.class.getName());

    /** 黑名单存储: userId -> 加入时间 */
    private final Map<String, LocalDateTime> blacklistStore = new ConcurrentHashMap<>();

    // ===== 1. 添加到黑名单 =====

    /**
     * 将用户添加到黑名单
     *
     * @param userId 用户ID
     * @return 是否添加成功
     */
    public boolean add(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (blacklistStore.containsKey(userId)) {
            logger.info("用户已在黑名单中: " + userId);
            return false;
        }
        blacklistStore.put(userId, LocalDateTime.now());
        logger.info("用户已加入黑名单: " + userId);
        return true;
    }

    // ===== 2. 从黑名单移除 =====

    /**
     * 将用户从黑名单移除
     *
     * @param userId 用户ID
     * @return 是否移除成功
     */
    public boolean remove(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        LocalDateTime removed = blacklistStore.remove(userId);
        if (removed != null) {
            logger.info("用户已从黑名单移除: " + userId);
            return true;
        }
        return false;
    }

    // ===== 3. 检查是否在黑名单中 =====

    /**
     * 检查用户是否在黑名单中
     *
     * @param userId 用户ID
     * @return true 表示在黑名单中
     */
    public boolean contains(String userId) {
        if (userId == null) {
            return false;
        }
        return blacklistStore.containsKey(userId);
    }

    // ===== 4. 列出所有黑名单用户 =====

    /**
     * 获取所有黑名单用户列表
     *
     * @return 黑名单用户ID列表
     */
    public List<String> listAll() {
        return new ArrayList<>(blacklistStore.keySet());
    }

    // ===== 5. 批量添加到黑名单 =====

    /**
     * 批量将用户添加到黑名单
     *
     * @param userIds 用户ID列表
     * @return 成功添加的数量
     */
    public int batchAdd(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String userId : userIds) {
            try {
                if (add(userId)) {
                    count++;
                }
            } catch (Exception e) {
                // 【缺陷】空 catch 块，批量操作时单条失败被静默忽略，无法追踪问题
            }
        }
        logger.info("批量添加黑名单完成: total=" + userIds.size() + ", success=" + count);
        return count;
    }

    // ===== 6. 导出黑名单 =====

    /**
     * 导出黑名单列表（包含加入时间）
     *
     * @return 黑名单数据列表，每项包含 userId 和 addTime
     */
    public List<Map<String, String>> exportList() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, LocalDateTime> entry : blacklistStore.entrySet()) {
            Map<String, String> item = new HashMap<>();
            item.put("userId", entry.getKey());
            item.put("addTime", entry.getValue().toString());
            result.add(item);
        }
        return result;
    }
}
