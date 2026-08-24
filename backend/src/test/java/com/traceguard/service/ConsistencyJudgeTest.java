package com.traceguard.service;

import com.traceguard.service.ConsistencyJudge.Judgement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TST-03：ConsistencyJudge 核心判定引擎——判定口径回归测试。
 *
 * 覆盖 AUD-02 定稿的判定契约：
 *   1. consistent 显式为布尔；
 *   2. 判不一致必须带四类主类型之一，否则视为解析失败（保留规则判定，返回 null）；
 *   3. 类型规范化 normalizeType 到 GAP-020 四类主类型；
 *   4. executor 不可用时 judge 返回 null（安全降级）。
 */
@DisplayName("TST-03 ConsistencyJudge 判定口径回归")
class ConsistencyJudgeTest {

    @Test
    @DisplayName("解析一致响应：consistent=true 且 defectType 为空")
    void parseConsistent() {
        Judgement j = ConsistencyJudge.parse("{\"consistent\":true,\"reason\":\"实现符合需求\"}");
        assertThat(j).isNotNull();
        assertThat(j.isConsistent()).isTrue();
        assertThat(j.getDefectType()).isEmpty();
        assertThat(j.getReason()).contains("符合");
    }

    @Test
    @DisplayName("解析不一致响应：consistent=false 且带四类主类型")
    void parseInconsistent() {
        Judgement j = ConsistencyJudge.parse("{\"consistent\":false,\"defectType\":\"业务逻辑不一致\",\"reason\":\"阈值反转\"}");
        assertThat(j).isNotNull();
        assertThat(j.isConsistent()).isFalse();
        assertThat(j.getDefectType()).isEqualTo("业务逻辑不一致");
    }

    @Test
    @DisplayName("非 JSON 响应：返回 null（触发降级保留规则判定）")
    void parseNonJsonReturnsNull() {
        assertThat(ConsistencyJudge.parse("抱歉，我无法完成该请求")).isNull();
        assertThat(ConsistencyJudge.parse(null)).isNull();
    }

    @Test
    @DisplayName("判不一致但缺陷类型为空：视为解析失败返回 null")
    void parseInconsistentWithoutTypeReturnsNull() {
        assertThat(ConsistencyJudge.parse("{\"consistent\":false,\"reason\":\"有缺陷\"}")).isNull();
    }

    @Test
    @DisplayName("defectType 缺省时 consistent 默认 true（宽松 JSON）")
    void parseMissingDefectTypeDefaultsConsistent() {
        Judgement j = ConsistencyJudge.parse("{\"reason\":\"仅给理由\"}");
        assertThat(j).isNotNull();
        assertThat(j.isConsistent()).isTrue();
    }

    @Test
    @DisplayName("normalizeType：规范化到 GAP-020 四类主类型")
    void normalizeTypeMapping() {
        assertThat(ConsistencyJudge.normalizeType("需求缺失")).isEqualTo("需求缺失");
        assertThat(ConsistencyJudge.normalizeType("代码超范围实现")).isEqualTo("代码超范围实现");
        assertThat(ConsistencyJudge.normalizeType("超范围")).isEqualTo("代码超范围实现");
        assertThat(ConsistencyJudge.normalizeType("约束条件不满足")).isEqualTo("约束条件不满足");
        assertThat(ConsistencyJudge.normalizeType("参数校验缺失")).isEqualTo("约束条件不满足");
        assertThat(ConsistencyJudge.normalizeType("业务逻辑不一致")).isEqualTo("业务逻辑不一致");
        assertThat(ConsistencyJudge.normalizeType("行为实现错误")).isEqualTo("业务逻辑不一致");
        assertThat(ConsistencyJudge.normalizeType("未知类型")).isEmpty();
        assertThat(ConsistencyJudge.normalizeType(null)).isEmpty();
    }

    @Test
    @DisplayName("executor 为 null 时 judge 返回 null（安全降级）")
    void judgeWithoutExecutorReturnsNull() {
        ConsistencyJudge judge = new ConsistencyJudge(null);
        assertThat(judge.judge("需求", "代码", 0.5, 0.5, 0.5, 0.5, "")).isNull();
    }
}
