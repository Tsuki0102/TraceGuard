package com.traceguard.eval;

import com.traceguard.service.ConsistencyJudge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FUN-03：CodeLlama × LangChain 端到端推理冒烟（单样本）。
 *
 * 在 Ollama + codellama:7b 就绪后运行：
 *   LLM_PROVIDER=codellama LLM_ENGINE=langchain mvn test -Dtest=CodeLlamaE2eSmokeTest
 *
 * 仅对单个需求-代码对发起一次真实推理，验证「LangChain 编排 → 本地 CodeLlama 推理 → 结构化判定解析」
 * 全链路可用（含耗时/成功率观测），避免全量 55 对长时间阻塞；全量评测见 DefectDetectionEvalTest。
 */
@DisplayName("FUN-03 CodeLlama×LangChain 端到端冒烟（单样本）")
class CodeLlamaE2eSmokeTest {

    @Test
    @DisplayName("单次真实推理返回结构化判定")
    void e2eSingleInference() {
        ConsistencyJudge judge = LlmJudgeFactory.build();
        if (judge == null) {
            System.out.println("[CodeLlamaE2E] 无可用 ConsistencyJudge（需设置 LLM_PROVIDER=codellama），跳过");
            return;
        }
        long start = System.currentTimeMillis();
        ConsistencyJudge.Judgement j = judge.judge(
                "系统在库存不足时必须拒绝下单，并返回错误提示；库存充足时正常创建订单。",
                "public boolean createOrder(int stock) { if (stock < 0) { return false; } return true; }",
                0.40, 0.50, 0.60, 0.45, "约束条件不满足");
        long cost = System.currentTimeMillis() - start;
        System.out.println("[CodeLlamaE2E] 单样本推理耗时=" + cost + "ms");
        System.out.println("[CodeLlamaE2E] 判定=" + (j == null ? "null" : j.toString()));

        assertNotNull(j, "CodeLlama×LangChain 端到端应返回结构化判定（耗时 " + cost + "ms，请确认 Ollama 已就绪）");
        assertTrue(j.getReason() != null && !j.getReason().isEmpty(),
                "判定应包含推理依据（reason）");
        System.out.println("[CodeLlamaE2E] 通过：LangChain 编排 → CodeLlama 推理 → 结构化判定解析全链路可用");
    }
}
