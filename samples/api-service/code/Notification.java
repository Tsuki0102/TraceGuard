package com.sample.apiservice;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 通知实体类
 * 表示一条通知的完整信息，包括发送渠道、内容、状态等。
 * 
 * @author sample
 * @version 1.0
 */
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知唯一ID */
    private String id;

    /** 目标用户ID */
    private String userId;

    /** 发送渠道: SMS / EMAIL / PUSH */
    private String channel;

    /** 通知内容 */
    private String content;

    /** 通知状态: PENDING / SENDING / SUCCESS / FAILED / CANCELLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 重试次数 */
    private int retryCount;

    /** 关联的模板ID（可选） */
    private String templateId;

    /** 批量任务ID（可选） */
    private String batchTaskId;

    /** 投递回调状态: null / DELIVERED / FAILED / TIMEOUT */
    private String deliveryStatus;

    /** 最后更新时间 */
    private LocalDateTime updateTime;

    /** 失败原因 */
    private String failReason;

    // ===== 构造方法 =====

    public Notification() {
    }

    public Notification(String id, String userId, String channel, String content) {
        this.id = id;
        this.userId = userId;
        this.channel = channel;
        this.content = content;
        this.status = "PENDING";
        this.createTime = LocalDateTime.now();
        this.retryCount = 0;
    }

    // ===== Getter / Setter =====

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getBatchTaskId() {
        return batchTaskId;
    }

    public void setBatchTaskId(String batchTaskId) {
        this.batchTaskId = batchTaskId;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    // ===== 业务辅助方法 =====

    /**
     * 判断通知是否可以重试
     */
    public boolean canRetry() {
        return "FAILED".equals(this.status) && this.retryCount < 3;
    }

    /**
     * 增加重试计数
     */
    public void incrementRetry() {
        this.retryCount++;
    }

    /**
     * 生成用于幂等去重的 key
     */
    public String buildIdempotentKey() {
        return userId + ":" + channel + ":" + content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Notification that = (Notification) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Notification{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", channel='" + channel + '\'' +
                ", status='" + status + '\'' +
                ", retryCount=" + retryCount +
                ", createTime=" + createTime +
                '}';
    }
}
