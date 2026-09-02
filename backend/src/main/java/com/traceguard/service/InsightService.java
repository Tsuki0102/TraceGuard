package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.common.BusinessException;
import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.FormalSpecification;
import com.traceguard.entity.Project;
import com.traceguard.entity.Requirement;
import com.traceguard.mapper.CodeDefectMapper;
import com.traceguard.mapper.ConsistencyResultMapper;
import com.traceguard.mapper.DefectMapper;
import com.traceguard.mapper.FormalSpecificationMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.mapper.RequirementMapper;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.SecureZipUtil;
import com.traceguard.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 质量洞察聚合服务（W5 波：缺陷趋势 / 跨项目缺陷工单 / 组合简报 / 缺陷模式库）。
 * 数据隔离口径与 DashboardStatsService 一致：管理员看全部，普通用户仅自己的项目（排除回收站）。
 */
@Service
public class InsightService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private DefectMapper defectMapper;

    @Autowired
    private CodeDefectMapper codeDefectMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ConsistencyResultMapper consistencyResultMapper;

    @Autowired
    private FormalSpecificationMapper specMapper;

    @Autowired
    private RequirementMapper requirementMapper;

    @Autowired
    private FileStorageUtil fileStorageUtil;

    @Autowired
    private ProjectService projectService;

    // ==================== 可见性 ====================

    /** 当前用户可见项目（普通用户仅自己的，管理员全部；排除回收站） */
    public List<Project> visibleProjects() {
        LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<>();
        if (!UserContext.isAdmin()) {
            w.eq(Project::getCreateUserId, UserContext.getUserId());
        }
        w.eq(Project::getDeleted, 0);
        w.orderByDesc(Project::getUpdateTime);
        return projectMapper.selectList(w);
    }

    private List<Long> visibleProjectIds() {
        return visibleProjects().stream().map(Project::getId).collect(Collectors.toList());
    }

    private void checkProjectVisible(Long projectId) {
        if (projectId == null) return;
        if (!visibleProjectIds().contains(projectId)) {
            throw new BusinessException(403, "无权访问该项目或项目不存在");
        }
    }

    // ==================== 缺陷趋势（按天 × 等级） ====================

    /**
     * 跨项目缺陷趋势：近 N 天每日 严重/一般 缺陷数 + 缺陷类型 Top。
     * projectId 为空 = 全部可见项目；否则单项目趋势。
     */
    public Map<String, Object> trend(int days, Long projectId) {
        int n = Math.min(Math.max(days, 7), 90);
        checkProjectVisible(projectId);
        LocalDateTime since = LocalDateTime.now().minusDays(n);

        QueryWrapper<Defect> w = new QueryWrapper<>();
        w.select("DATE_FORMAT(create_time,'%Y-%m-%d') AS day", "defect_level AS lvl", "COUNT(*) AS cnt")
                .ge("create_time", since)
                .in("project_id", visibleProjectIds());
        if (projectId != null) {
            w.eq("project_id", projectId);
        }
        w.groupBy("day", "lvl");
        List<Map<String, Object>> rows = defectMapper.selectMaps(w);

        // 连续日期轴（缺数补 0）
        List<String> dayAxis = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            dayAxis.add(LocalDateTime.now().minusDays(i).format(DAY_FMT));
        }
        Map<String, Map<String, Long>> byDay = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String day = String.valueOf(r.get("day"));
            String lvl = r.get("lvl") == null ? "general" : String.valueOf(r.get("lvl"));
            long cnt = ((Number) r.get("cnt")).longValue();
            byDay.computeIfAbsent(day, k -> new HashMap<>()).merge(lvl, cnt, Long::sum);
        }
        // 转为 int：全局 Long→String 序列化会把计数变字符串，前端算术会拼接
        List<Integer> serious = dayAxis.stream().map(d -> (int) (long) byDay.getOrDefault(d, Map.of()).getOrDefault("serious", 0L)).collect(Collectors.toList());
        List<Integer> general = dayAxis.stream().map(d -> (int) (long) byDay.getOrDefault(d, Map.of()).getOrDefault("general", 0L)).collect(Collectors.toList());

        // 类型 Top（同口径）
        QueryWrapper<Defect> wt = new QueryWrapper<>();
        wt.select("defect_type AS type", "COUNT(*) AS cnt")
                .ge("create_time", since)
                .in("project_id", visibleProjectIds());
        if (projectId != null) {
            wt.eq("project_id", projectId);
        }
        wt.groupBy("defect_type").orderByDesc("cnt").last("LIMIT 6");
        List<Map<String, Object>> byType = defectMapper.selectMaps(wt);

        long total = serious.stream().mapToLong(Integer::longValue).sum() + general.stream().mapToLong(Integer::longValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", dayAxis);
        out.put("serious", serious);
        out.put("general", general);
        out.put("total", (int) total);
        out.put("byType", byType);
        return out;
    }

    // ==================== 跨项目缺陷工单看板 ====================

    /**
     * 跨项目缺陷工单：按状态汇总 + 分页明细（带项目名）。
     * status/level/q 为可选过滤；数据隔离见 visibleProjects。
     */
    public Map<String, Object> board(int pageNum, int pageSize, Long projectId, String status, String level, String q) {
        checkProjectVisible(projectId);
        List<Long> ids = projectId != null ? List.of(projectId) : visibleProjectIds();
        if (ids.isEmpty()) {
            return emptyBoard();
        }

        // 状态汇总（不受 status 过滤影响，供看板列计数）
        QueryWrapper<Defect> ws = new QueryWrapper<>();
        ws.select("status AS st", "COUNT(*) AS cnt").in("project_id", ids).groupBy("status");
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r : defectMapper.selectMaps(ws)) {
            String st = r.get("st") == null ? "pending" : String.valueOf(r.get("st"));
            byStatus.put(st, ((Number) r.get("cnt")).longValue());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long fp = byStatus.getOrDefault("falsePositive", 0L);
        long unresolved = total - fp - byStatus.getOrDefault("resolved", 0L) - byStatus.getOrDefault("ignored", 0L);

        // 分页明细
        QueryWrapper<Defect> w = new QueryWrapper<>();
        w.in("project_id", ids);
        if (status != null && !status.isBlank()) {
            w.eq("status", status);
        }
        if (level != null && !level.isBlank()) {
            w.eq("defect_level", level);
        }
        if (q != null && !q.isBlank()) {
            w.and(x -> x.like("defect_reason", q).or().like("defect_type", q).or().like("requirement_text", q));
        }
        w.orderByAsc("defect_level").orderByDesc("create_time");
        Page<Defect> page = defectMapper.selectPage(new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 200)), w);

        Map<Long, String> projectNames = visibleProjects().stream()
                .collect(Collectors.toMap(Project::getId, Project::getProjectName));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Defect d : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("projectId", d.getProjectId());
            m.put("projectName", projectNames.getOrDefault(d.getProjectId(), "未知项目"));
            m.put("level", d.getDefectLevel());
            m.put("type", d.getDefectType());
            m.put("subType", d.getSubType());
            m.put("reason", d.getDefectReason());
            m.put("suggestion", d.getRepairSuggestion());
            m.put("requirement", d.getRequirementText());
            m.put("status", d.getStatus() == null ? "pending" : d.getStatus());
            m.put("handleTime", d.getHandleTime());
            m.put("createTime", d.getCreateTime());
            records.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", (int) total);
        summary.put("pending", (int) (long) byStatus.getOrDefault("pending", 0L));
        summary.put("processing", (int) (long) byStatus.getOrDefault("processing", 0L));
        summary.put("resolved", (int) (long) byStatus.getOrDefault("resolved", 0L));
        summary.put("ignored", (int) (long) byStatus.getOrDefault("ignored", 0L));
        summary.put("falsePositive", (int) fp);
        summary.put("unresolved", (int) unresolved);
        summary.put("fpRate", total == 0 ? 0 : Math.round(fp * 1000.0 / total) / 10.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("total", page.getTotal());
        out.put("pageNum", page.getCurrent());
        out.put("pageSize", page.getSize());
        out.put("records", records);
        return out;
    }

    private Map<String, Object> emptyBoard() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0L);
        summary.put("pending", 0L);
        summary.put("processing", 0L);
        summary.put("resolved", 0L);
        summary.put("ignored", 0L);
        summary.put("falsePositive", 0L);
        summary.put("unresolved", 0L);
        summary.put("fpRate", 0);
        out.put("summary", summary);
        out.put("total", 0L);
        out.put("records", List.of());
        return out;
    }

    // ==================== 组合简报 ====================

    /**
     * 项目组合质量简报：每个可见项目的覆盖/缺陷/未决指标 + 全组合汇总。
     * 健康分由前端既有 healthScore 工具按原始指标计算，口径与工作台一致。
     */
    public Map<String, Object> portfolio() {
        List<Project> projects = visibleProjects();
        List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());

        // 未决（pending/processing）与严重未决按项目聚合
        Map<Long, long[]> openByProject = new HashMap<>(); // [open, seriousOpen]
        if (!ids.isEmpty()) {
            QueryWrapper<Defect> w = new QueryWrapper<>();
            w.select("project_id AS pid", "defect_level AS lvl", "status AS st", "COUNT(*) AS cnt")
                    .in("project_id", ids).groupBy("project_id", "defect_level", "status");
            for (Map<String, Object> r : defectMapper.selectMaps(w)) {
                String st = r.get("st") == null ? "pending" : String.valueOf(r.get("st"));
                if (!"pending".equals(st) && !"processing".equals(st)) continue;
                Long pid = ((Number) r.get("pid")).longValue();
                long cnt = ((Number) r.get("cnt")).longValue();
                long[] arr = openByProject.computeIfAbsent(pid, k -> new long[2]);
                arr[0] += cnt;
                if ("serious".equals(String.valueOf(r.get("lvl")))) arr[1] += cnt;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        double covSum = 0;
        int covN = 0;
        long totalOpen = 0;
        long totalDefects = 0;
        for (Project p : projects) {
            long[] open = openByProject.getOrDefault(p.getId(), new long[2]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("industryType", p.getIndustryType());
            m.put("status", p.getStatus());
            m.put("coverageRate", p.getCoverageRate());
            m.put("defectCount", p.getDefectCount());
            m.put("requirementCount", p.getRequirementCount());
            m.put("openDefects", (int) open[0]);
            m.put("seriousOpen", (int) open[1]);
            m.put("updateTime", p.getUpdateTime());
            rows.add(m);
            // 口径：仅已分析项目计入平均覆盖率（与前端注释一致，避免未分析项目 0% 稀释）
            if ("analyzed".equals(p.getStatus()) && p.getCoverageRate() != null) {
                covSum += p.getCoverageRate();
                covN++;
            }
            totalOpen += open[0];
            totalDefects += p.getDefectCount() == null ? 0 : p.getDefectCount();
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("projects", projects.size());
        totals.put("avgCoverage", covN == 0 ? 0 : Math.round(covSum * 1000.0 / covN) / 1000.0);
        totals.put("totalOpen", (int) totalOpen);
        totals.put("totalDefects", (int) totalDefects);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("projects", rows);
        return out;
    }

    // ==================== 缺陷模式库（代码规则图鉴） ====================

    /** 内置代码检测规则的静态说明（命中数等统计来自真实数据） */
    private static final Map<String, Map<String, String>> RULE_DOCS = new LinkedHashMap<>();

    static {
        RULE_DOCS.put("sql", Map.of("name", "SQL 注入风险", "severity", "serious",
                "desc", "SQL 语句使用字符串拼接外部输入，存在注入风险",
                "advice", "改用参数化查询（MyBatis #{} / PreparedStatement）"));
        RULE_DOCS.put("loop", Map.of("name", "逻辑死循环", "severity", "serious",
                "desc", "while(true) 无退出条件，或循环变量未按预期更新",
                "advice", "补充退出条件、break 守卫或循环变量步进"));
        RULE_DOCS.put("resource", Map.of("name", "资源未释放", "severity", "general",
                "desc", "流/连接等资源未在 try-with-resources 或 finally 中关闭",
                "advice", "使用 try-with-resources 管理资源生命周期"));
    }

    /**
     * 缺陷模式图鉴：代码缺陷类型 × 真实命中统计 + 规则说明 + 最近命中示例。
     * 未在注册表中的类型自动生成通用卡片（数据驱动，不丢失任何真实类型）。
     */
    public Map<String, Object> patterns() {
        List<Long> ids = visibleProjectIds();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            out.put("patterns", List.of());
            out.put("totalHits", 0);
            return out;
        }

        QueryWrapper<CodeDefect> w = new QueryWrapper<>();
        w.select("defect_type AS type", "severity AS sev", "COUNT(*) AS cnt")
                .in("project_id", ids).groupBy("defect_type", "severity");
        Map<String, Map<String, Long>> byType = new LinkedHashMap<>(); // type -> severity -> cnt
        for (Map<String, Object> r : codeDefectMapper.selectMaps(w)) {
            String type = r.get("type") == null ? "未知类型" : String.valueOf(r.get("type"));
            String sev = r.get("sev") == null ? "general" : String.valueOf(r.get("sev"));
            byType.computeIfAbsent(type, k -> new LinkedHashMap<>())
                    .merge(sev, ((Number) r.get("cnt")).longValue(), Long::sum);
        }

        // 最近命中示例（每个类型取最新一条）
        QueryWrapper<CodeDefect> wr = new QueryWrapper<>();
        wr.in("project_id", ids).orderByDesc("create_time").last("LIMIT 200");
        Map<String, Map<String, Object>> example = new LinkedHashMap<>();
        for (CodeDefect c : codeDefectMapper.selectList(wr)) {
            String type = c.getDefectType() == null ? "未知类型" : c.getDefectType();
            if (!example.containsKey(type)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("filePath", c.getFilePath());
                e.put("methodName", c.getMethodName());
                e.put("lineNumber", c.getLineNumber());
                e.put("description", c.getDescription());
                example.put(type, e);
            }
        }

        List<Map<String, Object>> patterns = new ArrayList<>();
        long totalHits = 0;
        for (Map.Entry<String, Map<String, Long>> en : byType.entrySet()) {
            String type = en.getKey();
            Map<String, String> doc = matchDoc(type);
            long hits = en.getValue().values().stream().mapToLong(Long::longValue).sum();
            totalHits += hits;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("name", doc.get("name"));
            m.put("desc", doc.get("desc"));
            m.put("advice", doc.get("advice"));
            m.put("severity", doc.get("severity"));
            m.put("hits", (int) hits);
            m.put("seriousHits", (int) (long) en.getValue().getOrDefault("serious", 0L));
            m.put("example", example.get(type));
            patterns.add(m);
        }
        patterns.sort((a, b) -> Integer.compare((int) b.get("hits"), (int) a.get("hits")));

        out.put("patterns", patterns);
        out.put("totalHits", (int) totalHits);
        return out;
    }

    /** 类型名模糊匹配规则注册表（兼容中英文命名差异），未命中生成通用说明 */
    private Map<String, String> matchDoc(String type) {
        String t = type.toLowerCase();
        for (Map.Entry<String, Map<String, String>> en : RULE_DOCS.entrySet()) {
            String k = en.getKey();
            boolean hit = (k.equals("sql") && (t.contains("sql") || type.contains("注入")))
                    || (k.equals("loop") && (t.contains("loop") || type.contains("死循环") || type.contains("循环")))
                    || (k.equals("resource") && (t.contains("resource") || t.contains("leak")
                        || type.contains("资源") || type.contains("泄露") || type.contains("释放")));
            if (hit) return en.getValue();
        }
        Map<String, String> generic = new LinkedHashMap<>();
        generic.put("name", type);
        generic.put("severity", "general");
        generic.put("desc", "一致性校验产生的缺陷类型");
        generic.put("advice", "参见缺陷详情中的修复建议");
        return generic;
    }
    // ==================== W6：阈值实验室（三维权重/阈值重放） ====================

    private static final String[] BUCKET_LABELS = {"完全一致", "一般不一致", "严重不一致"};

    private static int bucket(double sim, double t1, double t2) {
        if (sim > t1) return 0;
        return sim >= t2 ? 1 : 2;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    /**
     * 阈值重放：对已分析项目，用新 α/β/γ 权重与 T1/T2 阈值重算全部需求-代码对的判定桶，
     * 与默认参数基线对比得出翻转明细。纯库内计算，不触发重新分析。
     */
    public Map<String, Object> replayThresholds(Long projectId, Double alpha, Double beta, Double gamma,
                                                Double t1, Double t2) {
        checkProjectVisible(projectId);
        double a = alpha == null ? 0.4 : alpha;
        double b = beta == null ? 0.35 : beta;
        double g = gamma == null ? 0.25 : gamma;
        double sum = a + b + g;
        if (sum <= 0) { a = 0.4; b = 0.35; g = 0.25; sum = 1.0; }
        a /= sum; b /= sum; g /= sum;
        double th1 = t1 == null ? 0.8 : t1;
        double th2 = Math.min(t2 == null ? 0.5 : t2, th1);

        LambdaQueryWrapper<ConsistencyResult> w = new LambdaQueryWrapper<ConsistencyResult>()
                .eq(ConsistencyResult::getProjectId, projectId)
                .select(ConsistencyResult::getId, ConsistencyResult::getRequirementId,
                        ConsistencyResult::getSemanticSimilarity, ConsistencyResult::getConstraintMatchDegree,
                        ConsistencyResult::getInvariantSatisfaction, ConsistencyResult::getTotalSimilarity);
        List<ConsistencyResult> rows = consistencyResultMapper.selectList(w);

        int[] base = new int[3];
        int[] custom = new int[3];
        int flipCount = 0;
        List<Map<String, Object>> flips = new ArrayList<>();
        for (ConsistencyResult r : rows) {
            Double sv = r.getSemanticSimilarity();
            Double cv = r.getConstraintMatchDegree();
            Double iv = r.getInvariantSatisfaction();
            double storedSim = r.getTotalSimilarity() == null ? 0 : r.getTotalSimilarity();
            double newSim = (sv == null || cv == null || iv == null) ? storedSim : a * sv + b * cv + g * iv;
            // 基线 = 默认权重重算口径（与自定义列同公式），保证默认参数下两列重合、差异全部来自参数变化
            double baseSim = (sv == null || cv == null || iv == null) ? storedSim : 0.4 * sv + 0.35 * cv + 0.25 * iv;
            int b0 = bucket(baseSim, 0.8, 0.5);
            int b1 = bucket(newSim, th1, th2);
            base[b0]++;
            custom[b1]++;
            if (b0 != b1) {
                flipCount++;
                if (flips.size() < 8) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("requirementId", r.getRequirementId());
                    f.put("storedSim", round4(baseSim));
                    f.put("newSim", round4(newSim));
                    f.put("from", BUCKET_LABELS[b0]);
                    f.put("to", BUCKET_LABELS[b1]);
                    flips.add(f);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alpha", round4(a));
        params.put("beta", round4(b));
        params.put("gamma", round4(g));
        params.put("t1", th1);
        params.put("t2", th2);
        out.put("params", params);
        out.put("count", rows.size());
        out.put("baseline", base);
        out.put("custom", custom);
        out.put("flipCount", flipCount);
        out.put("flips", flips);
        return out;
    }

    /** T1 扫描：0.60→0.95 步长 0.05（T2 按 0.5/0.8 比例联动），返回各档判定桶分布 */
    public List<Map<String, Object>> thresholdSweep(Long projectId) {
        checkProjectVisible(projectId);
        LambdaQueryWrapper<ConsistencyResult> w = new LambdaQueryWrapper<ConsistencyResult>()
                .eq(ConsistencyResult::getProjectId, projectId)
                .select(ConsistencyResult::getTotalSimilarity);
        double[] sims = consistencyResultMapper.selectList(w).stream()
                .mapToDouble(r -> r.getTotalSimilarity() == null ? 0 : r.getTotalSimilarity())
                .toArray();
        List<Map<String, Object>> sweep = new ArrayList<>();
        for (int i = 12; i <= 19; i++) {
            double t1 = Math.round(i * 5.0) / 100.0;
            double t2 = Math.round(t1 * 0.625 * 100) / 100.0;
            int pass = 0;
            int mild = 0;
            int severe = 0;
            for (double sSim : sims) {
                int bkt = bucket(sSim, t1, t2);
                if (bkt == 0) {
                    pass++;
                } else if (bkt == 1) {
                    mild++;
                } else {
                    severe++;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t1", t1);
            m.put("t2", t2);
            m.put("pass", pass);
            m.put("mild", mild);
            m.put("severe", severe);
            sweep.add(m);
        }
        return sweep;
    }

    // ==================== W6：Alloy 规约工作台 ====================

    /** 规约清单：项目下全部形式化规约 + 需求摘要 + 校验状态统计 */
    public Map<String, Object> alloySpecs(Long projectId, int limit) {
        checkProjectVisible(projectId);
        int n = Math.min(Math.max(limit, 1), 100);
        List<FormalSpecification> specs = specMapper.selectList(new LambdaQueryWrapper<FormalSpecification>()
                .eq(FormalSpecification::getProjectId, projectId)
                .orderByDesc(FormalSpecification::getUpdateTime)
                .last("LIMIT " + n));

        Set<Long> reqIds = specs.stream()
                .map(FormalSpecification::getRequirementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Requirement> reqMap = reqIds.isEmpty() ? Map.of()
                : requirementMapper.selectBatchIds(reqIds).stream()
                        .collect(Collectors.toMap(Requirement::getId, r -> r));

        List<Map<String, Object>> rows = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int unknown = 0;
        for (FormalSpecification sp : specs) {
            String st = sp.getVerificationStatus() == null ? "" : sp.getVerificationStatus().toLowerCase();
            boolean isPass = st.contains("pass") || (st.contains("sat") && !st.contains("unsat"));
            if (isPass) {
                passed++;
            } else if (st.contains("fail") || st.contains("unsat")) {
                failed++;
            } else {
                unknown++;
            }
            Requirement req = sp.getRequirementId() == null ? null : reqMap.get(sp.getRequirementId());
            String reqText = req == null ? "" : (req.getOriginalText() == null ? "" : req.getOriginalText());
            if (reqText.length() > 90) {
                reqText = reqText.substring(0, 90) + "…";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sp.getId());
            m.put("specId", sp.getSpecId());
            m.put("requirementId", sp.getRequirementId());
            m.put("requirementText", reqText);
            m.put("verificationStatus", sp.getVerificationStatus());
            m.put("alloyCode", sp.getAlloyCode());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("unknown", unknown);
        out.put("specs", rows);
        return out;
    }

    /** 在线试算校验：调用真实 Alloy 求解器，返回结果（不写入档案状态，避免覆盖分析链路产物） */
    public Map<String, Object> verifyAlloySpec(Long specId) {
        FormalSpecification spec = specMapper.selectById(specId);
        if (spec == null) {
            throw new BusinessException(404, "规约不存在");
        }
        checkProjectVisible(spec.getProjectId());
        AlloySpecVerifierUtil.VerifyResult vr = AlloySpecVerifierUtil.verify(spec.getAlloyCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", vr.getEngine());
        out.put("satStatus", vr.getSatStatus());
        out.put("instanceCount", vr.getInstanceCount());
        out.put("elapsedMs", vr.getElapsedMs());
        out.put("passed", vr.isPassed());
        out.put("status", vr.status());
        out.put("summary", vr.summary());
        out.put("message", vr.getMessage());
        out.put("counterexample", vr.getCounterexample());
        return out;
    }

    // ==================== W6：评测中心（标注资产 + 样例导入） ====================

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path samplesRoot() {
        return Paths.get(System.getProperty("user.dir")).resolve("../samples").normalize();
    }

    /** 标注资产盘点：samples 下每个样例的需求行数与标注缺陷类别分布（真实文件解析） */
    public List<Map<String, Object>> evalAssets() {
        List<Map<String, Object>> assets = new ArrayList<>();
        Path root = samplesRoot();
        if (!Files.isDirectory(root)) {
            return assets;
        }
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).sorted().collect(Collectors.toList())) {
                Path defectsFile = dir.resolve("defects.json");
                Path reqFile = dir.resolve("requirements.txt");
                if (!Files.exists(defectsFile) && !Files.exists(reqFile)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", dir.getFileName().toString());
                int reqLines = 0;
                if (Files.exists(reqFile)) {
                    reqLines = (int) Files.readAllLines(reqFile).stream().filter(l -> !l.isBlank()).count();
                }
                m.put("requirementLines", reqLines);
                int defectCount = 0;
                Map<String, Integer> catCount = new LinkedHashMap<>();
                String desc = "";
                if (Files.exists(defectsFile)) {
                    try {
                        Map<?, ?> data = JSON.readValue(defectsFile.toFile(), Map.class);
                        Object ds = data.get("defects");
                        desc = data.get("description") == null ? "" : String.valueOf(data.get("description"));
                        if (ds instanceof List<?> list) {
                            defectCount = list.size();
                            for (Object o : list) {
                                if (o instanceof Map<?, ?> d) {
                                    String cat = d.get("category") == null ? "未分类" : String.valueOf(d.get("category"));
                                    catCount.merge(cat, 1, Integer::sum);
                                }
                            }
                        }
                    } catch (Exception ignore) {
                        // 单个样例数据损坏不阻塞整体盘点
                    }
                }
                m.put("description", desc);
                m.put("defectCount", defectCount);
                m.put("categories", catCount);
                m.put("hasCode", Files.exists(dir.resolve("code.zip")));
                assets.add(m);
            }
        } catch (Exception e) {
            throw new BusinessException(500, "盘点样例资产失败: " + e.getMessage());
        }
        return assets;
    }

    /** 样例一键导入：复制 code.zip + 需求文件到 uploads 并安全解压，创建独立项目 */
    public Project importSample(String key, String projectName) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(400, "样例标识不能为空");
        }
        Path root = samplesRoot();
        Path dir = root.resolve(key).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            throw new BusinessException(400, "未知样例: " + key);
        }
        Path zip = dir.resolve("code.zip");
        if (!Files.exists(zip)) {
            throw new BusinessException(400, "该样例缺少代码包 code.zip");
        }
        try {
            String sub = "samples/" + key + "-" + System.currentTimeMillis();
            String savedZip = fileStorageUtil.saveFile(zip.toFile(), "code.zip", sub);

            // 与分析链路同款安全解压（路径穿越/压缩炸弹防护）
            File extractDir = new File(fileStorageUtil.getUploadPath(), sub + "/code");
            if (!extractDir.exists() && !extractDir.mkdirs()) {
                throw new BusinessException(500, "创建样例解压目录失败");
            }
            SecureZipUtil.safeUnzip(zip.toFile(), extractDir);

            String reqPath = null;
            Path req = dir.resolve("requirements.txt");
            if (Files.exists(req)) {
                reqPath = fileStorageUtil.saveFile(req.toFile(), "requirements.txt", sub);
            }

            Project p = new Project();
            p.setProjectName(projectName == null || projectName.isBlank() ? key : projectName);
            p.setIndustryType("样例基准");
            p.setDescription("从内置样例「" + key + "」一键导入（含标注缺陷集，可直接执行分析）");
            p.setRequirementFilePath(reqPath);
            p.setCodeProjectPath(extractDir.getAbsolutePath());
            return projectService.create(p);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "样例导入失败: " + e.getMessage());
        }
    }
}
