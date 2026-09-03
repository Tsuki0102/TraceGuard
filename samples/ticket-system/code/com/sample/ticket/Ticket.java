package com.sample.ticket;

import java.util.*;

/**
 * 工单实体
 */
public class Ticket {
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    private String ticketId;
    private String title;
    private String description;
    private String priority;        // HIGH / MEDIUM / LOW
    private String status;
    private String reporter;
    private String assignee;
    private String solution;
    private Date createTime;
    private Date closeTime;
    private int reopenCount;
    private String finishEvaluation;

    public Ticket(String ticketId, String title, String description, String priority, String reporter) {
        this.ticketId = ticketId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.reporter = reporter;
        this.status = STATUS_NEW;
        this.createTime = new Date();
        this.reopenCount = 0;
    }

    public String getTicketId() { return ticketId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReporter() { return reporter; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public String getSolution() { return solution; }
    public void setSolution(String solution) { this.solution = solution; }
    public Date getCreateTime() { return createTime; }
    public Date getCloseTime() { return closeTime; }
    public void setCloseTime(Date closeTime) { this.closeTime = closeTime; }
    public int getReopenCount() { return reopenCount; }
    public void setReopenCount(int reopenCount) { this.reopenCount = reopenCount; }
    public String getFinishEvaluation() { return finishEvaluation; }
    public void setFinishEvaluation(String finishEvaluation) { this.finishEvaluation = finishEvaluation; }
}
