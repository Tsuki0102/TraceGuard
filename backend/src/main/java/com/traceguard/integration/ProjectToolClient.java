package com.traceguard.integration;

/**
 * GAP-009：第三方项目管理工具统一抽象接口
 * 屏蔽Jira/禅道差异，后续扩展其他工具只需新增实现
 * 继承 ToolClient（type/enabled/testConnection），复用统一连通测试与状态总览。
 */
public interface ProjectToolClient extends ToolClient {
    /**
     * 新建issue（story/bug）
     */
    IssueRef createIssue(IssuePayload payload);
    
    /**
     * 更新issue（幂等重推）
     */
    IssueRef updateIssue(String remoteKey, IssuePayload payload);
    
    /**
     * 状态流转（本地状态变化同步远程issue状态）
     */
    void transitionStatus(String remoteKey, RemoteStatus status);

    /**
     * 查询远程issue当前状态（入站同步：远程->本地轮询用）
     * @param remoteKey 远程issue标识（Jira ISSUE-KEY / 禅道 bug-id）
     * @return 远程状态（status 为平台原生状态名，description 为可读描述），查不到返回 null
     */
    RemoteIssueStatus fetchRemoteStatus(String remoteKey);
}