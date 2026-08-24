package com.traceguard.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 需求解析规则配置（AUD-07，FR-REQ-002 歧义/矛盾检测可配置化）。
 * 存储于 sys_config（config_key=req_parse_rules），结构可经管理后台可视化调整。
 */
public class ReqParseRuleConfig {

    /** 歧义/模糊词清单：命中出现即提示「建议明确化」 */
    private List<String> ambiguityKeywords =
            new ArrayList<>(Arrays.asList("等", "等等", "适当", "合适", "合理", "可能", "也许", "若干", "一些", "方便", "友好"));

    /**
     * 互斥词对：同一需求内同时出现视为「潜在矛盾，建议人工复核」。
     * 每个元素为 [词A, 词B]；词B 支持 (?&lt;!不) 形式的前缀否定（即「不允许」不算与「禁止」矛盾）。
     */
    private List<List<String>> contradictionPairs = new ArrayList<>(Arrays.asList(
            Arrays.asList("必须", "禁止"),
            Arrays.asList("必须", "不得"),
            Arrays.asList("禁止", "允许"),
            Arrays.asList("不得", "允许"),
            Arrays.asList("启用", "禁用")
    ));

    /** 是否允许调用大模型做歧义/矛盾增强检测（默认关闭，与全局 LLM 开关一致） */
    private boolean enableLLM = false;

    public List<String> getAmbiguityKeywords() {
        return ambiguityKeywords == null ? new ArrayList<>() : ambiguityKeywords;
    }

    public void setAmbiguityKeywords(List<String> ambiguityKeywords) {
        this.ambiguityKeywords = ambiguityKeywords;
    }

    public List<List<String>> getContradictionPairs() {
        return contradictionPairs == null ? new ArrayList<>() : contradictionPairs;
    }

    public void setContradictionPairs(List<List<String>> contradictionPairs) {
        this.contradictionPairs = contradictionPairs;
    }

    public boolean isEnableLLM() {
        return enableLLM;
    }

    public void setEnableLLM(boolean enableLLM) {
        this.enableLLM = enableLLM;
    }
}
