package com.traceguard.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GAP-046：候选复核规划器（纯函数，可单测）。
 *
 * 背景（性能基准报告-汇总 §5.2）：LLM 二审对全部 R×C 匹配对逐条调用不可扩展
 * （kilo 实测 431 次调用 3646.879s ≈ 60.8min）。生产按「候选复核」收敛：
 * 仅对规则判定的候选缺陷对做 LLM 二审，万行保守上限 200 对 × ≈2.1s/对 ≈ 7min 预算。
 *
 * 候选选取优先级（同优先级内保持输入顺序，保证可复现）：
 *   P1 规则判非一致对（general_inconsistent / serious_inconsistent）
 *   P2 灰色带对（T2 <= totalSimilarity <= T1，规则链的摇摆区）
 *   P3 高风险对（ruleRisk >= highRiskThreshold，规则判一致但风险信号密集）
 *   P4 明确一致池抽检（systematic sampling，比例 consistentSampleRate）——质量探针
 *
 * 升级机制：若探针样本的 LLM 判缺陷率 >= escalateDefectRate，说明"明确一致池"实际并不干净，
 * 按确定性顺序补审剩余池（仍受 maxCandidates 硬上限约束）；小数据集（如 55 对评测）因此
 * 自动趋近全量复核，保持主口径指标，大数据集恒定有界。
 */
public final class CandidateReviewPlanner {

    /** 选取原因（写入 judge_detail.selectedReason，前端溯源面板展示） */
    public static final String REASON_RULE_SUSPECT = "RULE_SUSPECT";
    public static final String REASON_GRAY_BAND = "GRAY_BAND";
    public static final String REASON_HIGH_RISK = "HIGH_RISK";
    public static final String REASON_PROBE_SAMPLE = "PROBE_SAMPLE";
    public static final String REASON_ESCALATED = "ESCALATED";

    private CandidateReviewPlanner() {
    }

    /** 候选对输入（规则链产出的一致性结果投影） */
    public static class PairInput {
        private final Long id;
        private final String ruleStatus;
        private final double totalSimilarity;
        private final double ruleRisk;

        public PairInput(Long id, String ruleStatus, double totalSimilarity, double ruleRisk) {
            this.id = id;
            this.ruleStatus = ruleStatus == null ? "" : ruleStatus;
            this.totalSimilarity = totalSimilarity;
            this.ruleRisk = ruleRisk;
        }

        public Long getId() { return id; }
        public String getRuleStatus() { return ruleStatus; }
        public double getTotalSimilarity() { return totalSimilarity; }
        public double getRuleRisk() { return ruleRisk; }

        public boolean isRuleSuspect() {
            return "general_inconsistent".equals(ruleStatus) || "serious_inconsistent".equals(ruleStatus);
        }
    }

    /** 规划结果：待复核对（有序）+ 选取原因 + 未入选的一致池（升级补审顺序） */
    public static class Plan {
        private final List<Long> reviewOrder;
        private final Map<Long, String> selectedReasons;
        private final List<Long> restConsistent;
        private final int totalPairs;
        private final int suspectCount;

        Plan(List<Long> reviewOrder, Map<Long, String> selectedReasons,
             List<Long> restConsistent, int totalPairs, int suspectCount) {
            this.reviewOrder = reviewOrder;
            this.selectedReasons = selectedReasons;
            this.restConsistent = restConsistent;
            this.totalPairs = totalPairs;
            this.suspectCount = suspectCount;
        }

        /** 待复核对 ID（按优先级排列，含探针样本） */
        public List<Long> getReviewOrder() { return reviewOrder; }

        /** 对 ID -> 选取原因 */
        public Map<Long, String> getSelectedReasons() { return selectedReasons; }

        /** 未入选的明确一致池（升级补审时按此确定性顺序） */
        public List<Long> getRestConsistent() { return restConsistent; }

        /** 全量对数 */
        public int getTotalPairs() { return totalPairs; }

        /** 规则链判非一致的对数（P1） */
        public int getSuspectCount() { return suspectCount; }
    }

