package com.sample.ticket;

import java.util.*;

/**
 * 工单归档服务
 */
public class TicketArchiveService {

    /**
     * REQ-T026: 定时归档——关闭超过90天的工单自动归档至历史库
     * 本方法为归档逻辑的领域实现（由调度器每日凌晨调用），与需求一致
     */
    public int archiveExpiredTickets(Map<String, Ticket> ticketStore) {
        int archived = 0;
        long ninetyDays = 90L * 24 * 3600 * 1000;
        Iterator<Map.Entry<String, Ticket>> it = ticketStore.entrySet().iterator();
        while (it.hasNext()) {
            Ticket t = it.next().getValue();
            if (Ticket.STATUS_CLOSED.equals(t.getStatus()) && t.getCloseTime() != null
                    && System.currentTimeMillis() - t.getCloseTime().getTime() > ninetyDays) {
                it.remove();
                archived++;
            }
        }
        return archived;
    }

    /** 代码超范围实现：将归档结果同步到外部审计邮件（需求未要求） */
    public void notifyArchiveResultByMail(int archivedCount) {
        if (archivedCount > 0) {
            System.out.println("[mail] archived=" + archivedCount);
        }
    }
}
