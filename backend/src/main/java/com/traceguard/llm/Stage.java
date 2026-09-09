package com.traceguard.llm;

/**
 * 分析环节枚举（GAP-021）
 * 按环节路由 provider + model（EMBEDDING 不走 chat 编排，独立配置段）。
 */
public enum Stage {
    /** 需求语义提取（Kripke 结构） */
    REQUIREMENT("requirement"),
    /** Alloy 规约生成 */
    ALLOY("alloy"),
    /** 代码逻辑描述 */
    CODE_EXPLAIN("code-explain"),
    /** 缺陷解释增强 */
    DEFECT_EXPLAIN("defect-explain"),
    /** AUD-02：需求-代码一致性语义判定（LLM 二审） */
    CONSISTENCY_CHECK("consistency-check"),
    /** 智能体对话（Agent 多轮工具调用循环；路由缺失时业务层回退 code-explain） */
    AGENT_CHAT("agent");

    /** 配置文件中的键名（routing/models 表的 key） */
    private final String configKey;

    Stage(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
