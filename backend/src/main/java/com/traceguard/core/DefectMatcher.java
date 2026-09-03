package com.traceguard.core;

import cn.hutool.core.util.StrUtil;
import com.traceguard.config.ThresholdConfigHolder;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P2-3：缺陷匹配与生成器（从 ConsistencyChecker 拆出，职责=一致性状态判定 / 缺陷类型判定 / 缺陷清单生成）。
 *
 * 保持 generateDefects 的对外签名与语义不变：
 * <ul>
 *   <li>全局按综合相似度降序贪心匹配（P1-2 匈牙利重构的落点，暂未改动）；</li>
 *   <li>不一致对 -> 缺陷；未匹配需求 -> 需求缺失；未匹配代码 -> 代码超范围实现（含 FUN-04 负例抑制）。</li>
 * </ul>
 *
 * P2-4：缺陷子类型已枚举化（{@link DefectSubType}），落库仍写中文 label，存量数据零迁移。
 */
public final class DefectMatcher {

    private DefectMatcher() {}

    /**
     * 一致判定唯一口径：Sim &gt; T1 判完全一致（严格大于，对齐 SRS FR-CHECK-002 业务规则1）。
     * determineStatus 与 determineDefectType 必须共用本口径，避免边界（sim == t1）处
     * 状态判"不一致"而缺陷类型返回空的自相矛盾（P0-3 修复）。
     */
    public static boolean isConsistent(double sim, double t1) {
        return sim > t1;
    }

    /**
     * 一致性分级判定（对齐 SRS FR-CHECK-002 业务规则1）：
     * Sim &gt; T1 → 完全一致；T2 ≤ Sim ≤ T1 → 一般不一致；Sim &lt; T2 → 严重不一致。
     */
    public static String determineStatus(double sim, double t1, double t2) {
        return determineStatus(sim, t1, t2, false);
    }

    /**
     * B2 风险门控双通道判定（2026-09-03）：风险通道检出（defectRisk >= risk-gate）时，
     * 即使综合分仍在一致区（sim > t1）也判 general_inconsistent——独立风险信号覆盖
     * 线性分数通道的漏检；严重度分级仍由 sim 与 t2 决定。
     */
    public static String determineStatus(double sim, double t1, double t2, boolean riskDetected) {
        if (!riskDetected && isConsistent(sim, t1)) return "consistent";
        if (sim >= t2) return "general_inconsistent";
        return "serious_inconsistent";
    }

    /**
     * GAP-020：determineDefectType 返回 [主类型, 子类型] 二元组。
     * 主类型统一为 FR-CHECK-003 的 4 类口径，子类型保留原细分标签（label 来自 {@link DefectSubType}）。
     */
    public static String[] determineDefectType(double totalSim, double cosSim, double conMatch,
                                               double invSat, double t1, double t2) {
        return determineDefectType(totalSim, cosSim, conMatch, invSat, t1, t2, false);
    }

    /** B2 双通道重载：riskDetected=true 时分数通道判一致的对也进入子类型分流（风险信号解释见 risk_signals） */
    public static String[] determineDefectType(double totalSim, double cosSim, double conMatch,
                                               double invSat, double t1, double t2, boolean riskDetected) {
        if (!riskDetected && isConsistent(totalSim, t1)) return new String[]{"", ""};
        // GAP-046：约束覆盖率（需求约束点×代码证据）是强判别信号，优先于语义相关性。
        // 需求要求约束而代码未实现 -> 约束条件不满足；语义弱相关（词面不重叠）但在约束满足前提下 -> 逻辑偏离。
        // CQ-06：子阈值经 ThresholdConfigHolder 配置化（默认 0.6/0.7/0.2，与历史行为一致）
        DefectSubType subType;
        if (conMatch < ThresholdConfigHolder.conMatchMin()) {
            subType = DefectSubType.CONSTRAINT_UNMET;
        } else if (invSat < ThresholdConfigHolder.invSatMin()) {
            subType = DefectSubType.INVARIANT_VIOLATION;
        } else if (cosSim < ThresholdConfigHolder.cosSimMin()) {
            subType = DefectSubType.LOGIC_DEVIATION;
        } else {
            subType = DefectSubType.REQ_CODE_MISMATCH;
        }
        String mainType = DefectTypes.toMainType(subType.label(), invSat, conMatch);
        return new String[]{mainType, subType.label()};
    }

