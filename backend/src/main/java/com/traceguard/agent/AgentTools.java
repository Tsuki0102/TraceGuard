package com.traceguard.agent;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.traceguard.entity.AnalysisTask;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Project;
import com.traceguard.entity.Requirement;
import com.traceguard.service.AnalysisService;
import com.traceguard.service.DashboardStatsService;
import com.traceguard.service.InsightService;
import com.traceguard.service.LlmService;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ResultService;
import com.traceguard.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体只读工具集（一期）
 * 全部复用系统既有 Service：数据权限跟随用户身份（管理员全量，普通用户仅可见自己的项目）。
 * 工具输出做字段裁剪，避免大结果集撑爆上下文（分页 + 截断）。
 */
@Component
public class AgentTools {

    /** 单次工具返回的最大列表长度（超出截断，提示模型翻页） */
    private static final int MAX_PAGE_SIZE = 20;
    /** 单字段文本最大长度（超长截断，保护上下文） */
    private static final int MAX_TEXT_LEN = 400;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ResultService resultService;
    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private InsightService insightService;
    @Autowired
    private DashboardStatsService dashboardStatsService;
    @Autowired
    private LlmService llmService;

    private final Map<String, AgentTool> registry = new LinkedHashMap<>();

    @PostConstruct
    void register() {
        List<AgentTool> tools = List.of(
                listProjects(), getOverview(), getTrend(), queryDefects(),
                queryRequirements(), queryConsistency(), getPatterns(),
                search(), explainDefect(), listTasks(),
                rerunAnalysis(), updateDefectStatus());
        for (AgentTool t : tools) {
            registry.put(t.name(), t);
        }
    }

    /** 按名取工具；未注册返回 null */
    public AgentTool get(String name) {
        return registry.get(name);
    }

    /** 全部工具（供系统提示词注入） */
    public List<AgentTool> all() {
        return new ArrayList<>(registry.values());
    }

    public List<String> names() {
        return new ArrayList<>(registry.keySet());
    }

    // ==================== 工具实现 ====================

