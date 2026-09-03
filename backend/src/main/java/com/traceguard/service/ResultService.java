package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.*;
import com.traceguard.integration.*;
import com.traceguard.mapper.*;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.common.DefectStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResultService {

    @Autowired
    private RequirementMapper requirementMapper;

    @Autowired
    private CodeUnitMapper codeUnitMapper;

    @Autowired
    private CodeDefectMapper codeDefectMapper;

    @Autowired
    private ConsistencyResultMapper consistencyMapper;

    @Autowired
    private DefectMapper defectMapper;

    @Autowired
    private FormalSpecificationMapper specMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;

    @Autowired(required = false)
    private AuditLogMapper auditLogMapper;

    // GAP-009：第三方集成（可选注入，enabled=false时不存在）
    @Autowired(required = false)
    private JiraClient jiraClient;

    @Autowired(required = false)
    private ZentaoClient zentaoClient;

    @Autowired
    private IntegrationProperties integrationProperties;

    public List<Requirement> getRequirements(Long projectId) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Requirement::getProjectId, projectId);
        wrapper.orderByAsc(Requirement::getRequirementId);
        return requirementMapper.selectList(wrapper);
    }

    public Requirement getRequirementById(Long id) {
        return requirementMapper.selectById(id);
    }

    /** GAP-011：按ID查询缺陷（归属校验用） */
    public Defect getDefectById(Long id) {
        return defectMapper.selectById(id);
    }

    public FormalSpecification getSpecByRequirementId(Long requirementId) {
        LambdaQueryWrapper<FormalSpecification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FormalSpecification::getRequirementId, requirementId);
        return specMapper.selectOne(wrapper);
    }

    public FormalSpecification getSpecById(Long id) {
        return specMapper.selectById(id);
    }

    /** 更新Alloy规约代码（FR-REQ-003 在线编辑与手动优化），保存前执行结构与Kripke语义校验 */
    public FormalSpecification updateSpec(Long id, String alloyCode) {
        FormalSpecification spec = specMapper.selectById(id);
        if (spec == null) {
            throw new com.traceguard.common.BusinessException(404, "规约不存在");
        }
        AlloySpecVerifierUtil.VerifyResult vr = AlloySpecVerifierUtil.verify(alloyCode);
        if ("failed".equals(vr.status())) {
            throw new com.traceguard.common.BusinessException(400, "Alloy规约校验失败: " + vr.summary());
        }
        spec.setAlloyCode(alloyCode);
        spec.setVerificationStatus(vr.status());
        spec.setVerificationResult("用户手动编辑，" + vr.summary());
        spec.setOptimizationSuggestion(vr.suggestion());
        specMapper.updateById(spec);
        return spec;
    }

    public List<CodeUnit> getCodeUnits(Long projectId) {
        LambdaQueryWrapper<CodeUnit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeUnit::getProjectId, projectId);
        wrapper.orderByAsc(CodeUnit::getFilePath, CodeUnit::getStartLine);
        return codeUnitMapper.selectList(wrapper);
    }

    /** 代码单元分页查询（结果页服务端分页，避免万行级项目全量拉取） */
    public IPage<CodeUnit> getCodeUnitsPage(Long projectId, int pageNum, int pageSize) {
        LambdaQueryWrapper<CodeUnit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeUnit::getProjectId, projectId);
        wrapper.orderByAsc(CodeUnit::getFilePath, CodeUnit::getStartLine);
        return codeUnitMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public List<ConsistencyResult> getConsistencyResults(Long projectId, Long taskId) {
        LambdaQueryWrapper<ConsistencyResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConsistencyResult::getProjectId, projectId);
        if (taskId != null) {
            wrapper.eq(ConsistencyResult::getTaskId, taskId);
        }
        wrapper.orderByDesc(ConsistencyResult::getTotalSimilarity);
        return consistencyMapper.selectList(wrapper);
    }

    /**
     * A1 判定溯源：任务级引擎决策分布统计（判定溯源面板顶部数据源）。
     * 按 judge_path 分桶计数；judge_path 为 NULL 视为 RULE（纯规则模式产出）。
     */
    public Map<String, Object> getJudgeStats(Long taskId, Long projectId) {
        LambdaQueryWrapper<ConsistencyResult> wrapper = new LambdaQueryWrapper<>();
        if (taskId != null) {
            wrapper.eq(ConsistencyResult::getTaskId, taskId);
        }
        if (projectId != null) {
            wrapper.eq(ConsistencyResult::getProjectId, projectId);
        }
        List<ConsistencyResult> all = consistencyMapper.selectList(wrapper);
        Map<String, Long> byPath = all.stream().collect(Collectors.groupingBy(
                r -> r.getJudgePath() == null || r.getJudgePath().isEmpty() ? "RULE" : r.getJudgePath(),
                Collectors.counting()));
        long llmReviewed = byPath.entrySet().stream()
                .filter(e -> e.getKey().startsWith("LLM"))
                .mapToLong(Map.Entry::getValue).sum();
        long ruleRetained = byPath.getOrDefault("RULE", 0L)
                + byPath.getOrDefault("NOT_REVIEWED", 0L)
                + byPath.getOrDefault("RULE_FALLBACK", 0L);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("byPath", byPath);
        stats.put("llmReviewed", llmReviewed);
        stats.put("ruleRetained", ruleRetained);
        return stats;
    }

    public List<Defect> getDefects(Long projectId, Long taskId, String level) {
        return getDefects(projectId, taskId, level, null, null);
    }

    /** GAP-020：支持按主类型（defectType）与子类型（subType）筛选 */
    public List<Defect> getDefects(Long projectId, Long taskId, String level, String type, String subType) {
        return getDefects(projectId, taskId, level, type, subType, null);
    }

    /** GAP-011：支持按状态（status）筛选 */
    public List<Defect> getDefects(Long projectId, Long taskId, String level, String type, String subType, String status) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getProjectId, projectId);
        if (taskId != null) wrapper.eq(Defect::getTaskId, taskId);
        if (level != null && !level.isEmpty()) wrapper.eq(Defect::getDefectLevel, level);
        if (type != null && !type.isEmpty()) wrapper.eq(Defect::getDefectType, type);
        if (subType != null && !subType.isEmpty()) wrapper.eq(Defect::getSubType, subType);
        if (status != null && !status.isEmpty()) wrapper.eq(Defect::getStatus, status);
        wrapper.orderByAsc(Defect::getDefectLevel);
        return defectMapper.selectList(wrapper);
    }

    public List<CodeDefect> getCodeDefects(Long projectId, Long taskId) {
        LambdaQueryWrapper<CodeDefect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeDefect::getProjectId, projectId);
        if (taskId != null) {
            wrapper.eq(CodeDefect::getTaskId, taskId);
        }
        return codeDefectMapper.selectList(wrapper);
    }

    // ==================== 分页查询方法 ====================

    public IPage<Requirement> getRequirementsPage(Long projectId, int pageNum, int pageSize) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Requirement::getProjectId, projectId);
        wrapper.orderByAsc(Requirement::getRequirementId);
        return requirementMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public IPage<ConsistencyResult> getConsistencyResultsPage(Long projectId, Long taskId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ConsistencyResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConsistencyResult::getProjectId, projectId);
        if (taskId != null) {
            wrapper.eq(ConsistencyResult::getTaskId, taskId);
        }
        wrapper.orderByDesc(ConsistencyResult::getTotalSimilarity);
        return consistencyMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public IPage<Defect> getDefectsPage(Long projectId, Long taskId, String level, int pageNum, int pageSize) {
        return getDefectsPage(projectId, taskId, level, null, null, pageNum, pageSize);
    }

    /** GAP-020：支持按主类型（defectType）与子类型（subType）筛选的分页查询 */
    public IPage<Defect> getDefectsPage(Long projectId, Long taskId, String level, String type, String subType, int pageNum, int pageSize) {
        return getDefectsPage(projectId, taskId, level, type, subType, null, pageNum, pageSize);
    }

    /** GAP-011：支持按状态（status）筛选的分页查询 */
    public IPage<Defect> getDefectsPage(Long projectId, Long taskId, String level, String type, String subType, String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getProjectId, projectId);
        if (taskId != null) wrapper.eq(Defect::getTaskId, taskId);
        if (level != null && !level.isEmpty()) wrapper.eq(Defect::getDefectLevel, level);
        if (type != null && !type.isEmpty()) wrapper.eq(Defect::getDefectType, type);
        if (subType != null && !subType.isEmpty()) wrapper.eq(Defect::getSubType, subType);
        if (status != null && !status.isEmpty()) wrapper.eq(Defect::getStatus, status);
        wrapper.orderByAsc(Defect::getDefectLevel);
        return defectMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public IPage<CodeDefect> getCodeDefectsPage(Long projectId, Long taskId, int pageNum, int pageSize) {
        LambdaQueryWrapper<CodeDefect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeDefect::getProjectId, projectId);
        if (taskId != null) {
            wrapper.eq(CodeDefect::getTaskId, taskId);
        }
        return codeDefectMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Cacheable(cacheNames = "projectStats", key = "#projectId")
    public Map<String, Object> getProjectStatistics(Long projectId) {
        Map<String, Object> stats = new HashMap<>();
        // 使用count查询代替全量列表，减少数据传输
        Long totalReqs = requirementMapper.selectCount(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, projectId));
        List<Defect> defects = getDefects(projectId, null, null);
        Long codeDefectCount = codeDefectMapper.selectCount(new LambdaQueryWrapper<CodeDefect>()
                .eq(CodeDefect::getProjectId, projectId));
        long seriousDefects = defects.stream().filter(d -> "serious".equals(d.getDefectLevel())).count();
        long generalDefects = defects.stream().filter(d -> "general".equals(d.getDefectLevel())).count();
        // 覆盖率口径与AnalysisService.updateProjectStats保持一致：一致配对的需求视为已覆盖（0-100）
        List<ConsistencyResult> consistencyResults = getConsistencyResults(projectId, null);
        long coveredReqs = consistencyResults.stream()
                .filter(r -> "consistent".equals(r.getConsistencyStatus()))
                .map(ConsistencyResult::getRequirementId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        double coverage = totalReqs > 0 ? (double) coveredReqs / totalReqs * 100 : 0;
        stats.put("totalRequirements", totalReqs);
        stats.put("totalDefects", defects.size());
        stats.put("seriousDefects", seriousDefects);
        stats.put("generalDefects", generalDefects);
        stats.put("codeDefects", codeDefectCount);
        stats.put("coverageRate", Math.round(coverage * 100.0) / 100.0);
        Map<String, Long> defectTypeStats = defects.stream()
                .collect(Collectors.groupingBy(Defect::getDefectType, Collectors.counting()));
        stats.put("defectTypeDistribution", defectTypeStats);
        // 代码质量评分（FR-CODE-004）：高危缺陷每个扣10分，中危每个扣3分，下限0分
        List<CodeDefect> allCodeDefects = codeDefectMapper.selectList(new LambdaQueryWrapper<CodeDefect>()
                .eq(CodeDefect::getProjectId, projectId));
        long highCount = allCodeDefects.stream().filter(d -> "high".equals(d.getSeverity())).count();
        long mediumCount = allCodeDefects.stream().filter(d -> "medium".equals(d.getSeverity())).count();
        int qualityScore = (int) Math.max(0, 100 - highCount * 10 - mediumCount * 3);
        stats.put("codeQualityScore", qualityScore);
        stats.put("highCodeDefects", highCount);
        stats.put("mediumCodeDefects", mediumCount);
        // GAP-014：解析失败清单（来自 tg_project.parse_failures JSON 列）
        Project project = projectMapper.selectById(projectId);
        stats.put("parseFailures", project != null ? project.getParseFailures() : null);
        return stats;
    }

    /**
     * 2.7 整改（FR-PLAT-003）：项目质量趋势时间序列。
     * 按项目聚合已完成（completed）分析任务，按结束时间升序，逐任务计算质量指标，
     * 形成可绘制折线趋势图的时间序列（覆盖率、平均综合相似度、缺陷数、严重缺陷数、代码质量分）。
     */
    public List<Map<String, Object>> getQualityTrend(Long projectId) {
        List<AnalysisTask> tasks = analysisTaskMapper.selectList(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getProjectId, projectId)
                        .eq(AnalysisTask::getStatus, "completed")
                        .orderByAsc(AnalysisTask::getEndTime));
        List<Map<String, Object>> trend = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (AnalysisTask task : tasks) {
            Long taskId = task.getId();
            List<ConsistencyResult> results = getConsistencyResults(projectId, taskId);
            List<Defect> defects = getDefects(projectId, taskId, null);
            // 覆盖率：一致配对的需求视为已覆盖
            long coveredReqs = results.stream()
                    .filter(r -> "consistent".equals(r.getConsistencyStatus()))
                    .map(ConsistencyResult::getRequirementId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            long totalReqs = results.stream()
                    .map(ConsistencyResult::getRequirementId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            double coverage = totalReqs > 0 ? (double) coveredReqs / totalReqs * 100 : 0;
            // 平均综合相似度
            double avgSim = results.isEmpty() ? 0 :
                    results.stream().mapToDouble(ConsistencyResult::getTotalSimilarity).average().orElse(0);
            long serious = defects.stream().filter(d -> "serious".equals(d.getDefectLevel())).count();
            long general = defects.stream().filter(d -> "general".equals(d.getDefectLevel())).count();
            // 代码质量分（按该任务代码缺陷）
            List<CodeDefect> taskCodeDefects = codeDefectMapper.selectList(
                    new LambdaQueryWrapper<CodeDefect>().eq(CodeDefect::getTaskId, taskId));
            long high = taskCodeDefects.stream().filter(d -> "high".equals(d.getSeverity())).count();
            long medium = taskCodeDefects.stream().filter(d -> "medium".equals(d.getSeverity())).count();
            int qualityScore = (int) Math.max(0, 100 - high * 10 - medium * 3);
            Map<String, Object> point = new HashMap<>();
            point.put("taskId", taskId);
            point.put("taskName", task.getTaskName());
            point.put("time", task.getEndTime() != null ? task.getEndTime().format(fmt) : "");
            point.put("timestamp", task.getEndTime() != null ? task.getEndTime().toEpochSecond(java.time.ZoneOffset.of("+8")) : 0);
            point.put("coverageRate", Math.round(coverage * 100.0) / 100.0);
            point.put("avgSimilarity", Math.round(avgSim * 1000.0) / 10.0); // 百分比，保留1位
            point.put("totalDefects", defects.size());
            point.put("seriousDefects", serious);
            point.put("generalDefects", general);
            point.put("codeQualityScore", qualityScore);
            trend.add(point);
        }
        return trend;
    }

    /**
     * 多项目对比统计（FR-PLAT-003）：返回各项目的核心质量指标，用于横向对比
     */
    public List<Map<String, Object>> compareProjects(List<Long> projectIds) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Project> projects = projectMapper.selectBatchIds(projectIds);
        for (Project project : projects) {
            Map<String, Object> row = new HashMap<>();
            row.put("projectId", project.getId());
            row.put("projectName", project.getProjectName());
            row.put("industryType", project.getIndustryType());
            row.put("requirementCount", project.getRequirementCount());
            // coverageRate统一由getProjectStatistics提供（0-100），项目表0-1口径不再混入
            row.put("defectCount", project.getDefectCount());
            row.put("status", project.getStatus());
            row.putAll(getProjectStatistics(project.getId()));
            result.add(row);
        }
        return result;
    }

    @Cacheable(cacheNames = "traceability", key = "#projectId")
    public List<Map<String, Object>> getTraceabilityMatrix(Long projectId) {
        List<Map<String, Object>> matrix = new ArrayList<>();
        List<Requirement> reqs = getRequirements(projectId);
        List<Defect> defects = getDefects(projectId, null, null);
        Map<Long, Defect> reqDefectMap = defects.stream()
                .filter(d -> d.getRequirementId() != null)
                .collect(Collectors.toMap(Defect::getRequirementId, d -> d, (a, b) -> a));
        // 一次性查询所有一致性结果并按需求ID分组，避免循环内重复全表查询（N+1问题）
        Map<Long, List<ConsistencyResult>> consistencyByReq = getConsistencyResults(projectId, null).stream()
                .filter(r -> r.getRequirementId() != null)
                .collect(Collectors.groupingBy(ConsistencyResult::getRequirementId));
        // FR-CHECK-004：正向矩阵须含代码文件与代码行号，经codeUnitId一次性回查（避免N+1）
        Map<Long, CodeUnit> codeUnitMap = getCodeUnits(projectId).stream()
                .collect(Collectors.toMap(CodeUnit::getId, u -> u, (a, b) -> a));
        for (Requirement req : reqs) {
            Map<String, Object> row = new HashMap<>();
            row.put("requirementId", req.getRequirementId());
            row.put("requirementText", req.getOriginalText());
            Defect defect = reqDefectMap.get(req.getId());
            List<ConsistencyResult> matches = consistencyByReq
                    .getOrDefault(req.getId(), Collections.emptyList()).stream()
                    .sorted((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()))
                    .limit(3)
                    .collect(Collectors.toList());
            boolean reqMissing = (defect != null && "需求缺失".equals(defect.getDefectType())) || matches.isEmpty();
            if (reqMissing) {
                row.put("status", "missing");
                if (defect != null && "需求缺失".equals(defect.getDefectType())) {
                    row.put("defectLevel", defect.getDefectLevel());
                    row.put("repairSuggestion", defect.getRepairSuggestion());
                }
            } else {
                row.put("status", "covered");
                ConsistencyResult best = matches.get(0);
                row.put("similarity", best.getTotalSimilarity());
                row.put("consistencyStatus", best.getConsistencyStatus());
                // 最佳匹配代码单元的位置信息（字段命名与反向矩阵保持一致）
                CodeUnit cu = codeUnitMap.get(best.getCodeUnitId());
                if (cu != null) {
                    row.put("className", cu.getClassName());
                    row.put("methodName", cu.getMethodName());
                    row.put("filePath", cu.getFilePath());
                    row.put("startLine", cu.getStartLine());
                }
            }
            matrix.add(row);
        }
        return matrix;
    }

    /** FUN-13：反向追溯覆盖判定下限——最佳匹配综合相似度低于该值视为无真正语义对应（判超范围 extra） */
    private static final double MIN_REVERSE_COVERED_SIM = 0.4;

    /**
     * 反向追溯矩阵（FR-TRACE-002 代码->需求方向）：
     * 每个代码单元一行，展示其最佳匹配需求、相似度与覆盖状态（covered/extra 超范围实现）
     */
    @Cacheable(cacheNames = "reverseTraceability", key = "#projectId")
    public List<Map<String, Object>> getReverseTraceabilityMatrix(Long projectId) {
        List<Map<String, Object>> matrix = new ArrayList<>();
        List<CodeUnit> units = getCodeUnits(projectId);
        List<Defect> defects = getDefects(projectId, null, null);
        Map<Long, Defect> codeDefectMap = defects.stream()
                .filter(d -> d.getCodeUnitId() != null)
                .collect(Collectors.toMap(Defect::getCodeUnitId, d -> d, (a, b) -> a));
        Map<Long, Requirement> reqMap = new HashMap<>();
        for (Requirement r : getRequirements(projectId)) {
            reqMap.put(r.getId(), r);
        }
        // 一次性查询所有一致性结果并按代码单元ID分组，避免N+1查询
        Map<Long, List<ConsistencyResult>> consistencyByCode = getConsistencyResults(projectId, null).stream()
                .filter(r -> r.getCodeUnitId() != null)
                .collect(Collectors.groupingBy(ConsistencyResult::getCodeUnitId));
        for (CodeUnit unit : units) {
            // FR-CODE-001 规则3（2.4 整改项）：字段清单单元不进入追溯矩阵（无方法逻辑可比）
            if (com.traceguard.util.JavaCodeParserUtil.isFieldListUnit(unit)) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("className", unit.getClassName());
            row.put("methodName", unit.getMethodName());
            row.put("filePath", unit.getFilePath());
            row.put("startLine", unit.getStartLine());
            Defect defect = codeDefectMap.get(unit.getId());
            List<ConsistencyResult> matches = consistencyByCode
                    .getOrDefault(unit.getId(), Collections.emptyList()).stream()
                    .sorted((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()))
                    .limit(3)
                    .collect(Collectors.toList());
            // FUN-13：覆盖判定需最佳匹配相似度达到下限——无真正语义对应的代码（低分不匹配）判超范围，
            // 而非被低分"不一致"缺陷误判为 covered
            boolean matched = !matches.isEmpty()
                    && matches.get(0).getTotalSimilarity() >= MIN_REVERSE_COVERED_SIM;
            boolean codeExtra = (defect != null && "代码超范围实现".equals(defect.getDefectType())) || !matched;
            if (codeExtra) {
                row.put("status", "extra");
                if (defect != null) {
                    row.put("defectLevel", defect.getDefectLevel());
                    row.put("repairSuggestion", defect.getRepairSuggestion() != null
                            ? defect.getRepairSuggestion()
                            : "该代码单元未对应到任何需求，请确认是否为必要实现或补充需求文档");
                }
            } else {
                row.put("status", "covered");
                ConsistencyResult best = matches.get(0);
                row.put("similarity", best.getTotalSimilarity());
                row.put("consistencyStatus", best.getConsistencyStatus());
                Requirement req = reqMap.get(best.getRequirementId());
                if (req != null) {
                    row.put("requirementId", req.getRequirementId());
                    row.put("requirementText", req.getOriginalText());
                }
            }
            matrix.add(row);
        }
        return matrix;
    }

    /**
     * GAP-026：正向追溯矩阵分页（需求->代码）——支持大数据量远程分页加载。
     */
    public IPage<Map<String, Object>> getTraceabilityMatrixPage(Long projectId, int pageNum, int pageSize) {
        List<Requirement> reqs = getRequirements(projectId);
        List<Defect> defects = getDefects(projectId, null, null);
        Map<Long, Defect> reqDefectMap = defects.stream()
                .filter(d -> d.getRequirementId() != null)
                .collect(Collectors.toMap(Defect::getRequirementId, d -> d, (a, b) -> a));
        Map<Long, List<ConsistencyResult>> consistencyByReq = getConsistencyResults(projectId, null).stream()
                .filter(r -> r.getRequirementId() != null)
                .collect(Collectors.groupingBy(ConsistencyResult::getRequirementId));
        Map<Long, CodeUnit> codeUnitMap = getCodeUnits(projectId).stream()
                .collect(Collectors.toMap(CodeUnit::getId, u -> u, (a, b) -> a));
        
        // 构建所有行数据
        List<Map<String, Object>> allRows = new ArrayList<>();
        for (Requirement req : reqs) {
            Map<String, Object> row = new HashMap<>();
            row.put("requirementId", req.getRequirementId());
            row.put("requirementText", req.getOriginalText());
            Defect defect = reqDefectMap.get(req.getId());
            List<ConsistencyResult> matches = consistencyByReq
                    .getOrDefault(req.getId(), Collections.emptyList()).stream()
                    .sorted((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()))
                    .limit(3)
                    .collect(Collectors.toList());
            boolean reqMissing = (defect != null && "需求缺失".equals(defect.getDefectType())) || matches.isEmpty();
            if (reqMissing) {
                row.put("status", "missing");
                if (defect != null && "需求缺失".equals(defect.getDefectType())) {
                    row.put("defectLevel", defect.getDefectLevel());
                    row.put("repairSuggestion", defect.getRepairSuggestion());
                }
            } else {
                row.put("status", "covered");
                ConsistencyResult best = matches.get(0);
                row.put("similarity", best.getTotalSimilarity());
                row.put("consistencyStatus", best.getConsistencyStatus());
                CodeUnit cu = codeUnitMap.get(best.getCodeUnitId());
                if (cu != null) {
                    row.put("className", cu.getClassName());
                    row.put("methodName", cu.getMethodName());
                    row.put("filePath", cu.getFilePath());
                    row.put("startLine", cu.getStartLine());
                }
            }
            allRows.add(row);
        }
        
        // 手动分页
        int total = allRows.size();
        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageData = fromIndex < total ? allRows.subList(fromIndex, toIndex) : Collections.emptyList();
        
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize, total);
        page.setRecords(pageData);
        return page;
    }

    /**
     * GAP-026：反向追溯矩阵分页（代码->需求）——支持大数据量远程分页加载。
     */
    public IPage<Map<String, Object>> getReverseTraceabilityMatrixPage(Long projectId, int pageNum, int pageSize) {
        List<CodeUnit> units = getCodeUnits(projectId);
        List<Defect> defects = getDefects(projectId, null, null);
        Map<Long, Defect> codeDefectMap = defects.stream()
                .filter(d -> d.getCodeUnitId() != null)
                .collect(Collectors.toMap(Defect::getCodeUnitId, d -> d, (a, b) -> a));
        Map<Long, Requirement> reqMap = new HashMap<>();
        for (Requirement r : getRequirements(projectId)) {
            reqMap.put(r.getId(), r);
        }
        Map<Long, List<ConsistencyResult>> consistencyByCode = getConsistencyResults(projectId, null).stream()
                .filter(r -> r.getCodeUnitId() != null)
                .collect(Collectors.groupingBy(ConsistencyResult::getCodeUnitId));
        
        // 构建所有行数据
        List<Map<String, Object>> allRows = new ArrayList<>();
        for (CodeUnit unit : units) {
            // FR-CODE-001 规则3（2.4 整改项）：字段清单单元不进入追溯矩阵（无方法逻辑可比）
            if (com.traceguard.util.JavaCodeParserUtil.isFieldListUnit(unit)) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("className", unit.getClassName());
            row.put("methodName", unit.getMethodName());
            row.put("filePath", unit.getFilePath());
            row.put("startLine", unit.getStartLine());
            Defect defect = codeDefectMap.get(unit.getId());
            List<ConsistencyResult> matches = consistencyByCode
                    .getOrDefault(unit.getId(), Collections.emptyList()).stream()
                    .sorted((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()))
                    .limit(3)
                    .collect(Collectors.toList());
            // FUN-13：覆盖判定需最佳匹配相似度达到下限——无真正语义对应的代码（低分不匹配）判超范围，
            // 而非被低分"不一致"缺陷误判为 covered
            boolean matched = !matches.isEmpty()
                    && matches.get(0).getTotalSimilarity() >= MIN_REVERSE_COVERED_SIM;
            boolean codeExtra = (defect != null && "代码超范围实现".equals(defect.getDefectType())) || !matched;
            if (codeExtra) {
                row.put("status", "extra");
                if (defect != null) {
                    row.put("defectLevel", defect.getDefectLevel());
                    row.put("repairSuggestion", defect.getRepairSuggestion() != null
                            ? defect.getRepairSuggestion()
                            : "该代码单元未对应到任何需求，请确认是否为必要实现或补充需求文档");
                }
            } else {
                row.put("status", "covered");
                ConsistencyResult best = matches.get(0);
                row.put("similarity", best.getTotalSimilarity());
                row.put("consistencyStatus", best.getConsistencyStatus());
                Requirement req = reqMap.get(best.getRequirementId());
                if (req != null) {
                    row.put("requirementId", req.getRequirementId());
                    row.put("requirementText", req.getOriginalText());
                }
            }
            allRows.add(row);
        }
        
        // 手动分页
        int total = allRows.size();
        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageData = fromIndex < total ? allRows.subList(fromIndex, toIndex) : Collections.emptyList();
        
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize, total);
        page.setRecords(pageData);
        return page;
    }

    /**
     * 清除指定项目的统计与追溯矩阵缓存（供分析任务完成后调用，须通过代理调用方可生效）
     */
    @CacheEvict(cacheNames = {"projectStats", "traceability", "reverseTraceability"}, key = "#projectId")
    public void evictProjectCache(Long projectId) {
        // 仅为触发缓存清除，无需额外逻辑
    }

    // ==================== GAP-011：缺陷状态流转 ====================

    /**
     * GAP-011：更新缺陷状态并写审计日志
     * @return 更新后的 Defect
     */
    public Defect updateDefectStatus(Long defectId, String newStatus, Long userId) {
        Defect defect = defectMapper.selectById(defectId);
        if (defect == null) {
            throw new com.traceguard.common.BusinessException(404, "缺陷不存在");
        }
        // 校验目标状态合法性
        if (!DefectStatus.isValid(newStatus)) {
            throw new com.traceguard.common.BusinessException(400, "非法状态值：" + newStatus);
        }
        String oldStatus = defect.getStatus() != null ? defect.getStatus() : DefectStatus.PENDING.getCode();
        if (!DefectStatus.canTransition(oldStatus, newStatus)) {
            List<String> allowed = DefectStatus.allowedTargets(oldStatus);
            throw new com.traceguard.common.BusinessException(400,
                    "非法流转：" + DefectStatus.labelOf(oldStatus) + " -> " + DefectStatus.labelOf(newStatus)
                    + "。当前状态可用操作：" + String.join("/", allowed.stream().map(DefectStatus::labelOf).toArray(String[]::new)));
        }
        // 项目归属校验已由 Controller 完成（projectService.checkOwnership）；userId 由调用方传入
        defect.setStatus(newStatus);
        defect.setHandleTime(LocalDateTime.now());
        defect.setHandlerId(userId);
        defectMapper.updateById(defect);

        // 写审计日志
        auditLogWriteOld(defectId, oldStatus, newStatus);

        // GAP-009：状态联动钩子（旁路，失败不影响本地流转）
        triggerStatusTransition(defect, newStatus);

        return defect;
    }

    /** 旧版审计写入方式（直接插入） */
    private void auditLogWriteOld(Long defectId, String oldStatus, String newStatus) {
        if (auditLogMapper == null) return;
        try {
            AuditLog log = new AuditLog();
            log.setOperation("DEFECT_STATUS_CHANGE");
            log.setMethod("PUT /api/defects/status/" + defectId);
            log.setPath("/defects/status/" + defectId);
            log.setParams("{\"defectId\":" + defectId + ",\"from\":\"" + oldStatus + "\",\"to\":\"" + newStatus + "\"}");
            log.setSuccess(1);
            log.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(log);
        } catch (Exception e) {
            log.warn("缺陷状态变更审计日志写入失败: {}", e.getMessage());
        }
    }

    // ==================== GAP-009：第三方集成推送方法 ====================

    /**
     * GAP-009：更新需求的远程issue key
     */
    public void updateRequirementRemoteKey(Requirement requirement) {
        requirementMapper.updateById(requirement);
    }

    /**
     * GAP-009：更新缺陷的远程issue key
     */
    public void updateDefectRemoteKey(Defect defect) {
        defectMapper.updateById(defect);
    }

    /**
     * GAP-009：状态联动钩子（旁路操作，失败仅日志提示）
     */
    private void triggerStatusTransition(Defect defect, String newStatus) {
        // remote_issue_key非空才触发
        if (defect.getRemoteIssueKey() == null || defect.getRemoteIssueKey().isEmpty()) {
            return;
        }

        String remoteKey = defect.getRemoteIssueKey();
        RemoteStatus remoteStatus = mapLocalToRemoteStatus(newStatus);

        // 根据remoteKey前缀判断平台
        try {
            if (remoteKey.startsWith("bug-") || remoteKey.startsWith("story-")) {
                // 禅道
                if (zentaoClient != null && zentaoClient.enabled()) {
                    zentaoClient.transitionStatus(remoteKey, remoteStatus);
                    log.info("禅道状态联动成功：{} -> {}", remoteKey, newStatus);
                }
            } else {
                // Jira（格式如 ISSUE-123）
                if (jiraClient != null && jiraClient.enabled()) {
                    jiraClient.transitionStatus(remoteKey, remoteStatus);
                    log.info("Jira状态联动成功：{} -> {}", remoteKey, newStatus);
                }
            }
        } catch (Exception e) {
            log.warn("第三方状态联动失败（不影响本地流转）：{} - {}", remoteKey, e.getMessage());
        }
    }

    /**
     * 本地状态到远程状态映射
     */
    private RemoteStatus mapLocalToRemoteStatus(String localStatus) {
        return switch (localStatus.toLowerCase()) {
            case "processing" -> RemoteStatus.builder().status("IN_PROGRESS").description("进行中").build();
            case "resolved" -> RemoteStatus.builder().status("RESOLVED").description("已解决").build();
            case "ignored" -> RemoteStatus.builder().status("IGNORED").description("不予解决").build();
            default -> RemoteStatus.builder().status(localStatus.toUpperCase()).build();
        };
    }
}
