package com.traceguard.eval;

/**
 * GAP-007：四项验收指标口径计算（纯函数，可独立单测）。
 * 口径定义见《TraceGuard-V1.2-整体改造设计方案》3.9.6 步骤 2：
 *   转换准确率     = 达标条数 / N                      （目标 >= 85%）
 *   缺陷检测准确率 = (TP + TN) / (TP + TN + FP + FN)    （目标 >= 80%）
 *   漏检率         = FN / (TP + FN)                    （目标 <= 15%）
 *   误报率         = FP / (FP + TN)                    （目标 <= 10%）
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /** 转换准确率 = 达标条数 / 总数 */
    public static double conversionAccuracy(long passed, long total) {
        return total == 0 ? 0.0 : (double) passed / total;
    }

    /** 缺陷检测准确率 = (TP + TN) / (TP + TN + FP + FN) */
    public static double defectAccuracy(long tp, long tn, long fp, long fn) {
        long denom = tp + tn + fp + fn;
        return denom == 0 ? 0.0 : (double) (tp + tn) / denom;
    }

    /** 漏检率 = FN / (TP + FN) */
    public static double missRate(long tp, long fn) {
        long denom = tp + fn;
        return denom == 0 ? 0.0 : (double) fn / denom;
    }

    /** 误报率 = FP / (FP + TN) */
    public static double falsePositiveRate(long fp, long tn) {
        long denom = fp + tn;
        return denom == 0 ? 0.0 : (double) fp / denom;
    }

    /**
     * 关键要素覆盖率：keyElements 在生成规约中的命中率（忽略空白差异）。
     * 保留为「字面口径」，与语义化覆盖率并列，便于双口径追溯。
     */
    public static double keyElementCoverage(String generatedCode, java.util.List<String> keyElements) {
        if (keyElements == null || keyElements.isEmpty() || generatedCode == null || generatedCode.isEmpty()) {
            return 0.0;
        }
        String normalized = generatedCode.replaceAll("\\s+", "");
        int hit = 0;
        for (String key : keyElements) {
            if (key == null || key.isEmpty()) continue;
            if (normalized.contains(key.replaceAll("\\s+", ""))) hit++;
        }
        return (double) hit / keyElements.size();
    }

    /**
     * GAP-052：语义化要素覆盖率（主口径）。
     * 不再依赖数据集字面 token，而是解析生成规约是否包含「需求→Kripke 要素」对应的结构化要素，
     * 对规则模板与 LLM 自由生成一视同仁（公平口径）。
     * 要素清单（命中即计 1，权重相同）：
     *   1) module 模块声明
     *   2) 实体签名含状态字段（sig X { ... state ... }）
     *   3) 状态集声明（abstract sig State 或 one sig X extends State）
     *   4) 初始状态谓词（pred init 且指明某状态）
     *   5) 状态转移谓词（pred transition 且含 state'）
     *   6) fact 不变量声明
     *   7) check / assert 一致性断言
     * 返回命中要素数 / 7。
     */
    public static double semanticCoverage(String generatedCode) {
        if (generatedCode == null || generatedCode.trim().isEmpty()) {
            return 0.0;
        }
        // 去掉注释，避免误判
        String code = generatedCode.replaceAll("//.*", "").replaceAll("/\\*[\\s\\S]*?\\*/", "");
        int hit = 0;
        // 1) module 声明
        if (java.util.regex.Pattern.compile("\\bmodule\\s+\\w+").matcher(code).find()) hit++;
        // 2) 实体签名含状态字段：sig X { ... state ... }
        if (java.util.regex.Pattern.compile("\\bsig\\s+\\w+\\s*\\{[^}]*state[^}]*\\}").matcher(code).find()) hit++;
        // 3) 状态集声明
        if (java.util.regex.Pattern.compile("\\babstract\\s+sig\\s+State\\b").matcher(code).find()
                || java.util.regex.Pattern.compile("\\bone\\s+sig\\s+\\w+\\s+extends\\s+State\\b").matcher(code).find()) hit++;
        // 4) 初始状态谓词：pred init 且块内指明某状态（o.state = X 或 state = X）
        java.util.regex.Matcher initM = java.util.regex.Pattern.compile("\\bpred\\s+init\\b[\\s\\S]*?\\}")
                .matcher(code);
        if (initM.find() && java.util.regex.Pattern.compile("state\\s*=\\s*\\w+").matcher(initM.group(0)).find()) hit++;
        // 5) 状态转移谓词含 state'
        java.util.regex.Matcher transM = java.util.regex.Pattern.compile("\\bpred\\s+transition\\b[\\s\\S]*?\\}")
                .matcher(code);
        if (transM.find() && transM.group(0).contains("state'")) hit++;
        // 6) fact 不变量
        if (java.util.regex.Pattern.compile("\\bfact\\b").matcher(code).find()) hit++;
        // 7) check / assert 一致性断言
        if (java.util.regex.Pattern.compile("\\b(check|assert)\\s+\\w+").matcher(code).find()) hit++;
        return (double) hit / 7.0;
    }
}