    /** 项目可见性校验：管理员全量；普通用户仅项目创建者 */
    private Project requireVisibleProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        Project p = projectService.getById(projectId);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        if (!UserContext.isAdmin() && !UserContext.getUserId().equals(p.getCreateUserId())) {
            throw new IllegalArgumentException("无权访问项目 " + projectId + "（仅项目创建者或管理员可见）");
        }
        return p;
    }

    private Map<String, Object> projectBrief(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", p.getId());
        m.put("projectName", p.getProjectName());
        m.put("status", p.getStatus());
        m.put("requirementCount", p.getRequirementCount());
        m.put("defectCount", p.getDefectCount());
        m.put("coverageRate", p.getCoverageRate());
        if (p.getTechStack() != null) m.put("techStack", p.getTechStack());
        return m;
    }

    /** 工具1：列出当前用户可见项目 */
    private AgentTool listProjects() {
        return new AgentTool() {
            @Override public String name() { return "list_projects"; }
            @Override public String description() {
                return "列出当前用户可见的全部项目（含状态、需求数、缺陷数、需求覆盖率）。用于了解有哪些项目、挑选 projectId。";
            }
            @Override public String argsSchema() { return "{}（无参数）"; }
            @Override public Object execute(Map<String, Object> args) {
                List<Project> projects = insightService.visibleProjects();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Project p : projects) {
                    rows.add(projectBrief(p));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", rows.size());
                out.put("projects", rows);
                return out;
            }
        };
    }

    /** 工具2：工作台全局统计 */
    private AgentTool getOverview() {
        return new AgentTool() {
            @Override public String name() { return "get_overview"; }
            @Override public String description() {
                return "获取当前用户视角的全局质量概览：项目总数、已分析数、平均需求覆盖率、缺陷总数、本周任务量与周同比。";
            }
            @Override public String argsSchema() { return "{}（无参数）"; }
            @Override public Object execute(Map<String, Object> args) {
                return dashboardStatsService.overview();
            }
        };
    }

    /** 工具3：缺陷趋势 */
    private AgentTool getTrend() {
        return new AgentTool() {
            @Override public String name() { return "get_trend"; }
            @Override public String description() {
                return "查询近 N 天每日新增缺陷趋势（按 严重serious/一般general 分级）及缺陷类型 Top。可按项目过滤。";
            }
            @Override public String argsSchema() {
                return "{\"days\":30, \"projectId\":null}；days 取值 7~90（默认30）；projectId 可选，为空=全部可见项目";
            }
            @Override public Object execute(Map<String, Object> args) {
                int days = intArg(args, "days", 30);
                Long projectId = longArg(args, "projectId", null);
                return insightService.trend(days, projectId);
            }
        };
    }

    /** 工具4：分页查询缺陷 */
    private AgentTool queryDefects() {
        return new AgentTool() {
            @Override public String name() { return "query_defects"; }
            @Override public String description() {
                return "分页查询指定项目的缺陷列表。可按等级（serious=严重/general=一般）、类型、处理状态（pending=待处理/processing=处理中/resolved=已解决/ignored=已忽略）过滤，用于回答\"某项目有哪些严重缺陷\"等。";
            }
            @Override public String argsSchema() {
                return "{\"projectId\":1, \"level\":\"serious\", \"status\":\"pending\", \"page\":1, \"size\":10}"
                        + "；projectId 必填；level/status 可选；page 默认1，size 默认10（最大" + MAX_PAGE_SIZE + "）";
            }
            @Override public Object execute(Map<String, Object> args) {
                Long projectId = longArg(args, "projectId", null);
                requireVisibleProject(projectId);
                int page = Math.max(1, intArg(args, "page", 1));
                int size = Math.min(MAX_PAGE_SIZE, Math.max(1, intArg(args, "size", 10)));
                String level = strArg(args, "level", null);
                String type = strArg(args, "type", null);
                String subType = strArg(args, "subType", null);
                String status = strArg(args, "status", null);
                IPage<Defect> p = resultService.getDefectsPage(projectId, null, level, type, subType, status, page, size);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Defect d : p.getRecords()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("defectNo", d.getDefectId());
                    m.put("level", d.getDefectLevel());
                    m.put("type", d.getDefectType());
                    if (d.getSubType() != null) m.put("subType", d.getSubType());
                    m.put("status", d.getStatus());
                    m.put("requirementId", d.getRequirementId());
                    if (d.getDefectLine() != null) m.put("line", d.getDefectLine());
                    m.put("reason", truncate(d.getDefectReason()));
                    m.put("createTime", d.getCreateTime() == null ? null : d.getCreateTime().toLocalDate().toString());
                    rows.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", p.getTotal());
                out.put("page", page);
                out.put("size", size);
                out.put("defects", rows);
                if (rows.size() < p.getTotal()) {
                    out.put("hint", "仅返回第 " + page + " 页，可用 page 参数翻页");
                }
                return out;
            }
        };
    }

    /** 工具5：分页查询需求 */
    private AgentTool queryRequirements() {
        return new AgentTool() {
            @Override public String name() { return "query_requirements"; }
            @Override public String description() {
                return "分页查询指定项目的需求条目（标题/类型/优先级/状态），用于了解项目需求构成。";
            }
            @Override public String argsSchema() {
                return "{\"projectId\":1, \"page\":1, \"size\":10}；projectId 必填";
            }
            @Override public Object execute(Map<String, Object> args) {
                Long projectId = longArg(args, "projectId", null);
                requireVisibleProject(projectId);
                int page = Math.max(1, intArg(args, "page", 1));
                int size = Math.min(MAX_PAGE_SIZE, Math.max(1, intArg(args, "size", 10)));
                IPage<Requirement> p = resultService.getRequirementsPage(projectId, page, size);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Requirement r : p.getRecords()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requirementId", r.getRequirementId());
                    m.put("title", truncate(r.getTitle()));
                    if (r.getRequirementType() != null) m.put("type", r.getRequirementType());
                    if (r.getPriority() != null) m.put("priority", r.getPriority());
                    m.put("status", r.getStatus());
                    rows.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", p.getTotal());
                out.put("page", page);
                out.put("size", size);
                out.put("requirements", rows);
                return out;
            }
        };
    }

    /** 工具6：分页查询一致性判定结果 */
    private AgentTool queryConsistency() {
        return new AgentTool() {
            @Override public String name() { return "query_consistency"; }
            @Override public String description() {
                return "分页查询指定项目的需求-代码一致性判定结果（语义相似度/约束匹配度/判定状态），用于回答\"一致性得分\"\"哪些需求与代码不一致\"等。";
            }
            @Override public String argsSchema() {
                return "{\"projectId\":1, \"page\":1, \"size\":10}；projectId 必填";
            }
            @Override public Object execute(Map<String, Object> args) {
                Long projectId = longArg(args, "projectId", null);
                requireVisibleProject(projectId);
                int page = Math.max(1, intArg(args, "page", 1));
                int size = Math.min(MAX_PAGE_SIZE, Math.max(1, intArg(args, "size", 10)));
                IPage<ConsistencyResult> p = resultService.getConsistencyResultsPage(projectId, null, page, size);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ConsistencyResult r : p.getRecords()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("matchId", r.getMatchId());
                    m.put("requirementId", r.getRequirementId());
                    m.put("semanticSimilarity", r.getSemanticSimilarity());
                    m.put("constraintMatchDegree", r.getConstraintMatchDegree());
                    m.put("invariantSatisfaction", r.getInvariantSatisfaction());
                    m.put("totalSimilarity", r.getTotalSimilarity());
                    m.put("status", r.getConsistencyStatus());
                    if (r.getDefectType() != null) m.put("defectType", r.getDefectType());
                    rows.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", p.getTotal());
                out.put("page", page);
                out.put("size", size);
                out.put("results", rows);
                return out;
            }
        };
    }

    /** 工具7：缺陷模式统计 */
    private AgentTool getPatterns() {
        return new AgentTool() {
            @Override public String name() { return "get_patterns"; }
            @Override public String description() {
                return "获取缺陷模式统计（类型分布、高频缺陷模式），用于回答\"缺陷主要集中在哪类问题\"。";
            }
            @Override public String argsSchema() { return "{}（无参数）"; }
            @Override public Object execute(Map<String, Object> args) {
                return insightService.patterns();
            }
        };
    }

    /** 工具8：全局搜索 */
    private AgentTool search() {
        return new AgentTool() {
            @Override public String name() { return "search"; }
            @Override public String description() {
                return "按关键词全局搜索项目/需求/缺陷（工作台搜索同源），用于按名称定位项目或缺陷。";
            }
            @Override public String argsSchema() { return "{\"q\":\"关键词\"}；q 必填"; }
            @Override public Object execute(Map<String, Object> args) {
                String q = strArg(args, "q", null);
                if (q == null || q.trim().isEmpty()) {
                    throw new IllegalArgumentException("q 不能为空");
                }
                return dashboardStatsService.search(q.trim());
            }
        };
    }

    /** 工具9：缺陷深度解释（LLM 增强） */
    private AgentTool explainDefect() {
        return new AgentTool() {
            @Override public String name() { return "explain_defect"; }
            @Override public String description() {
                return "对单个缺陷做深度解释：结合需求文本与代码片段，由大模型给出成因分析与修复建议。缺陷ID取 query_defects 返回的 id 字段。";
            }
            @Override public String argsSchema() { return "{\"id\":123}；id 必填（缺陷主键）"; }
            @Override public Object execute(Map<String, Object> args) {
                Long id = longArg(args, "id", null);
                if (id == null) {
                    throw new IllegalArgumentException("id 不能为空");
                }
                Defect d = resultService.getDefectById(id);
                if (d == null) {
                    throw new IllegalArgumentException("缺陷不存在: " + id);
                }
                requireVisibleProject(d.getProjectId());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("defectNo", d.getDefectId());
                out.put("level", d.getDefectLevel());
                out.put("type", d.getDefectType());
                out.put("status", d.getStatus());
                out.put("requirementText", truncate(d.getRequirementText()));
                out.put("codeSnippet", truncate(d.getCodeSnippet()));
                Map<String, String> explain = llmService.explainDefect(
                        d.getRequirementText(), d.getCodeSnippet(), d.getDefectType());
                if (explain != null) {
                    out.put("reason", explain.getOrDefault("reason", ""));
                    out.put("suggestion", explain.getOrDefault("suggestion", ""));
                } else {
                    out.put("reason", d.getDefectReason());
                    out.put("suggestion", d.getRepairSuggestion());
                    out.put("note", "LLM 增强解释不可用，已返回规则生成的原始原因/建议");
                }
                return out;
            }
        };
    }

    /** 工具10：分页查询分析任务 */
    private AgentTool listTasks() {
        return new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public String description() {
                return "分页查询指定项目的分析任务（任务名/状态/进度/当前步骤），用于定位 taskId（rerun_analysis 需要）。";
            }
            @Override public String argsSchema() {
                return "{\"projectId\":1, \"page\":1, \"size\":10}；projectId 必填";
            }
            @Override public Object execute(Map<String, Object> args) {
                Long projectId = longArg(args, "projectId", null);
                requireVisibleProject(projectId);
                int page = Math.max(1, intArg(args, "page", 1));
                int size = Math.min(MAX_PAGE_SIZE, Math.max(1, intArg(args, "size", 10)));
                IPage<AnalysisTask> p = analysisService.listTasks(projectId, page, size);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (AnalysisTask t : p.getRecords()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", t.getId());
                    m.put("taskName", truncate(t.getTaskName()));
                    m.put("status", t.getStatus());
                    if (t.getProgress() != null) m.put("progress", t.getProgress());
                    if (t.getCurrentStep() != null) m.put("currentStep", t.getCurrentStep());
                    m.put("startTime", t.getStartTime() == null ? null : t.getStartTime().toLocalDate().toString());
                    if (t.getErrorMessage() != null) m.put("error", truncate(t.getErrorMessage()));
                    rows.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", p.getTotal());
                out.put("page", page);
                out.put("size", size);
                out.put("tasks", rows);
                return out;
            }
        };
    }

    /** 工具11：重新运行分析任务（动作类，需用户确认） */
    private AgentTool rerunAnalysis() {
        return new AgentTool() {
            @Override public String name() { return "rerun_analysis"; }
            @Override public String description() {
                return "重新运行指定分析任务（复用原任务配置创建新任务并立即执行，会产生一次完整分析）。taskId 取 list_tasks 返回的 taskId。此为动作类工具，调用后系统将向用户弹出确认卡片。";
            }
            @Override public String argsSchema() { return "{\"taskId\":123}；taskId 必填"; }
            @Override public boolean requiresConfirm() { return true; }
            @Override public String confirmSummary(Map<String, Object> args) {
                Long taskId = longArg(args, "taskId", null);
                return "重新运行分析任务 #" + taskId + "（复用原配置创建新任务并立即执行）";
            }
            @Override public Object execute(Map<String, Object> args) {
                Long taskId = longArg(args, "taskId", null);
                if (taskId == null) {
                    throw new IllegalArgumentException("taskId 不能为空");
                }
                analysisService.checkTaskAccess(taskId);
                AnalysisTask created = analysisService.rerunTask(taskId);
                analysisService.runAnalysis(created.getId());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("newTaskId", created.getId());
                out.put("taskName", created.getTaskName());
                out.put("status", created.getStatus());
                out.put("message", "已创建并启动重新分析任务");
                return out;
            }
        };
    }

    /** 工具12：变更缺陷处理状态（动作类，需用户确认） */
    private AgentTool updateDefectStatus() {
        return new AgentTool() {
            @Override public String name() { return "update_defect_status"; }
            @Override public String description() {
                return "变更缺陷工单处理状态（pending=待处理/processing=处理中/resolved=已解决/ignored=已忽略）。id 取 query_defects 返回的 id。此为动作类工具，调用后系统将向用户弹出确认卡片。";
            }
            @Override public String argsSchema() { return "{\"id\":123, \"status\":\"resolved\"}；id 与 status 必填"; }
            @Override public boolean requiresConfirm() { return true; }
            @Override public String confirmSummary(Map<String, Object> args) {
                Long id = longArg(args, "id", null);
                String status = strArg(args, "status", "");
                return "将缺陷 #" + id + " 的处理状态变更为 " + status;
            }
            @Override public Object execute(Map<String, Object> args) {
                Long id = longArg(args, "id", null);
                String status = strArg(args, "status", null);
                if (id == null || status == null) {
                    throw new IllegalArgumentException("id 与 status 不能为空");
                }
                Defect d = resultService.getDefectById(id);
                if (d == null) {
                    throw new IllegalArgumentException("缺陷不存在: " + id);
                }
                requireVisibleProject(d.getProjectId());
                String oldStatus = d.getStatus();
                resultService.updateDefectStatus(id, status, UserContext.getUserId());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("from", oldStatus);
                out.put("to", status);
                out.put("message", "缺陷状态已变更：" + oldStatus + " -> " + status);
                return out;
            }
        };
    }

    // ==================== 参数容错解析 ====================

    private static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String && !((String) v).isEmpty()) {
            try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static Long longArg(Map<String, Object> args, String key, Long def) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String && !((String) v).isEmpty()) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static String strArg(Map<String, Object> args, String key, String def) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) + "…（已截断）" : s;
    }

    /** 供 AgentService 构建系统提示词：工具清单描述 */
    public String describeTools() {
        StringBuilder sb = new StringBuilder();
        for (AgentTool t : registry.values()) {
            sb.append("- ").append(t.name()).append("：").append(t.description())
              .append(" 参数：").append(t.argsSchema()).append('\n');
        }
        return sb.toString();
    }
}