    /**
     * 由一致性结果生成缺陷清单（不一致缺陷 / 需求缺失 / 代码超范围实现）。
     *
     * @param outOfScopeSimLimit FUN-04 代码超范围实现的负例抑制下限
     */
    public static List<Defect> generateDefects(Long taskId, Long projectId, List<ConsistencyResult> results,
                                               List<Requirement> requirements, List<CodeUnit> codeUnits,
                                               double outOfScopeSimLimit) {
        List<Defect> defects = new ArrayList<>();
        Map<Long, Requirement> reqMap = new HashMap<>();
        for (Requirement r : requirements) reqMap.put(r.getId(), r);
        Map<Long, CodeUnit> codeMap = new HashMap<>();
        for (CodeUnit c : codeUnits) codeMap.put(c.getId(), c);
        Set<Long> matchedReqs = new HashSet<>();
        Set<Long> matchedCodes = new HashSet<>();
        results.sort((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()));
        for (ConsistencyResult res : results) {
            if ("serious_inconsistent".equals(res.getConsistencyStatus()) ||
                "general_inconsistent".equals(res.getConsistencyStatus())) {
                if (matchedReqs.contains(res.getRequirementId()) || matchedCodes.contains(res.getCodeUnitId())) {
                    continue;
                }
                Requirement req = reqMap.get(res.getRequirementId());
                CodeUnit code = codeMap.get(res.getCodeUnitId());
                if (req == null || code == null) continue;
                // P0-3 防御：状态判不一致但类型为空（理论边界残留）时兜底为业务逻辑不一致，禁止产出空类型缺陷
                String mainType = StrUtil.isBlank(res.getDefectType())
                        ? DefectTypes.LOGIC_MISMATCH : res.getDefectType();
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setConsistencyResultId(res.getId());
                defect.setRequirementId(res.getRequirementId());
                defect.setCodeUnitId(res.getCodeUnitId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("serious_inconsistent".equals(res.getConsistencyStatus()) ? "serious" : "general");
                defect.setDefectType(mainType);
                defect.setSubType(res.getDefectSubType() != null ? res.getDefectSubType() : mainType);
                defect.setDefectReason(DefectNarrator.generateDefectReason(res, req, code));
                defect.setRepairSuggestion(DefectNarrator.generateRepairSuggestion(res, req, code));
                defect.setCodeSnippet(code.getCodeContent() != null ?
                        code.getCodeContent().substring(0, Math.min(500, code.getCodeContent().length())) : "");
                defect.setRequirementText(req.getOriginalText());
                // P2-2：AST 定位（按子类型枚举映射策略，AST 失败降级关键词启发式）
                defect.setDefectLine(DefectLocator.locate(res.getDefectSubType(), req.getOriginalText(),
                        code.getCodeContent(), code.getStartLine()));
                // A1 判定溯源：继承关联一致性结果的决策路径与明细，供 Defects.vue 溯源面板展示
                defect.setJudgePath(res.getJudgePath());
                defect.setJudgeDetail(res.getJudgeDetail());
                defects.add(defect);
                matchedReqs.add(res.getRequirementId());
                matchedCodes.add(res.getCodeUnitId());
            } else if ("consistent".equals(res.getConsistencyStatus())) {
                matchedReqs.add(res.getRequirementId());
                matchedCodes.add(res.getCodeUnitId());
            }
        }
        appendMissingRequirements(taskId, projectId, defects, requirements, matchedReqs);
        appendOutOfScopeCode(taskId, projectId, defects, results, codeUnits, matchedCodes, outOfScopeSimLimit);
        return defects;
    }

    /** 未匹配需求 -> 需求缺失缺陷 */
    private static void appendMissingRequirements(Long taskId, Long projectId, List<Defect> defects,
                                                  List<Requirement> requirements, Set<Long> matchedReqs) {
        for (Requirement req : requirements) {
            if (!matchedReqs.contains(req.getId())) {
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setRequirementId(req.getId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("serious");
                defect.setDefectType(DefectTypes.MISSING_REQUIREMENT);
                defect.setSubType(DefectSubType.MISSING_REQUIREMENT.label());
                defect.setDefectReason("需求\"" + req.getRequirementId() + "\"在代码中未找到对应实现");
                defect.setRepairSuggestion("请根据需求补充相应的代码实现");
                defect.setRequirementText(req.getOriginalText());
                defects.add(defect);
            }
        }
    }

    /**
     * 未匹配代码 -> 代码超范围实现缺陷（FUN-04 负例抑制：
     * 与任一需求的最大相似度 ≥ 下限时视为"覆盖不足"而非超范围，不产出缺陷）。
     */
    private static void appendOutOfScopeCode(Long taskId, Long projectId, List<Defect> defects,
                                             List<ConsistencyResult> results, List<CodeUnit> codeUnits,
                                             Set<Long> matchedCodes, double outOfScopeSimLimit) {
        Map<Long, Double> codeMaxSim = new HashMap<>();
        for (ConsistencyResult res : results) {
            if (res.getCodeUnitId() != null) {
                codeMaxSim.merge(res.getCodeUnitId(), res.getTotalSimilarity(), Math::max);
            }
        }
        for (CodeUnit code : codeUnits) {
            if (!matchedCodes.contains(code.getId())) {
                double maxSim = codeMaxSim.getOrDefault(code.getId(), 0.0);
                if (maxSim >= outOfScopeSimLimit) {
                    continue;
                }
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setCodeUnitId(code.getId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("general");
                defect.setDefectType(DefectTypes.OVER_IMPLEMENTATION);
                defect.setSubType(DefectSubType.OVER_IMPLEMENTATION.label());
                defect.setDefectReason("代码方法" + code.getClassName() + "." + code.getMethodName() + "未对应到任何需求条目");
                defect.setRepairSuggestion("请确认该方法是否为必要实现，或补充对应的需求文档");
                defect.setCodeSnippet(code.getCodeContent() != null ?
                        code.getCodeContent().substring(0, Math.min(500, code.getCodeContent().length())) : "");
                defect.setDefectLine(code.getStartLine() != null && code.getStartLine() > 0 ? code.getStartLine() : null);
                defects.add(defect);
            }
        }
    }
}
