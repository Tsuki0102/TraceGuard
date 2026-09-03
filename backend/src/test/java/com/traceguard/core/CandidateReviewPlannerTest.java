package com.traceguard.core;

import com.traceguard.core.CandidateReviewPlanner.PairInput;
import com.traceguard.core.CandidateReviewPlanner.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-046：候选复核规划器——优先级选取/上限截断/确定性探针抽样/升级触发 回归测试。
 */
@DisplayName("GAP-046 CandidateReviewPlanner 候选规划回归")
class CandidateReviewPlannerTest {

    private static final double T1 = 0.8;
    private static final double T2 = 0.5;
    private static final double HIGH_RISK = 0.30;

    @Test
    @DisplayName("优先级选取：规则可疑 > 灰色带 > 高风险 > 探针")
    void prioritySelection() {
        List<PairInput> pairs = Arrays.asList(
                new PairInput(0L, "consistent", 0.90, 0.10),                 // 明确一致 -> 探针池
                new PairInput(1L, "consistent", 0.75, 0.10),                 // 灰色带
                new PairInput(2L, "serious_inconsistent", 0.40, 0.50),       // 规则可疑
                new PairInput(3L, "consistent", 0.90, 0.40),                 // 高风险
                new PairInput(4L, "general_inconsistent", 0.60, 0.20));      // 规则可疑
        Plan plan = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.0, 100);

        // 可疑对在前（保持输入顺序 2,4），其后灰带 1、高风险 3；探针比例 0 -> 无探针
        assertThat(plan.getReviewOrder()).containsExactly(2L, 4L, 1L, 3L);
        assertThat(plan.getSelectedReasons().get(2L))
                .isEqualTo(CandidateReviewPlanner.REASON_RULE_SUSPECT);
        assertThat(plan.getSelectedReasons().get(1L))
                .isEqualTo(CandidateReviewPlanner.REASON_GRAY_BAND);
        assertThat(plan.getSelectedReasons().get(3L))
                .isEqualTo(CandidateReviewPlanner.REASON_HIGH_RISK);
        // 明确一致池未入选 -> restConsistent
        assertThat(plan.getRestConsistent()).containsExactly(0L);
        assertThat(plan.getTotalPairs()).isEqualTo(5);
        assertThat(plan.getSuspectCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("硬上限截断：可疑/灰带/高风险占满上限时探针池全部落入 rest")
    void capTruncation() {
        List<PairInput> pairs = Arrays.asList(
                new PairInput(0L, "serious_inconsistent", 0.40, 0.50),
                new PairInput(1L, "consistent", 0.75, 0.10),
                new PairInput(2L, "consistent", 0.95, 0.40),
                new PairInput(3L, "consistent", 0.95, 0.05),
                new PairInput(4L, "consistent", 0.95, 0.05));
        Plan plan = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.5, 3);

        assertThat(plan.getReviewOrder()).containsExactly(0L, 1L, 2L);
        // 上限已满：探针池（3,4）不入选，全部按输入顺序进 rest
        assertThat(plan.getRestConsistent()).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("探针等距抽样：确定性可复现，未抽样对进 rest")
    void probeSamplingDeterministic() {
        List<PairInput> pairs = new java.util.ArrayList<>();
        for (long i = 0; i < 10; i++) {
            pairs.add(new PairInput(i, "consistent", 0.95, 0.05));
        }
        Plan plan = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.2, 100);
        // want = ceil(10*0.2)=2，stride = 10/2 = 5 -> 命中下标 0 与 5
        assertThat(plan.getReviewOrder()).containsExactly(0L, 5L);
        assertThat(plan.getSelectedReasons().get(0L))
                .isEqualTo(CandidateReviewPlanner.REASON_PROBE_SAMPLE);
        assertThat(plan.getRestConsistent()).hasSize(8);
        assertThat(plan.getRestConsistent()).doesNotContain(0L, 5L);

        // 重复规划结果完全一致（确定性）
        Plan again = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.2, 100);
        assertThat(again.getReviewOrder()).isEqualTo(plan.getReviewOrder());
    }

    @Test
    @DisplayName("shouldEscalate：无探针判定不升级，缺陷率达到阈值触发升级")
    void escalateDecision() {
        assertThat(CandidateReviewPlanner.shouldEscalate(0, 0, 0.10)).isFalse();
        assertThat(CandidateReviewPlanner.shouldEscalate(0, 5, 0.10)).isFalse();
        assertThat(CandidateReviewPlanner.shouldEscalate(1, 20, 0.10)).isFalse();
        assertThat(CandidateReviewPlanner.shouldEscalate(2, 20, 0.10)).isTrue();
        assertThat(CandidateReviewPlanner.shouldEscalate(1, 10, 0.10)).isTrue();
    }

    @Test
    @DisplayName("escalate：按 rest 确定性顺序补审，受 budget 截断")
    void escalateBudget() {
        List<PairInput> pairs = new java.util.ArrayList<>();
        for (long i = 0; i < 6; i++) {
            pairs.add(new PairInput(i, "consistent", 0.95, 0.05));
        }
        Plan plan = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.0, 100);
        assertThat(plan.getRestConsistent()).hasSize(6);

        assertThat(CandidateReviewPlanner.escalate(plan, 0)).isEmpty();
        assertThat(CandidateReviewPlanner.escalate(plan, -1)).isEmpty();
        List<Long> out = CandidateReviewPlanner.escalate(plan, 3);
        assertThat(out).containsExactly(0L, 1L, 2L);
        List<Long> full = CandidateReviewPlanner.escalate(plan, 100);
        assertThat(full).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("候选复核关闭（rate=0, cap 不设限）：仅规则可疑/灰带/高风险入选")
    void noProbeMode() {
        List<PairInput> pairs = Arrays.asList(
                new PairInput(0L, "serious_inconsistent", 0.40, 0.50),
                new PairInput(1L, "consistent", 0.95, 0.05));
        Plan plan = CandidateReviewPlanner.plan(pairs, T1, T2, HIGH_RISK, 0.0, 0);
        assertThat(plan.getReviewOrder()).containsExactly(0L);
        assertThat(plan.getRestConsistent()).containsExactly(1L);
    }
}