    /**
     * 生成候选复核计划。
     *
     * @param pairs               全部匹配对（规则链产出顺序）
     * @param t1                  一致阈值（上方视为明确一致）
     * @param t2                  严重不一致阈值（灰色带下界）
     * @param highRiskThreshold   高风险对复核阈值
     * @param sampleRate          明确一致池抽检比例（0-1，<=0 关闭探针）
     * @param maxCandidates       复核硬上限（含探针；<=0 视为不设限）
     */
    public static Plan plan(List<PairInput> pairs, double t1, double t2,
                            double highRiskThreshold, double sampleRate, int maxCandidates) {
        List<PairInput> suspects = new ArrayList<>();
        List<PairInput> grayBand = new ArrayList<>();
        List<PairInput> highRisk = new ArrayList<>();
        List<PairInput> clearConsistent = new ArrayList<>();
        if (pairs != null) {
            for (PairInput p : pairs) {
                if (p == null) {
                    continue;
                }
                if (p.isRuleSuspect()) {
                    suspects.add(p);
                } else if (p.getTotalSimilarity() <= t1) {
                    // 规则判一致但综合相似度未越过 T1（含 t2 以下的规则漏网），一律进候选
                    grayBand.add(p);
                } else if (p.getRuleRisk() >= highRiskThreshold) {
                    highRisk.add(p);
                } else {
                    clearConsistent.add(p);
                }
            }
        }

        Map<Long, String> reasons = new HashMap<>();
        List<Long> reviewOrder = new ArrayList<>();
        int cap = maxCandidates > 0 ? maxCandidates : Integer.MAX_VALUE;

        for (PairInput p : suspects) {
            if (reviewOrder.size() >= cap) break;
            reviewOrder.add(p.getId());
            reasons.put(p.getId(), REASON_RULE_SUSPECT);
        }
        for (PairInput p : grayBand) {
            if (reviewOrder.size() >= cap) break;
            reviewOrder.add(p.getId());
            reasons.put(p.getId(), REASON_GRAY_BAND);
        }
        for (PairInput p : highRisk) {
            if (reviewOrder.size() >= cap) break;
            reviewOrder.add(p.getId());
            reasons.put(p.getId(), REASON_HIGH_RISK);
        }
        // P4 探针：systematic sampling（步长取整，确定性可复现），补入剩余容量
        List<PairInput> probes = sample(clearConsistent, sampleRate);
        java.util.Set<Long> probeIds = new java.util.HashSet<>();
        for (PairInput p : probes) {
            probeIds.add(p.getId());
        }
        List<Long> rest = new ArrayList<>();
        for (PairInput p : clearConsistent) {
            if (reasons.containsKey(p.getId())) {
                continue;
            }
            if (reviewOrder.size() < cap && probeIds.contains(p.getId())) {
                reviewOrder.add(p.getId());
                reasons.put(p.getId(), REASON_PROBE_SAMPLE);
            } else {
                rest.add(p.getId());
            }
        }
        return new Plan(reviewOrder, reasons, rest,
                pairs == null ? 0 : pairs.size(), suspects.size());
    }

    /**
     * 升级补审：探针缺陷率超阈值时，从 restConsistent 按确定性顺序补入待复核对。
     *
     * @param plan        初始计划
     * @param budget      剩余可补审数量（总上限 - 已复核数，由调用方按配额计算）
     * @return 补审对 ID（有序，可能为空）
     */
    public static List<Long> escalate(Plan plan, int budget) {
        List<Long> out = new ArrayList<>();
        if (plan == null || budget <= 0) {
            return out;
        }
        for (Long id : plan.getRestConsistent()) {
            if (out.size() >= budget) {
                break;
            }
            out.add(id);
        }
        return out;
    }

    /** 探针缺陷率是否触发升级 */
    public static boolean shouldEscalate(int probeDefects, int probeJudged, double escalateDefectRate) {
        return probeJudged > 0 && probeDefects > 0
                && (double) probeDefects / probeJudged >= escalateDefectRate;
    }

    /** systematic sampling：样本数 = ceil(size * rate)，从下标 0 起按等距步长取，保证确定性 */
    static List<PairInput> sample(List<PairInput> pool, double sampleRate) {
        List<PairInput> out = new ArrayList<>();
        if (pool == null || pool.isEmpty() || sampleRate <= 0) {
            return out;
        }
        int want = (int) Math.ceil(pool.size() * Math.min(sampleRate, 1.0));
        int stride = Math.max(1, pool.size() / Math.max(want, 1));
        for (int i = 0; i < pool.size() && out.size() < want; i += stride) {
            out.add(pool.get(i));
        }
        return out;
    }
}
