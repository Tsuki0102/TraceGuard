package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4：缺陷风险信号权重机制与 explainSignals 信号分解单测。
 * 注意：本测试会改写静态权重，结尾必须复位默认，避免影响其它用例。
 */
@DisplayName("缺陷风险信号权重与信号分解")
class CodeDefectPatternDetectorTest {

    private final String req = "退款申请需校验金额不得超过原金额且状态必须为PAID";
    private final String code = "public boolean refund(String orderId, double amount) { if (amount <= 0) return false; return doRefund(orderId, amount * 0.9); }";

    @Test
    @DisplayName("explainSignals 输出全部 10 个信号名且含量化边界/数值信号")
    void explainSignalsContainsAllKnownSignals() {
        Map<String, Double> signals = CodeDefectPatternDetector.explainSignals(req, code, null);
        assertThat(signals.keySet()).containsExactlyInAnyOrderElementsOf(CodeDefectPatternDetector.riskSignalNames());
        // 需求含"金额/原金额"，代码乘 0.9 -> refundFactor 应命中
        assertThat(signals.getOrDefault("refundFactor", 0.0)).isGreaterThan(0);
    }

    @Test
    @DisplayName("riskSignalWeights 返回全量信号快照，且包含内置默认（去噪 + 升权）")
    void weightSnapshotContainsDefaults() {
        Map<String, Double> w = CodeDefectPatternDetector.riskSignalWeights();
        assertThat(w.keySet()).containsExactlyInAnyOrderElementsOf(CodeDefectPatternDetector.riskSignalNames());
        // 内置默认：噪声信号 0、强信号放大
        assertThat(w.get("stateMismatch")).isEqualTo(0.0);
        assertThat(w.get("impliedBusinessRuleMissing")).isEqualTo(0.0);
        assertThat(w.get("numericMismatch")).isEqualTo(2.0);
        assertThat(w.get("quantitativeBoundMismatch")).isEqualTo(1.5);
    }

    @Test
    @DisplayName("configureRiskWeights 热更新生效，且可复位默认")
    void weightsHotReloadAndRestore() {
        Map<String, Double> onlyNumeric = new java.util.HashMap<>();
        onlyNumeric.put("numericMismatch", 3.0);
        // 只保留一个强信号，其余 weightOf 缺省 1.0
        CodeDefectPatternDetector.configureRiskWeights(onlyNumeric);
        try {
            Map<String, Double> w = CodeDefectPatternDetector.riskSignalWeights();
            assertThat(w.get("numericMismatch")).isEqualTo(3.0);
            assertThat(w.get("refundFactor")).isEqualTo(1.0);
            assertThat(w.get("stateMismatch")).isEqualTo(1.0); // 缺省即 1.0
        } finally {
            CodeDefectPatternDetector.configureRiskWeights(CodeDefectPatternDetector.baselineRiskWeights());
        }
        // 复位默认后应为 P1-4 网格实证基线（去噪 + 温和升权）的全量快照
        Map<String, Double> expected = new java.util.HashMap<>();
        expected.put("stateMismatch", 0.0);
        expected.put("numericMismatch", 2.0);
        expected.put("paramValidationMissing", 1.0);
        expected.put("logicInversion", 1.0);
        expected.put("commonCodeBug", 1.0);
        expected.put("impliedBusinessRuleMissing", 0.0);
        expected.put("nullDereference", 1.0);
        expected.put("stateFlowViolation", 1.0);
        expected.put("refundFactor", 1.0);
        expected.put("quantitativeBoundMismatch", 1.5);
        assertThat(CodeDefectPatternDetector.riskSignalWeights()).isEqualTo(expected);
    }
}
