package com.traceguard.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-2 + P2-4：缺陷行号定位器单测（AST 优先 + 关键词降级兜底）。
 */
@DisplayName("DefectLocator AST 行号定位")
class DefectLocatorTest {

    @Test
    @DisplayName("约束缺失（MISSING_VALIDATION）：AST 定位到方法体首条语句")
    void constraintUnmetLocatesToFirstBodyStatement() {
        // 约束缺失 -> 应在首条语句之前插入校验
        String code = "public void process(Order o) {\n    System.out.println(o);\n}";
        Integer line = DefectLocator.locate("约束条件不满足", "订单处理失败时需记录异常", code, 100);
        assertThat(line).isEqualTo(101);
    }

    @Test
    @DisplayName("状态类（STATE_FIELD）：AST 定位到 setStatus 赋值行")
    void stateFieldLocatesToSetterLine() {
        String code = "public void publish(Exam e) {\n"
                + "    if (e.getStatus() == PUBLISHED) return;\n"
                + "    e.setStatus(PUBLISHED);\n"
                + "}";
        Integer line = DefectLocator.locate("不变量不满足", "发布后状态为已发布", code, 50);
        // setStatus 在第 3 行 -> 绝对行号 52
        assertThat(line).isEqualTo(52);
    }

    @Test
    @DisplayName("数值类（NUMERIC_LITERAL）：AST 定位到与需求数值冲突的比较行")
    void numericLocatesToConflictingLiteralLine() {
        String code = "public void check() {\n"
                + "    int max = 100;\n"
                + "    if (amount > max) { throw new IllegalStateException(); }\n"
                + "}";
        // 需求要求上限 50，代码常量 max=100 -> 冲突字面量所在行（方法内第 2 行，绝对 201）
        Integer line = DefectLocator.locate("数值越界", "单次充值金额不得超过50元", code, 200);
        assertThat(line).isEqualTo(201);
    }

    @Test
    @DisplayName("未知历史子类型：回退逻辑偏离 -> 方法体首条语句")
    void unknownLabelFallsBackToFirstStatement() {
        String code = "public int calc() {\n    return 0;\n}";
        Integer line = DefectLocator.locate("历史遗留未知类型", null, code, 10);
        assertThat(line).isEqualTo(11);
    }

    @Test
    @DisplayName("startLine 为空：返回方法内 1-based 偏移")
    void noStartLineReturnsOffsetBased() {
        String code = "public int calc() {\n    return 0;\n}";
        Integer line = DefectLocator.locate("业务逻辑不一致", null, code, null);
        assertThat(line).isEqualTo(2);
    }

    @Test
    @DisplayName("空内容：回退到 startLine（无偏移）")
    void emptyContentFallsBackToStartLine() {
        assertThat(DefectLocator.locate("约束条件不满足", "校验", "", 5)).isEqualTo(5);
        assertThat(DefectLocator.locate("约束条件不满足", "校验", null, null)).isNull();
    }

    @Test
    @DisplayName("语法不支持（AST 失败）：降级关键词启发式仍给出方法内行号")
    void unparseableContentDegradesToKeywordHeuristic() {
        // 片段无法解析（孤立的杂散文本）-> 关键字兜底：MISSING_VALIDATION 找 if/check/validate 等
        String code = "public void f() {\n"
                + "    // 带注释的非标准片段 {{{ \n"
                + "    if (x != null) return;\n"
                + "}";
        Integer line = DefectLocator.locate("约束条件不满足", "参数不能为空", code, 7);
        // 若 AST 失败则降级，无论如何都应落在方法行范围 [7, 10]
        assertThat(line).isBetween(7, 10);
    }

    @Test
    @DisplayName("子类型枚举映射：CONSTRAINT_UNMET 对应 MISSING_VALIDATION 策略")
    void enumMapping() {
        assertThat(DefectSubType.fromLabel("约束条件不满足").locator())
                .isEqualTo(LocatorStrategy.MISSING_VALIDATION);
        assertThat(DefectSubType.fromLabel("不变量不满足").locator())
                .isEqualTo(LocatorStrategy.STATE_FIELD);
        assertThat(DefectSubType.fromLabel("需求缺失").locator())
                .isEqualTo(LocatorStrategy.METHOD_START);
        assertThat(DefectSubType.fromLabel("不存在的历史值")).isNull();
    }
}
