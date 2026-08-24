package com.traceguard.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-007 验证点 1（设计 3.9.11）：指标计算正确性单测。
 * 构造已知 TP/FP/FN/TN，断言四指标计算值与手算一致。
 */
@DisplayName("GAP-007 指标计算正确性")
class EvalMetricsTest {

    @Test
    @DisplayName("缺陷检测四指标：已知 TP/FP/FN/TN 与手算一致")
    void defectMetricsMatchHandCalc() {
        long tp = 8, tn = 40, fp = 2, fn = 1;

        // (TP+TN)/(TP+TN+FP+FN) = 48/51
        assertThat(EvalMetrics.defectAccuracy(tp, tn, fp, fn)).isEqualTo(48.0 / 51);
        // FN/(TP+FN) = 1/9
        assertThat(EvalMetrics.missRate(tp, fn)).isEqualTo(1.0 / 9);
        // FP/(FP+TN) = 2/42
        assertThat(EvalMetrics.falsePositiveRate(fp, tn)).isEqualTo(2.0 / 42);
    }

    @Test
    @DisplayName("转换准确率：达标条数/总数")
    void conversionAccuracyHandCalc() {
        assertThat(EvalMetrics.conversionAccuracy(36, 40)).isEqualTo(0.9);
        assertThat(EvalMetrics.conversionAccuracy(0, 5)).isEqualTo(0.0);
        assertThat(EvalMetrics.conversionAccuracy(5, 5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("零分母场景：不抛异常且返回 0")
    void zeroDenominator() {
        assertThat(EvalMetrics.defectAccuracy(0, 0, 0, 0)).isEqualTo(0.0);
        assertThat(EvalMetrics.missRate(0, 0)).isEqualTo(0.0);
        assertThat(EvalMetrics.falsePositiveRate(0, 0)).isEqualTo(0.0);
        assertThat(EvalMetrics.conversionAccuracy(0, 0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("边界：全部检出 / 全部漏检")
    void boundaryCases() {
        // 全 TP：漏检率 0，误报率 0（无 TN/FP）
        assertThat(EvalMetrics.missRate(10, 0)).isEqualTo(0.0);
        assertThat(EvalMetrics.falsePositiveRate(0, 0)).isEqualTo(0.0);
        // 全 FN：漏检率 1
        assertThat(EvalMetrics.missRate(0, 10)).isEqualTo(1.0);
        // 全 FP：误报率 1（无 TN）
        assertThat(EvalMetrics.falsePositiveRate(10, 0)).isEqualTo(1.0);
    }
}
