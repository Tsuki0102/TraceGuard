package com.traceguard.core;

import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Requirement;

/**
 * P2-3：缺陷文案生成器（从 ConsistencyChecker 拆出，职责=缺陷原因/修复建议的规则文案）。
 *
 * 说明：文案内容不影响判定口径，LLM 启用时 AnalysisService 会用大模型结果覆盖这两段文案。
 */
public final class DefectNarrator {

    private DefectNarrator() {}

    /** 缺陷原因：由一致性状态 + 三维分项得分拼装（规则链路） */
    public static String generateDefectReason(ConsistencyResult res, Requirement req, CodeUnit code) {
        StringBuilder reason = new StringBuilder();
        reason.append("需求与代码一致性判定为").append("general_inconsistent".equals(res.getConsistencyStatus()) ? "一般不一致" : "严重不一致").append("。");
        reason.append("综合相似度: ").append(String.format("%.2f", res.getTotalSimilarity())).append("; ");
        reason.append("语义相似度: ").append(String.format("%.2f", res.getSemanticSimilarity())).append("; ");
        reason.append("约束匹配度: ").append(String.format("%.2f", res.getConstraintMatchDegree())).append("; ");
        reason.append("不变量满足度: ").append(String.format("%.2f", res.getInvariantSatisfaction())).append("。");
        if (res.getSemanticSimilarity() < 0.3) {
            reason.append("语义相似度较低，需求与代码实现的业务逻辑可能存在偏差。");
        }
        if (res.getConstraintMatchDegree() < 0.6) {
            reason.append("约束匹配度不足，需求中定义的约束条件在代码中可能未完整实现。");
        }
        return reason.toString();
    }

    /** 修复建议：按薄弱维度给出针对性动作项（规则链路） */
    public static String generateRepairSuggestion(ConsistencyResult res, Requirement req, CodeUnit code) {
        StringBuilder suggestion = new StringBuilder();
        if (res.getSemanticSimilarity() < 0.3) {
            suggestion.append("1. 检查").append(code.getClassName()).append(".").append(code.getMethodName()).append("方法的实现逻辑，确认是否与需求\"").append(req.getRequirementId()).append("\"对应;\n");
            suggestion.append("2. 如方法不对应，请将需求匹配到正确的方法；如方法实现有误，请调整业务逻辑。\n");
        }
        if (res.getConstraintMatchDegree() < 0.6) {
            suggestion.append("3. 检查需求中定义的约束条件（如参数校验、空值检查、异常处理等），确保代码中完整实现。\n");
        }
        if (suggestion.length() == 0) {
            suggestion.append("请对照需求原文检查代码实现，调整业务逻辑、约束条件处理，确保需求完整正确实现。");
        }
        return suggestion.toString();
    }
}
