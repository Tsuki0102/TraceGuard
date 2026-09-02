package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.entity.*;
import com.traceguard.mapper.*;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.UserContext;
import com.traceguard.websocket.ProgressWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作台聚合服务（W2 波：KPI 趋势 / 动态流 / 使用趋势 / 通知 / 全局搜索 / 系统状态 / 质量门槛）
 * 数据隔离口径与 ProjectController 一致：普通用户仅可见自己创建的项目数据，管理员可见全部。
 */
@Service
public class DashboardStatsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardStatsService.class);
    private static final DecimalFormat D = new DecimalFormat("0.#");
    /** 质量门槛默认阈值（百分制），管理员可通过 sys_config.quality_gate 覆盖 */
    private static final String GATE_DEFAULT = "{\"coverage\":80,\"consistency\":80,\"seriousLimit\":0}";

    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private RequirementMapper requirementMapper;
    @Autowired
    private DefectMapper defectMapper;
    @Autowired
    private SystemConfigMapper systemConfigMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private FileStorageUtil fileStorageUtil;

    @Value("${traceguard.storage.backup-path:./backups/}")
    private String backupPath;

    // ==================== 可见性 ====================

    private boolean isAdmin() {
        return UserContext.isAdmin();
    }

    /** 当前用户可见项目（普通用户仅自己的，管理员全部；排除回收站） */
    private List<Project> visibleProjects() {
        LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<>();
        if (!isAdmin()) {
            w.eq(Project::getCreateUserId, UserContext.getUserId());
        }
        return projectMapper.selectList(w);
    }

    // ==================== W2-01：KPI 概览 + 周同比 ====================

    /** 当前用户可见项目 id 列表（管理员传 null=全量；普通用户无项目返回空列表） */
    private List<Long> visibleProjectIds(boolean admin) {
        if (admin) return null;
        return visibleProjects().stream().map(Project::getId).collect(Collectors.toList());
    }

    /**
     * 工作台统计：项目数/已分析/平均覆盖率/累计缺陷 + 本周 vs 上周"已启动分析次数"趋势。
     * 趋势口径：分析任务条数（本周至今 vs 上周同期），避免编造无历史快照的假同比。
     * 数据隔离：普通用户的任务趋势仅统计其可见项目。
     */
    public Map<String, Object> overview() {
        boolean admin = isAdmin();
        List<Project> projects = visibleProjects();
        List<Project> analyzed = projects.stream().filter(p -> "analyzed".equals(p.getStatus())).collect(Collectors.toList());
        int totalDefects = projects.stream().mapToInt(p -> p.getDefectCount() == null ? 0 : p.getDefectCount()).sum();
        double avgCoverage = analyzed.stream()
                .filter(p -> p.getCoverageRate() != null)
                .mapToDouble(p -> p.getCoverageRate() * 100)
                .average().orElse(0);

        LocalDate today = LocalDate.now();
        LocalDateTime weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime lastWeekStart = weekStart.minusWeeks(1);
        LocalDateTime todayStart = today.plusDays(1).atStartOfDay();
        List<Long> ids = visibleProjectIds(admin);
        Long thisWeek;
        Long lastWeek;
        if (!admin && ids.isEmpty()) {
            thisWeek = 0L;
            lastWeek = 0L;
        } else {
            thisWeek = analysisTaskMapper.countTasksBetween(weekStart, todayStart, ids);
            lastWeek = analysisTaskMapper.countTasksBetween(lastWeekStart, weekStart, ids);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("projectCount", projects.size());
        data.put("analyzedCount", analyzed.size());
        data.put("avgCoverage", Math.round(avgCoverage));
        data.put("totalDefects", totalDefects);
        data.put("thisWeekTasks", thisWeek != null ? thisWeek : 0L);
        data.put("lastWeekTasks", lastWeek != null ? lastWeek : 0L);
        data.put("weekDelta", deltaPct(thisWeek, lastWeek));
        return data;
    }

    /** 周同比百分比（正=上升，负=下降，空串=无对比基期） */
    private String deltaPct(Number cur, Number pre) {
        double c = cur == null ? 0 : cur.doubleValue();
        double p = pre == null ? 0 : pre.doubleValue();
        if (p <= 0) return "";
        double delta = Math.round((c - p) / p * 100);
        return (delta > 0 ? "+" : "") + D.format(delta);
    }

    /** 登录用户最近动态（W2-03）：管理员看全部，普通用户只看自己，最多 limit 条 */
    public List<Map<String, Object>> activities(int limit) {
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<>();
        if (!isAdmin()) {
            w.eq(AuditLog::getUsername, UserContext.getUsername());
        }
        w.orderByDesc(AuditLog::getCreateTime).last("LIMIT " + Math.min(Math.max(limit, 1), 50));
        List<AuditLog> logs = auditLogMapper.selectList(w);
        List<Map<String, Object>> list = new ArrayList<>();
        for (AuditLog log : logs) {
            Map<String, Object> m = new HashMap<>();
            m.put("username", log.getUsername());
            m.put("operation", log.getOperation());
            m.put("path", log.getPath());
            m.put("success", log.getSuccess() != null && log.getSuccess() == 1);
            m.put("createTime", log.getCreateTime() != null ? log.getCreateTime().toString().replace("T", " ") : "");
            list.add(m);
        }
        return list;
    }

    // ==================== W2-06：使用趋势（按天） ====================

    /** 趋势图数据：days=7/30，统计 分析任务数/完成数（task）+ 登录次数（login）；数据隔离 */
    public Map<String, Object> trend(int days) {
        int n = Math.min(Math.max(days, 7), 90);
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(n - 1L).atStartOfDay();
        boolean admin = isAdmin();
        List<Long> ids = visibleProjectIds(admin);
        List<Map<String, Object>> tasks;
        if (!admin && ids.isEmpty()) {
            tasks = new ArrayList<>();
        } else {
            tasks = analysisTaskMapper.dailyTaskStats(start, ids);
        }
        List<Map<String, Object>> logins = auditLogMapper.dailyLoginStats(start, admin ? null : UserContext.getUsername());

        // 任务按天映射
        Map<String, Map<String, Object>> taskByDay = new LinkedHashMap<>();
        for (Map<String, Object> t : tasks) {
            taskByDay.put(String.valueOf(t.get("day")), t);
        }
        Map<String, Long> loginByDay = new LinkedHashMap<>();
        for (Map<String, Object> l : logins) {
            loginByDay.put(String.valueOf(l.get("day")), ((Number) l.get("cnt")).longValue());
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String day = today.minusDays(n - 1L - i).toString();
            Map<String, Object> t = taskByDay.get(day);
            Long total = t == null ? 0L : ((Number) t.get("total")).longValue();
            Long success = t == null ? 0L : ((Number) t.get("success")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("tasks", total);
            row.put("completed", success);
            row.put("logins", loginByDay.getOrDefault(day, 0L));
            series.add(row);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("days", series);
        return data;
    }

    // ==================== W2-04：通知 ====================

    /** 通知：待办提醒（项目状态派生）+ 最近分析任务结果 */
    public Map<String, Object> notifications() {
        List<Project> projects = visibleProjects();
        List<Map<String, Object>> todos = new ArrayList<>();
        Map<String, Integer> freq = new LinkedHashMap<>();
        freq.put("created", 0);
        freq.put("running", 0);
        freq.put("failed", 0);
        for (Project p : projects) {
            String s = p.getStatus();
            if ("created".equals(s)) {
                if (p.getRequirementCount() == null || p.getRequirementCount() == 0) {
                    todos.add(todo(p.getId(), p.getProjectName(), "待上传需求文档", "created"));
                } else if (!hasCode(p)) {
                    todos.add(todo(p.getId(), p.getProjectName(), "待上传代码工程", "created"));
                }
            } else if ("failed".equals(s)) {
                todos.add(todo(p.getId(), p.getProjectName(), "分析失败，需要重跑", "failed"));
            }
            freq.merge(s, 1, Integer::sum);
        }
        // 最近任务结果（可见项目范围内）
        List<AnalysisTask> recentTasks = recentTasks(15);
        List<Map<String, Object>> tasks = new ArrayList<>();
        Map<Long, String> nameMap = projects.stream().collect(Collectors.toMap(Project::getId, Project::getProjectName, (a, b) -> a));
        for (AnalysisTask t : recentTasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("projectId", t.getProjectId());
            m.put("projectName", nameMap.get(t.getProjectId()));
            m.put("taskName", t.getTaskName());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("createTime", t.getCreateTime() != null ? t.getCreateTime().toString().replace("T", " ") : "");
            tasks.add(m);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("todos", todos.stream().limit(8).collect(Collectors.toList()));
        data.put("tasks", tasks);
        data.put("freq", freq);
        return data;
    }

    private boolean hasCode(Project p) {
        return p.getCodeProjectPath() != null && !p.getCodeProjectPath().isEmpty();
    }

    private Map<String, Object> todo(Long id, String name, String action, String level) {
        Map<String, Object> m = new HashMap<>();
        m.put("projectId", id);
        m.put("projectName", name);
        m.put("action", action);
        m.put("level", level);
        return m;
    }

    /** 可见项目范围内最近 N 个分析任务 */
    private List<AnalysisTask> recentTasks(int limit) {
        List<Project> projects = visibleProjects();
        if (projects.isEmpty()) return new ArrayList<>();
        List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());
        LambdaQueryWrapper<AnalysisTask> w = new LambdaQueryWrapper<>();
        w.in(AnalysisTask::getProjectId, ids).orderByDesc(AnalysisTask::getCreateTime).last("LIMIT " + Math.min(Math.max(limit, 1), 50));
        return analysisTaskMapper.selectList(w);
    }

    // ==================== W2-05：全局搜索 ====================

    /** 跨表搜索：项目名 / 需求原文 / 缺陷（数据隔离） */
    public Map<String, Object> search(String q) {
        String kw = q == null ? "" : q.trim();
        Map<String, Object> data = new HashMap<>();
        data.put("projects", new ArrayList<>());
        data.put("requirements", new ArrayList<>());
        data.put("defects", new ArrayList<>());
        if (kw.isEmpty()) return data;

        // 项目
        LambdaQueryWrapper<Project> pw = new LambdaQueryWrapper<>();
        if (!isAdmin()) pw.eq(Project::getCreateUserId, UserContext.getUserId());
        pw.like(Project::getProjectName, kw).last("LIMIT 5");
        List<Project> ps = projectMapper.selectList(pw);
        List<Long> ids = ps.stream().map(Project::getId).collect(Collectors.toList());
        data.put("projects", ps.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getProjectName());
            m.put("status", p.getStatus());
            return m;
        }).collect(Collectors.toList()));
        if (ids.isEmpty()) return data;

        // 需求
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .in(Requirement::getProjectId, ids)
                .and(w -> w.like(Requirement::getOriginalText, kw).or().like(Requirement::getTitle, kw).or().like(Requirement::getRequirementId, kw))
                .last("LIMIT 5"));
        data.put("requirements", reqs.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("projectId", r.getProjectId());
            m.put("requirementId", r.getRequirementId());
            m.put("title", r.getTitle() != null ? r.getTitle() : cut(r.getOriginalText(), 40));
            m.put("text", cut(r.getOriginalText(), 60));
            return m;
        }).collect(Collectors.toList()));

        // 缺陷
        List<Defect> defs = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                .in(Defect::getProjectId, ids)
                .and(w -> w.like(Defect::getDefectId, kw).or().like(Defect::getDefectReason, kw).or().like(Defect::getDefectType, kw))
                .last("LIMIT 5"));
        data.put("defects", defs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("projectId", d.getProjectId());
            m.put("defectId", d.getDefectId());
            m.put("defectType", d.getDefectType());
            m.put("reason", cut(d.getDefectReason(), 60));
            return m;
        }).collect(Collectors.toList()));
        return data;
    }

    private String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ==================== W2-07：系统运行状态 ====================

    /** 系统状态：数据库连通 / 磁盘占用 / 运行中任务 / WS 在线 / 最近备份 */
    public Map<String, Object> systemStatus() {
        Map<String, Object> data = new HashMap<>();

        // 数据库
        boolean dbUp = false;
        try (Connection conn = dataSource.getConnection()) {
            dbUp = conn.isValid(1);
        } catch (Exception e) {
            LOGGER.warn("数据库连通检测失败: {}", e.getMessage());
        }
        data.put("db", dbUp);

        // 磁盘（上传目录所在盘）
        try {
            File dir = new File(fileStorageUtil.getUploadPath());
            if (!dir.exists()) dir.mkdirs();
            long free = dir.getUsableSpace();
            long total = dir.getTotalSpace();
            data.put("diskFreeMb", free / 1024 / 1024);
            data.put("diskTotalMb", total / 1024 / 1024);
            long used = total - free;
            data.put("diskUsedPct", total > 0 ? Math.round(used * 100.0 / total) : 0);
        } catch (Exception e) {
            LOGGER.warn("磁盘空间检测失败: {}", e.getMessage());
            data.put("diskFreeMb", -1);
            data.put("diskTotalMb", -1);
            data.put("diskUsedPct", -1);
        }

        // 分析任务队列
        data.put("runningTasks", analysisTaskMapper.selectCount(new LambdaQueryWrapper<AnalysisTask>()
                .in(AnalysisTask::getStatus, "running", "pending")));
        data.put("pausedTasks", analysisTaskMapper.selectCount(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getStatus, "paused")));

        // WS 在线
        data.put("wsOnline", ProgressWebSocketHandler.onlineCount());

        // 最近备份时间（扫描备份目录最新文件）
        data.put("lastBackupTime", latestBackupTime());
        return data;
    }

    private String latestBackupTime() {
        try {
            File dir = new File(backupPath);
            if (!dir.exists()) return null;
            File[] files = dir.listFiles(f -> f.isFile() && !f.getName().startsWith("passphrase"));
            if (files == null || files.length == 0) return null;
            return Arrays.stream(files)
                    .max(Comparator.comparingLong(File::lastModified))
                    .map(f -> new java.util.Date(f.lastModified())).map(Date::toInstant)
                    .map(t -> java.time.LocalDateTime.ofInstant(t, java.time.ZoneId.systemDefault()))
                    .map(t -> t.toString().replace("T", " "))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== W2-10：Quality Gate 阈值配置 ====================

    /** 读取质量门槛配置（sys_config.quality_gate JSON），无配置返回默认值 */
    public Map<String, Object> gateConfig() {
        SystemConfig cfg = systemConfigMapper.selectById("quality_gate");
        String raw = cfg != null && cfg.getConfigValue() != null ? cfg.getConfigValue() : GATE_DEFAULT;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** W3-02：菜单配置化（sys_config.menu_config JSON），缺省返回空 = 全部菜单可见 */
    public Map<String, Object> menuConfig() {
        SystemConfig cfg = systemConfigMapper.selectById("menu_config");
        String raw = cfg != null ? cfg.getConfigValue() : null;
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}