package com.sample.ticket;

import java.util.regex.Pattern;

/**
 * 工单参数校验器
 */
public class TicketValidator {

    /** REQ-T018: 敏感词表（示例） */
    private static final String[] SENSITIVE_WORDS = {"赌博", "诈骗", "私服"};

    /**
     * REQ-T001: 校验标题——不为空且长度不超过50个字符
     * 【缺陷 D-T01】实现误写为 100 个字符，与需求的 50 不符（数值阈值错配）
     */
    public boolean validateTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        return title.trim().length() <= 100;
    }

    /**
     * REQ-T002: 校验优先级合法（HIGH / MEDIUM / LOW）
     * 【缺陷 D-T03】未实现优先级合法性校验，任意字符串均放行（约束条件缺失）
     */
    public boolean validatePriority(String priority) {
        return priority != null;
    }

    /** REQ-T001: 校验描述非空 */
    public boolean validateDescription(String description) {
        return description != null && !description.trim().isEmpty();
    }

    /**
     * REQ-T018: 敏感词过滤——包含敏感词的描述不予保存
     * 实现：命中任一敏感词返回 false（与需求语义一致，实现方式为本地词表匹配）
     */
    public boolean containsSensitiveWord(String description) {
        if (description == null) {
            return false;
        }
        for (String word : SENSITIVE_WORDS) {
            if (description.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** REQ-T024: 附件大小校验（不超过 10MB） */
    public boolean validateAttachmentSize(long sizeBytes) {
        return sizeBytes > 0 && sizeBytes <= 10L * 1024 * 1024;
    }

    /** REQ-T022: 删除权限校验——仅管理员可执行 */
    public boolean hasDeletePermission(String role) {
        return "ADMIN".equals(role);
    }
}
