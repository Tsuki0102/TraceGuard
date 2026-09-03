package com.sample.ticket;

import java.util.*;

/**
 * 工单状态机校验器
 */
public class TicketStateValidator {

    /** REQ-T004: 合法状态流转顺序 */
    private static final Map<String, String> NEXT_STATUS = new LinkedHashMap<>();

    static {
        NEXT_STATUS.put(Ticket.STATUS_NEW, Ticket.STATUS_OPEN);
        NEXT_STATUS.put(Ticket.STATUS_OPEN, Ticket.STATUS_IN_PROGRESS);
        NEXT_STATUS.put(Ticket.STATUS_IN_PROGRESS, Ticket.STATUS_RESOLVED);
        NEXT_STATUS.put(Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED);
    }

    /**
     * REQ-T004: 校验 from -> to 是否符合状态机顺序（不允许跳过中间状态）
     */
    public boolean validateTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return to.equals(NEXT_STATUS.get(from));
    }

    /** REQ-T006: 受理校验——仅 NEW 状态可受理 */
    public boolean canAccept(Ticket ticket) {
        return ticket != null && Ticket.STATUS_NEW.equals(ticket.getStatus());
    }

    /**
     * REQ-T008: 关闭校验——仅 RESOLVED 状态可关闭
     * 【缺陷 D-T07】实现未校验状态，任意状态均可关闭（状态约束缺失）
     */
    public boolean canClose(Ticket ticket) {
        return ticket != null;
    }
}
