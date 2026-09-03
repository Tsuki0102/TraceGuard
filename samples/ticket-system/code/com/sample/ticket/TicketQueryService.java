package com.sample.ticket;

import java.util.*;

/**
 * 工单查询与统计服务
 */
public class TicketQueryService {

    private final TicketValidator validator = new TicketValidator();
    private final Map<String, Ticket> ticketStore;

    public TicketQueryService(Map<String, Ticket> ticketStore) {
        this.ticketStore = ticketStore;
    }

    /** REQ-T012: 按工单ID精确查询，查询不到返回 null */
    public Ticket queryById(String ticketId) {
        return ticketStore.get(ticketId);
    }

    /** REQ-T013: 按状态查询工单列表 */
    public List<Ticket> queryByStatus(String status) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : ticketStore.values()) {
            if (t.getStatus().equals(status)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * REQ-T014: 按处理人查询（需校验当前操作人查询权限）
     * 【缺陷 D-T10】未实现权限校验，任意操作人可查询他人工单（约束缺失）
     */
    public List<Ticket> queryByAssignee(String assignee, String operatorRole) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : ticketStore.values()) {
            if (assignee.equals(t.getAssignee())) {
                out.add(t);
            }
        }
        return out;
    }

    /** REQ-T015: 按日期统计当日新建工单数量（yyyy-MM-dd） */
    public int countByDate(String date) {
        int count = 0;
        for (Ticket t : ticketStore.values()) {
            String day = String.format("%tF", t.getCreateTime());
            if (day.equals(date)) {
                count++;
            }
        }
        return count;
    }

    /** REQ-T021: 按关键字搜索工单标题（标题包含关键字） */
    public List<Ticket> searchByKeyword(String keyword) {
        List<Ticket> out = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) {
            return out;
        }
        for (Ticket t : ticketStore.values()) {
            if (t.getTitle().contains(keyword)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * REQ-T017: 工单列表分页——每页默认20条，每页最大不超过100条
     * 困难负例：需求表述为"分页规则"，实现为分页参数裁剪，语义一致但措辞迥异
     */
    /** REQ-T017: 分页查询（默认每页 20 条的重载入口） */
    public List<Ticket> queryPaged(int pageNum) {
        return queryPaged(pageNum, 20);
    }

    public List<Ticket> queryPaged(int pageNum, int pageSize) {
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int safePage = Math.max(pageNum, 1);
        List<Ticket> all = new ArrayList<>(ticketStore.values());
        int from = (safePage - 1) * safeSize;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + safeSize, all.size());
        return all.subList(from, to);
    }

    /** 代码超范围实现：将工单清单导出为内部调试快照（需求未要求该功能） */
    public String dumpDebugSnapshot() {
        StringBuilder sb = new StringBuilder("snapshot:");
        for (Ticket t : ticketStore.values()) {
            sb.append(t.getTicketId()).append(",");
        }
        return sb.toString();
    }
}
