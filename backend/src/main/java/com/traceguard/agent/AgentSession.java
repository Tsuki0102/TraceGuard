package com.traceguard.agent;

import com.traceguard.llm.LlmMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体会话（LLM 侧多轮消息历史，进程内存储）
 * 仅保存 role/content 文本；工具调用明细不留存在历史（观察结果已并入 user 消息）。
 */
public class AgentSession {

    private final List<LlmMessage> history = new ArrayList<>();
    private volatile long lastAccessMs = System.currentTimeMillis();

    public List<LlmMessage> getHistory() {
        return history;
    }

    public void touch() {
        this.lastAccessMs = System.currentTimeMillis();
    }

    public long getLastAccessMs() {
        return lastAccessMs;
    }
}
