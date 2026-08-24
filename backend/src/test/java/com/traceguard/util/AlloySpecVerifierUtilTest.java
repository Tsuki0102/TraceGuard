package com.traceguard.util;

import com.traceguard.config.AlloyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlloySpecVerifierUtil 单元测试（GAP-002 验证点 2，扩展）
 * 开关开启：优先返回 engine=alloy 结果，satStatus/instanceCount/counterexample 字段正确；
 * 开关关闭 / 任务级熔断：返回 engine=structure 结果，与旧版结构校验一致。
 */
@DisplayName("Alloy规约校验器（真实求解优先/结构校验降级）单元测试")
class AlloySpecVerifierUtilTest {

    private static final String VALID_SPEC =
            "module spec\n" +
            "sig Order { id: Int, state: one State }\n" +
            "abstract sig State {}\n" +
            "one sig Initial extends State {}\n" +
            "one sig Completed extends State {}\n" +
            "fact invariants { all o: Order | o.state in Initial + Completed }\n" +
            "assert stateInvariant { all o: Order | o.state in Initial + Completed }\n" +
            "check stateInvariant for 5\n";

    private AlloyProperties enabledProps() {
        AlloyProperties p = new AlloyProperties();
        p.setEnabled(true);
        p.setTimeoutSeconds(30);
        p.setMaxInstances(5);
        p.setMaxScope(6);
        return p;
    }

    private AlloyProperties disabledProps() {
        AlloyProperties p = enabledProps();
        p.setEnabled(false);
        return p;
    }

    @AfterEach
    void clearBreaker() {
        TaskBreakerHolder.clear();
    }

    @Test
    @DisplayName("开关开启：verify 优先返回 engine=alloy，satStatus 正确")
    void enabledPrefersAlloyEngine() {
        AlloyAnalyzerUtil.configure(enabledProps());
        AlloySpecVerifierUtil.configure(new AlloyAnalyzerUtil(), enabledProps());
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.verify(VALID_SPEC);
        assertThat(result.getEngine()).isEqualTo("alloy");
        assertThat(result.getSatStatus()).isIn("SAT", "UNSAT", "UNKNOWN");
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getInstanceCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("开关关闭：verify 回退 engine=structure（旧版行为）")
    void disabledFallsBackToStructure() {
        AlloyAnalyzerUtil.configure(disabledProps());
        AlloySpecVerifierUtil.configure(new AlloyAnalyzerUtil(), disabledProps());
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.verify(VALID_SPEC);
        assertThat(result.getEngine()).isEqualTo("structure");
        assertThat(result.getSatStatus()).isNull();
    }

    @Test
    @DisplayName("任务级熔断开启：即使开关开启也走 engine=structure")
    void circuitOpenForcesStructure() {
        AlloyAnalyzerUtil.configure(enabledProps());
        AlloySpecVerifierUtil.configure(new AlloyAnalyzerUtil(), enabledProps());
        // 模拟任务级熔断：连续失败达阈值
        TaskCircuitBreaker breaker = new TaskCircuitBreaker(AlloySpecVerifierUtil.CIRCUIT_BREAK_THRESHOLD);
        for (int i = 0; i < AlloySpecVerifierUtil.CIRCUIT_BREAK_THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.isOpen()).isTrue();
        TaskBreakerHolder.set(breaker);
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.verify(VALID_SPEC);
        assertThat(result.getEngine()).isEqualTo("structure");
    }

    @Test
    @DisplayName("空规约：engine=structure 且返回 failed（规约内容为空）")
    void emptySpecStructureFailed() {
        AlloyAnalyzerUtil.configure(disabledProps());
        AlloySpecVerifierUtil.configure(null, disabledProps());
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.verify("");
        assertThat(result.getEngine()).isEqualTo("structure");
        assertThat(result.status()).isEqualTo("failed");
    }

    // ==================== 2.3 整改项：优化建议生成具体修复指令 ====================

    @Test
    @DisplayName("2.3 校验通过时优化建议为空")
    void suggestionEmptyWhenPassed() {
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.structureVerify(
                "module spec\n" +
                "sig Order { id: Int, state: one State }\n" +
                "abstract sig State {}\n" +
                "one sig Initial extends State {}\n" +
                "one sig Completed extends State {}\n" +
                "pred init[t: Time] { some o: Order | o.state = Initial and o.createdAt = t }\n" +
                "pred transition[t, t': Time] { all o: Order | o.createdAt = t implies " +
                "  ((o.state = Initial and o.state' = Completed) or (o.state = Completed and o.state' = Completed)) }\n" +
                "fact invariants {\n" +
                "  // INV: 订单状态必须属于已声明状态集\n" +
                "  all o: Order | o.state in Initial + Completed\n" +
                "  all o: Order | one o.state }\n" +
                "assert consistencyCheck { all t, t': Time | transition[t, t'] implies all o: Order | o.state' in Initial + Completed }\n" +
                "check consistencyCheck for 5\n");
        assertThat(result.status()).isEqualTo("passed");
        assertThat(result.suggestion()).isEmpty();
    }

    @Test
    @DisplayName("2.3 缺 module/缺状态时给出具体可落地修复指令")
    void suggestionGivesConcreteFixes() {
        // 缺失 module 与状态声明
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.structureVerify(
                "sig Order { id: Int, state: one State }\n" +
                "abstract sig State {}\n" +
                "pred init[t: Time] { some o: Order | o.state = Initial and o.createdAt = t }\n" +
                "pred transition[t, t': Time] { all o: Order | o.state in State }\n" +
                "fact invariants { all o: Order | o.state in State }\n" +
                "check consistencyCheck for 5\n");
        assertThat(result.status()).isEqualTo("failed");
        String suggestion = result.suggestion();
        // 缺少 module：给出 module 声明修复指令
        assertThat(suggestion).contains("module");
        assertThat(suggestion).contains("module 名称");
        // 未声明任何状态：给出 one sig ... extends State 修复指令
        assertThat(suggestion).contains("one sig");
        assertThat(suggestion).contains("extends State");
        // 每条错误都有具体修复（不再是通用模板话术）
        assertThat(suggestion).doesNotContain("请修正需求描述或规约生成规则");
    }

    @Test
    @DisplayName("2.3 校验失败建议含各错误修复路径（初始状态/不变量/可达性）")
    void suggestionCoversInitAndInvariantAndReachability() {
        AlloySpecVerifierUtil.VerifyResult result = AlloySpecVerifierUtil.structureVerify(
                "module spec\n" +
                "sig Order { id: Int, state: one State }\n" +
                "abstract sig State {}\n" +
                "one sig Initial extends State {}\n" +
                "one sig Completed extends State {}\n" +
                "pred init[t: Time] { some o: Order | o.createdAt = t }\n" +   // 未指明初始状态
                "pred transition[t, t': Time] { all o: Order | o.createdAt = t implies " +
                "  ((o.state = Initial and o.state' = Completed)) }\n" +
                "fact invariants { all o: Order | o.state in State }\n" +     // 仅1条 all，不变量不足 -> warning
                "check consistencyCheck for 5\n");
        assertThat(result.status()).isEqualTo("failed");
        String suggestion = result.suggestion();
        // 初始状态未指明 -> 给出 o.state = 状态名 修复
        assertThat(suggestion).contains("o.state = 状态名");
        // 不变量不足（warning）-> 给出 all 量化子句建议
        assertThat(suggestion).contains("不变量");
    }
}
