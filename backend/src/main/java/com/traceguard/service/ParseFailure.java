package com.traceguard.service;

/**
 * GAP-014：批量解析失败信息结构。
 * 记录单个失败文件的文件名、失败原因、修正建议与文件大小。
 */
public record ParseFailure(String fileName, String reason, String suggestion, long fileSize) {

    /**
     * 按异常类型归类失败原因并返回修正建议。
     */
    public static ParseFailure fromException(String fileName, Exception e, long fileSize) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String reason;
        String suggestion;

        if (message.contains("密码") || message.contains("password") || message.contains("encrypted")) {
            reason = "文档已加密，无法解析";
            suggestion = "请解除文档密码后重新上传";
        } else if (message.contains("50MB") || message.contains("超出") || fileSize > 50 * 1024 * 1024) {
            reason = "超出50MB上限";
            suggestion = "请拆分文档后分批上传";
        } else if (message.contains("未解析到") || message.contains("无可识别") || message.contains("empty")) {
            reason = "未解析到需求条目";
            suggestion = "请检查文档内容是否符合需求条目格式（带编号条目）";
        } else if (e instanceof org.apache.poi.openxml4j.exceptions.InvalidFormatException
                || e instanceof org.apache.poi.EmptyFileException
                || message.contains("Invalid") || message.contains("corrupt")) {
            reason = "文件损坏或非有效 docx/pdf";
            suggestion = "请用对应办公软件重新导出后上传";
        } else {
            // 未分类异常：截断原始 message
            reason = "解析异常：" + message.substring(0, Math.min(100, message.length()));
            suggestion = "请检查文件格式后重试，或联系管理员";
        }
        return new ParseFailure(fileName, reason, suggestion, fileSize);
    }
}
