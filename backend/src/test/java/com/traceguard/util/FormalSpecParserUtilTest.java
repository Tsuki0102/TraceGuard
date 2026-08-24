package com.traceguard.util;

import com.traceguard.util.FormalSpecParserUtil.ConstraintClause;
import com.traceguard.util.FormalSpecParserUtil.ConstraintKind;
import com.traceguard.util.FormalSpecParserUtil.InvariantClause;
import com.traceguard.util.FormalSpecParserUtil.SpecModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FormalSpecParserUtil 单元测试（GAP-005 验证点 1）
 * 规则模板产出样例与 LLM 风格 Alloy 样例：约束/不变量子句提取与分类正确；语法异常输入返回空模型不抛出。
 */
@DisplayName("形式化规约解析（SpecModel）单元测试")
class FormalSpecParserUtilTest {

    @Test
    @DisplayName("规则模板样样例：fact/assert 块子句提取并分类正确")
    void ruleTemplateSpecParsedAndClassified() {
        String alloy =
                "module req_001\n" +
                "sig Order { id: Int, state: one State, amount: Int }\n" +
                "abstract sig State {}\n" +
                "one sig Open extends State {}\n" +
                "one sig Closed extends State {}\n" +
                "fact invariants {\n" +
                "    all o: Order | one o.state\n" +
                "    all o: Order | o.state != none\n" +
                "    all disj o1, o2: Order | o1.id != o2.id\n" +
                "    all o: Order | o.amount >= 0\n" +
                "}\n" +
                "assert consistencyCheck {\n" +
                "    all t, t': Time | transition[t, t'] implies (all o: Order | o.state' in State)\n" +
                "}\n" +
                "check consistencyCheck for 5\n";
        SpecModel model = FormalSpecParserUtil.parse(alloy);
        assertThat(model.getConstraints()).isNotEmpty();
        // 分类：NULL_CHECK（!= none）、UNIQUENESS（disj）、RANGE（>=）
        assertThat(model.getConstraints()).anySatisfy(c ->
                assertThat(c.getKind()).isEqualTo(ConstraintKind.NULL_CHECK));
        assertThat(model.getConstraints()).anySatisfy(c ->
                assertThat(c.getKind()).isEqualTo(ConstraintKind.UNIQUENESS));
        assertThat(model.getConstraints()).anySatisfy(c ->
                assertThat(c.getKind()).isEqualTo(ConstraintKind.RANGE));
        // 可计分子句排除 OTHER
        List<ConstraintClause> countable = model.countableConstraints();
        assertThat(countable).allSatisfy(c -> assertThat(c.getKind()).isNotEqualTo(ConstraintKind.OTHER));
        // 不变量：含 all 量化且涉及状态
        assertThat(model.getInvariants()).isNotEmpty();
        assertThat(model.getInvariants()).anySatisfy(i -> assertThat(i.getClauseText()).contains("all o: Order"));
    }

    @Test
    @DisplayName("LLM 风格 Alloy：EXCEPTION_PATH 与 RESOURCE_RELEASE 约束识别，转移不变量标记 hasTransition")
    void llmStyleSpecParsed() {
        String alloy =
                "module spec\n" +
                "sig Process { state: one State, resource: Resource }\n" +
                "abstract sig State {}\n" +
                "one sig Running extends State {}\n" +
                "one sig Stopped extends State {}\n" +
                "fact invariants {\n" +
                "    all p: Process | p.state != none\n" +
                "    all p: Process | no p.exception\n" +
                "    all p: Process | p.resource in closed\n" +
                "}\n" +
                "fact transition { all p: Process | p.state = Running => p.state' = Stopped }\n";
        SpecModel model = FormalSpecParserUtil.parse(alloy);
        assertThat(model.getConstraints()).anySatisfy(c ->
                assertThat(c.getKind()).isEqualTo(ConstraintKind.EXCEPTION_PATH));
        assertThat(model.getConstraints()).anySatisfy(c ->
                assertThat(c.getKind()).isEqualTo(ConstraintKind.RESOURCE_RELEASE));
        // 转移不变量（=>）标记 hasTransition
        assertThat(model.getInvariants()).anySatisfy(i ->
                assertThat(i.isHasTransition()).isTrue());
    }

    @Test
    @DisplayName("空规约返回空模型不抛异常")
    void emptySpecReturnsEmptyModel() {
        SpecModel model = FormalSpecParserUtil.parse("");
        assertThat(model.getConstraints()).isEmpty();
        assertThat(model.getInvariants()).isEmpty();
        assertThat(FormalSpecParserUtil.parse(null).getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("语法异常输入返回空模型，不抛出未捕获异常")
    void malformedSpecReturnsEmptyModel() {
        String bad = "module spec\n sig Order { this is not valid alloy !!! }\n fact { all o: ";
        assertThatCode(() -> FormalSpecParserUtil.parse(bad)).doesNotThrowAnyException();
        SpecModel model = FormalSpecParserUtil.parse(bad);
        // 提取失败或空均可，但绝不能抛出
        assertThat(model).isNotNull();
    }
}
