package com.sample.ticket;

import java.util.*;

/**
 * 工单统计报表服务
 */
public class TicketReportService {

    private final Map<String, Ticket> ticketStore;

    public TicketReportService(Map<String, Ticket> ticketStore) {
        this.ticketStore = ticketStore;
    }

    /**
     * REQ-T027: 统计指定日期区间内每天的工单创建数量（yyyy-MM-dd -> 数量）
     */
    public Map<String, Integer> countByDateRange(Date start, Date end) {
        Map<String, Integer> daily = new TreeMap<>();
        if (start == null || end == null) {
            return daily;
        }
        for (Ticket t : ticketStore.values()) {
            Date ct = t.getCreateTime();
            if (!ct.before(start) && !ct.after(end)) {
                String day = String.format("%tF", ct);
                daily.merge(day, 1, Integer::sum);
            }
        }
        return daily;
    }

    /**
     * REQ-T028: 查询指定处理人当前名下处于 IN_PROGRESS 状态的工单数量
     */
    public int countInProgressByAssignee(String assignee) {
        if (assignee == null || assignee.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Ticket t : ticketStore.values()) {
            if (assignee.equals(t.getAssignee()) && Ticket.STATUS_IN_PROGRESS.equals(t.getStatus())) {
                count++;
            }
        }
        return count;
    }
}
