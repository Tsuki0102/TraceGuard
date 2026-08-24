package com.traceguard.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GAP-044 / FUN-03：LangChain 适配器回退链路回归测试。
 *
 * 覆盖可自动化的部分：
 *   1. create 永不抛异常——langchain4j 缺失/构造失败时返回不可用空适配器（available=false），调用方回退 self 引擎；
 *   2. 调用 generate 遇模型不可用或端点不可达时抛异常（由调用方捕获回退），不静默吞错。
 *
 * 端到端复测（真实 CodeLlama × LangChain）需本机 Ollama：
 *   bash scripts/pull-codellama.sh && 配置 engine=langchain 后跑 DefectDetectionEvalTest，
 *   见 README「本地 CodeLlama 端到端复测」与《评测报告-综合》FUN-03 说明。
 */
@DisplayName("GAP-044 LangChain 适配器回退链路回归")
class LangChainAdapterTest {

    @Test
    @DisplayName("create 永不抛异常：缺失/非法输入返回非 null 适配器")
    void createNeverThrows() {
        // 空参数与非法端点均不应抛出，返回可用或不可用的适配器（回退契约）
        assertDoesNotThrow(() -> LangChainAdapter.create(null, null, 1));
        assertDoesNotThrow(() -> LangChainAdapter.create("not-a-url", "codellama:7b", 1));
        LangChainAdapter adapter = LangChainAdapter.create(null, null, 1);
        assertNotNull(adapter, "create 必须返回非 null 适配器");
    }

    @Test
    @DisplayName("不可用适配器调用 generate 抛 IllegalStateException")
    void generateOnUnavailableThrows() {
        LangChainAdapter adapter = LangChainAdapter.create(null, null, 1);
        if (adapter.isAvailable()) {
            return; // 本机恰好可用时跳过（不视为失败）
        }
        assertThrows(IllegalStateException.class,
                () -> adapter.generate("system", "user"),
                "不可用适配器调用应抛异常，由调用方回退 self 引擎");
    }

    @Test
    @DisplayName("端点不可达时 generate 抛异常（调用方回退契约，不静默吞错）")
    void generateFailsOnUnreachableEndpoint() {
        LangChainAdapter adapter = LangChainAdapter.create("http://127.0.0.1:1/v1", "codellama:7b", 1);
        if (!adapter.isAvailable()) {
            // 构造即失败（不可用）时 generate 由上一用例覆盖
            return;
        }
        // 不可达端点（本机 127.0.0.1:1）调用应抛异常，证明调用方能捕获并回退
        assertThrows(Exception.class,
                () -> adapter.generate("system prompt", "user prompt"),
                "端点不可达时 generate 应抛异常，由调用方回退 self 引擎");
    }
}
