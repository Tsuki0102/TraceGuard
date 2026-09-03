package com.traceguard.service.report;

import com.traceguard.entity.*;
import java.util.List;
import java.util.Map;

/**
 * GAP-010：报告渲染上下文（一次查询，多章节复用）
 * 聚合 project/stats/defects/codeDefects/matrix 查询结果
 */
public class ReportContext {
    public final Project project;
    public final Map<String, Object> stats;
    public final List<Defect> defects;
    public final List<CodeDefect> codeDefects;
    public final List<Map<String, Object>> traceabilityMatrix;
    /** A1 判定溯源：引擎决策分布（getJudgeStats 产出），规则模式/无匹配对时 total=0 */
    public final Map<String, Object> judgeStats;

    public ReportContext(Project project, Map<String, Object> stats,
                         List<Defect> defects, List<CodeDefect> codeDefects,
                         List<Map<String, Object>> traceabilityMatrix) {
        this(project, stats, defects, codeDefects, traceabilityMatrix, null);
    }

    public ReportContext(Project project, Map<String, Object> stats,
                         List<Defect> defects, List<CodeDefect> codeDefects,
                         List<Map<String, Object>> traceabilityMatrix,
                         Map<String, Object> judgeStats) {
        this.project = project;
        this.stats = stats;
        this.defects = defects;
        this.codeDefects = codeDefects;
        this.traceabilityMatrix = traceabilityMatrix;
        this.judgeStats = judgeStats;
    }
}
