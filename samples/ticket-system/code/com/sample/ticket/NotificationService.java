package com.sample.ticket;

import java.util.*;

/**
 * 通知服务
 */
public class NotificationService {

    /**
     * REQ-T011: 转派通知——发送失败最多重试3次，重试间隔5秒
     * 【缺陷 D-T11】实现仅重试1次（需求为3次），且未实现5秒间隔
     */
    public boolean notifyAssignee(String assignee, String ticketId) {
        if (assignee == null || assignee.isEmpty()) {
            return false;
        }
        int maxRetry = 1;
        for (int i = 0; i <= maxRetry; i++) {
            boolean sent = doSend(assignee, ticketId);
            if (sent) {
                return true;
            }
        }
        return false;
    }

    /** 真实发送通道（演示桩：默认失败以触发重试路径） */
    private boolean doSend(String assignee, String ticketId) {
        return assignee != null && !assignee.isEmpty() && ticketId != null;
    }

    /**
     * REQ-T029: 工单关闭后清除其未读通知标记
     */
    public boolean clearUnreadNotices(String ticketId, Set<String> unreadNoticeIds) {
        if (ticketId == null || unreadNoticeIds == null) {
            return false;
        }
        return unreadNoticeIds.removeIf(noticeId -> noticeId.startsWith(ticketId));
    }
}
