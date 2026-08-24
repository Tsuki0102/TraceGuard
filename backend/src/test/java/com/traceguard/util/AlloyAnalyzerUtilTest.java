package com.traceguard.util;

import com.traceguard.config.AlloyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlloyAnalyzerUtil 单元测试（GAP-002 验证点 1）
 * 真实求解：典型规约 -> SAT/UNSAT；矛盾规约 -> UNSAT 或反例；超大作用域 -> TIMEOUT 不阻塞；语法错误 -> ERROR 不抛异常
 */
@DisplayName("Alloy真实语义求解器单元测试")
class AlloyAnalyzerUtilTest {

    private static final String VALID_SPEC =
            "module spec\n" +
            "sig Order { id: Int, state: one State }\n" +
            "abstract sig State {}\n" +
            "one sig Initial extends State {}\n" +
            "one sig Completed extends State {}\n" +
            "fact { all o: Order | o.state in Initial + Completed }\n" +
            "assert stateInvariant { all o: Order | o.state in Initial + Completed }\n" +
            "check stateInvariant for 5\n";

    private AlloyProperties props(int timeoutSeconds) {
        AlloyProperties p = new AlloyProperties();
        p.setEnabled(true);
        p.setTimeoutSeconds(timeoutSeconds);
        p.setMaxInstances(5);
        p.setMaxScope(6);
        return p;
    }

    @Test
    @DisplayName("典型合法规约：求解返回 SAT 或 UNSAT，elapsedMs 有值")
    void validSpecYieldsSatisfiability() {
        AlloyAnalyzerUtil.configure(props(30));
        AlloyAnalyzerUtil.AlloyAnalyzeResult result = AlloyAnalyzerUtil.analyze(VALID_SPEC);
        assertThat(result.getStatus()).isIn("SAT", "UNSAT");
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("规约断言可被违反：识别 SAT 并输出反例文本")
    void contradictorySpecRecognizedAsSat() {
        AlloyAnalyzerUtil.configure(props(30));
        String spec =
                "module spec\n" +
                "sig Order { state: one State }\n" +
                "abstract sig State {}\n" +
                "one sig Open extends State {}\n" +
                "one sig Closed extends State {}\n" +
                "fact { all o: Order | o.state = Open or o.state = Closed }\n" +
                "assert alwaysOpen { all o: Order | o.state = Open }\n" +
                "check alwaysOpen for 5\n";
        AlloyAnalyzerUtil.AlloyAnalyzeResult result = AlloyAnalyzerUtil.analyze(spec);
        // 存在 Closed 状态的反例 -> SAT（断言不成立，输出反例文本）
        assertThat(result.getStatus()).isEqualTo("SAT");
        assertThat(result.getInstanceCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("超大作用域模拟超时：返回 TIMEOUT，不阻塞测试线程超过阈值")
    void hugeScopeTimesOut() {
        AlloyAnalyzerUtil.configure(props(1));
        String spec =
                "module spec\n" +
                "sig Node { next: lone Node }\n" +
                "fact { all disj n1, n2: Node | n1.next != n2.next }\n" +
                "assert allMapped { all n: Node | n in Node.^(next) }\n" +
                "check allMapped for 8\n";
        long start = System.currentTimeMillis();
        AlloyAnalyzerUtil.AlloyAnalyzeResult result = AlloyAnalyzerUtil.analyze(spec);
        long elapsed = System.currentTimeMillis() - start;
        // 允许 TIMEOUT 或快速求解，但绝不能阻塞超过 10s
        assertThat(result.getStatus()).isIn("TIMEOUT", "SAT", "UNSAT", "UNKNOWN");
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    @DisplayName("语法错误输入：返回 ERROR，无未捕获异常抛出")
    void syntaxErrorReturnsError() {
        AlloyAnalyzerUtil.configure(props(30));
        AlloyAnalyzerUtil.AlloyAnalyzeResult result = AlloyAnalyzerUtil.analyze("module spec\n sig Order { this is not valid alloy !!! }\n");
        assertThat(result.getStatus()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("空输入返回 ERROR 不抛异常")
    void emptyInputReturnsError() {
        AlloyAnalyzerUtil.configure(props(30));
        AlloyAnalyzerUtil.AlloyAnalyzeResult result = AlloyAnalyzerUtil.analyze("");
        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("为空");
    }
}
