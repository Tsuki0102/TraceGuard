package com.sample.ticket;

import java.util.*;

/**
 * 工单核心服务：创建 / 受理 / 转派 / 关闭 / 重新打开 / 升级 / 解决
 */
public class TicketService {

    private final TicketStateValidator stateValidator = new TicketStateValidator();
    private final TicketValidator validator = new TicketValidator();
    private final NotificationService notificationService = new NotificationService();
    private final Map<String, Ticket> ticketStore = new HashMap<>();
    private int idSeq = 1000;

    /**
     * REQ-T003: 创建工单——校验通过后初始状态为 NEW 并返回工单ID
     * 【缺陷 D-T02（困难负例反向）】本方法调用 validatePriority 校验优先级；
     * 但 TicketValidator.validatePriority 自身实现存在缺陷（任意非空优先级放行），
     * 该缺陷归属于校验器方法（见 D-T03），创建流程本身职责已完成。
     */
    public String createTicket(String title, String description, String priority, String reporter) {
        if (!validator.validateTitle(title)) {
            throw new RuntimeException("工单标题非法");
        }
        if (!validator.validatePriority(priority)) {
            throw new RuntimeException("工单优先级非法");
        }
        if (!validator.validateDescription(description)) {
            throw new RuntimeException("工单描述不能为空");
        }
        if (validator.containsSensitiveWord(description)) {
            throw new RuntimeException("工单描述包含敏感词");
        }
        Ticket ticket = new Ticket("T-" + (++idSeq), title, description, priority, reporter);
        ticketStore.put(ticket.getTicketId(), ticket);
        return ticket.getTicketId();
    }

    /**
     * REQ-T006: 受理工单——仅 NEW 状态可受理，受理后进入 OPEN
     */
    public void acceptTicket(Ticket ticket) {
        if (!stateValidator.canAccept(ticket)) {
            throw new RuntimeException("当前状态不允许受理");
        }
        ticket.setStatus(Ticket.STATUS_OPEN);
    }

    /**
     * REQ-T007: 转派工单——目标处理人非空，转派后保持 IN_PROGRESS，仅更新处理人
     * 【缺陷 D-T05】实现错误地将状态置为 RESOLVED（应保持 IN_PROGRESS），且缺少处理人非空校验
     */
    public void assignTicket(Ticket ticket, String newAssignee) {
        ticket.setAssignee(newAssignee);
        ticket.setStatus(Ticket.STATUS_RESOLVED);
        notificationService.notifyAssignee(newAssignee, ticket.getTicketId());
    }

    /**
     * REQ-T008: 关闭工单
     * 【缺陷 D-T06】未校验"仅 RESOLVED 可关闭"（TicketStateValidator.canClose 自身缺陷），
     * 且关闭后未记录关闭时间（需求要求记录 closeTime）
     */
    public void closeTicket(Ticket ticket) {
        if (!stateValidator.canClose(ticket)) {
            throw new RuntimeException("当前状态不允许关闭");
        }
        ticket.setStatus(Ticket.STATUS_CLOSED);
    }

    /**
     * REQ-T009: 重新打开工单——仅 CLOSED 可重开，回到 OPEN，次数不超过3次
     * 【缺陷 D-T04】未实现"重新打开次数不得超过3次"的限制（约束缺失）
     */
    public void reopenTicket(Ticket ticket) {
        if (!Ticket.STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new RuntimeException("仅已关闭工单可重新打开");
        }
        ticket.setReopenCount(ticket.getReopenCount() + 1);
        ticket.setStatus(Ticket.STATUS_OPEN);
    }

    /**
     * REQ-T019: 解决工单——必须填写解决方案说明才能置为 RESOLVED
     */
    public void resolveTicket(Ticket ticket, String solution) {
        if (solution == null || solution.trim().isEmpty()) {
            throw new RuntimeException("解决方案说明不能为空");
        }
        if (!Ticket.STATUS_IN_PROGRESS.equals(ticket.getStatus())) {
            throw new RuntimeException("仅处理中工单可解决");
        }
        ticket.setSolution(solution);
        ticket.setStatus(Ticket.STATUS_RESOLVED);
    }

    /**
     * REQ-T010: 工单升级——创建超过48小时未解决则升级优先级一档
     * 【缺陷 D-T08】实现误写为 24 小时（需求为 48 小时），且 HIGH 保持不变的规则未实现（HIGH 仍会升档越界）
     */
    public boolean escalateIfOverdue(Ticket ticket) {
        long hours = (System.currentTimeMillis() - ticket.getCreateTime().getTime()) / (3600 * 1000);
        if (hours > 24 && !Ticket.STATUS_RESOLVED.equals(ticket.getStatus())
                && !Ticket.STATUS_CLOSED.equals(ticket.getStatus())) {
            if ("LOW".equals(ticket.getPriority())) {
                ticket.setPriority("MEDIUM");
            } else {
                ticket.setPriority("HIGH");
            }
            return true;
        }
        return false;
    }

    /** REQ-T020: 批量导入——逐条校验，任一条不合法整批拒绝 */
    public int batchImport(List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            String title = row.get("title");
            String desc = row.get("description");
            String priority = row.get("priority");
            if (!validator.validateTitle(title) || !validator.validateDescription(desc)
                    || !validator.validatePriority(priority)) {
                throw new RuntimeException("批量导入存在非法数据，整批拒绝");
            }
            if (validator.containsSensitiveWord(desc)) {
                throw new RuntimeException("批量导入存在敏感词，整批拒绝");
            }
        }
        for (Map<String, String> row : rows) {
            createTicket(row.get("title"), row.get("description"), row.get("priority"), row.get("reporter"));
        }
        return rows.size();
    }
}
