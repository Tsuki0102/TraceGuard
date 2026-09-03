package com.sample.ticket;

import java.util.*;

/**
 * SLA 时限计算器
 */
public class SlaCalculator {

    /**
     * REQ-T005: SLA 时限——HIGH 2小时 / MEDIUM 8小时 / LOW 24小时
     * 【缺陷 D-T09（困难负例）】实现误写为 HIGH=4 小时（需求为 2 小时）；
     * 结构完整（分支齐备）仅数值错配，语义高度对齐，属数值类缺陷的困难样本
     */
    public int computeDeadlineHours(String priority) {
        if ("HIGH".equals(priority)) {
            return 4;
        } else if ("MEDIUM".equals(priority)) {
            return 8;
        } else if ("LOW".equals(priority)) {
            return 24;
        }
        throw new RuntimeException("未知优先级");
    }

    /** REQ-T005/REQ-T016: 判断工单是否超时（自创建起超过 SLA 时限且未解决） */
    public boolean isBreached(Ticket ticket) {
        int deadlineHours = computeDeadlineHours(ticket.getPriority());
        long hours = (System.currentTimeMillis() - ticket.getCreateTime().getTime()) / (3600 * 1000);
        return hours > deadlineHours && !Ticket.STATUS_RESOLVED.equals(ticket.getStatus())
                && !Ticket.STATUS_CLOSED.equals(ticket.getStatus());
    }

    /** REQ-T016: 统计已超时工单数量 */
    public int countSlaBreaches(Collection<Ticket> tickets) {
        int count = 0;
        for (Ticket t : tickets) {
            if (isBreached(t)) {
                count++;
            }
        }
        return count;
    }
}
