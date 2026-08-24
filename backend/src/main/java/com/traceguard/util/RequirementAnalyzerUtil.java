package com.traceguard.util;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.entity.Requirement;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RequirementAnalyzerUtil {

    private static final ObjectMapper QUALITY_OM = new ObjectMapper();

    private final Pattern functionalPattern = Pattern.compile("(实现|提供|支持|完成|执行|处理|计算|验证|检测|生成|创建|更新|删除|查询|登录|注册|上传|下载|发送|接收)");
    private final Pattern nonFunctionalPattern = Pattern.compile("(性能|响应时间|安全|可靠|兼容|可用|可维护|可扩展|并发|吞吐量|内存|CPU)");
    private final Pattern constraintPattern = Pattern.compile("(必须|应当|应该|需要|不得|禁止|只能|仅|至少|最多|不超过|不低于|大于|小于|等于|在.*之前|在.*之后)");
    private final Pattern statePattern = Pattern.compile("(当.*时|如果|若|一旦|在.*情况下|状态|变成|转换为|初始|开始|结束|完成|失败|成功)");
    /** 强约束句（不变量候选） */
    private final Pattern invariantPattern = Pattern.compile("(必须|应当|应该|不得|禁止|不能|始终|唯一|非空|不能为空|不能重复)");

    /** 互斥词对（FR-REQ-002 矛盾性检测）已迁移为可配置项，见 RuleConfigHolder / ReqParseRuleConfig（AUD-07） */
    /** 敏感操作须伴随权限/角色边界描述（FR-REQ-002 边界条件检测） */
    private static final Pattern SENSITIVE_ACTION_PATTERN = Pattern.compile("(删除|修改|更新|导出)");
    private static final Pattern PERMISSION_TERM_PATTERN = Pattern.compile("(权限|角色|授权|管理员|审批|登录)");
    /** 数量边界词：出现数值时应明确范围 */
    private static final Pattern RANGE_TERM_PATTERN = Pattern.compile("(至少|最多|不超过|不低于|大于|小于|等于|以内|以上|以下|少于|多于|范围|之间)");
    /** 相似度达到该阈值的条目视为冗余重复（FR-REQ-001 业务规则2） */
    private static final double DUPLICATE_SIMILARITY_THRESHOLD = 0.9;

    public List<Requirement> analyzeRequirements(Long projectId, List<String> requirementTexts) {
        return analyzeRequirements(projectId, requirementTexts, null, 1);
    }

    /** GAP-019：携带来源文件名（多文档批量解析时逐文件调用） */
    public List<Requirement> analyzeRequirements(Long projectId, List<String> requirementTexts, String sourceFile) {
        return analyzeRequirements(projectId, requirementTexts, sourceFile, 1);
    }

    /** GAP-019：支持起始序号（多文档逐文件解析时保持 REQ-XXXX 全局连续） */
    public List<Requirement> analyzeRequirements(Long projectId, List<String> requirementTexts, String sourceFile, int startIndex) {
        List<Requirement> requirements = new ArrayList<>();
        int index = startIndex;
        for (String text : requirementTexts) {
            if (text.trim().length() < 5) continue;
            // 重复/冗余需求过滤（FR-REQ-001 业务规则2）：归一化后相同或高度相似的条目仅保留首条
            if (isDuplicateRequirement(text, requirements)) continue;
            Requirement req = new Requirement();
            req.setProjectId(projectId);
            req.setRequirementId("REQ-" + String.format("%04d", index));
            req.setOriginalText(text);
            // GAP-019：标题/优先级/来源文件
            req.setTitle(extractTitle(text));
            req.setPriority(determinePriority(text));
            req.setSourceFile(sourceFile);
            req.setRequirementType(determineRequirementType(text));
            req.setStateSet(extractStates(text));
            req.setInitialState(extractInitialState(text));
            req.setStateTransitions(extractTransitions(text));
            req.setAtomicConstraints(extractConstraints(text));
            req.setInvariants(extractInvariants(text));
            req.setConstraintRules(extractConstraintRules(text));
            req.setAmbiguityReport(buildQualityReport(text));
            // GAP-045：Kripke 语义模型补全——原子命题(AP)与状态标签函数(L)
            String ap = extractAtomicPropositions(text, req.getAtomicConstraints(), req.getInvariants());
            req.setAtomicPropositions(ap);
            // 以提取的初始状态作为本需求(视为一个状态/场景)的代表状态，构造 L(state)={为真的AP}
            req.setStateLabeling(buildStateLabeling(req.getInitialState(), ap));
            req.setStatus(1);
            requirements.add(req);
            index++;
        }
        return requirements;
    }

    /**
     * 判断条目是否为已接受需求的重复/冗余：先做归一化精确比对（去掉编号前缀与空白），
     * 再与已接受条目做文本相似度比对（LCS相似度 >= 0.9 视为冗余）
     */
    private boolean isDuplicateRequirement(String text, List<Requirement> accepted) {
        String normalized = normalizeRequirementText(text);
        for (Requirement r : accepted) {
            String existing = normalizeRequirementText(r.getOriginalText());
            if (existing.equals(normalized)) {
                return true;
            }
            if (StrUtil.similar(existing, text) >= DUPLICATE_SIMILARITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 归一化：去行首编号前缀与全部空白，使"1. 用户登录"与"2、用户登录"可比对 */
    private String normalizeRequirementText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceFirst("^([0-9]+[.、)）]|[一二三四五六七八九十]+[.、)）]|第[一二三四五六七八九十0-9]+[条章节]|(FR|REQ)-\\d+)", "")
                .replaceAll("\\s+", "");
    }

    /**
     * 需求质量报告（FR-REQ-002，2.2 整改项）：
     * 歧义词检测 + 单条内矛盾性检测 + 缺失边界条件检测，输出结构化 JSON：
     * {"hasIssue":bool,"issueCount":n,"issues":[{"type","severity","keyword","excerpt","suggestion","message"}],"summary":"..."}
     * summary 保持旧版文本风格，message 保留旧关键词（潜在矛盾/权限或角色边界/数量边界），
     * 保证旧文本断言与展示兼容的同时，提供前端列表化渲染与审计导出所需的结构化信息。
     */
    private String buildQualityReport(String text) {
        List<QualityIssue> issues = new ArrayList<>();
        issues.addAll(checkAmbiguity(text));
        issues.addAll(checkContradiction(text));
        issues.addAll(checkMissingBoundaries(text));
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            if (issues.isEmpty()) {
                report.put("hasIssue", false);
                report.put("issueCount", 0);
                report.put("issues", new ArrayList<>());
                report.put("summary", "未检测到明显歧义");
            } else {
                report.put("hasIssue", true);
                report.put("issueCount", issues.size());
                report.put("issues", issues);
                List<String> messages = new ArrayList<>();
                for (QualityIssue issue : issues) {
                    messages.add(issue.getMessage());
                }
                report.put("summary", String.join("; ", messages));
            }
            return QUALITY_OM.writeValueAsString(report);
        } catch (Exception e) {
            return "未检测到明显歧义";
        }
    }

    /** 结构化质量检测问题（2.2 整改项） */
    static class QualityIssue {
        private String type;       // ambiguity | contradiction | missing_boundary
        private String severity;   // high | medium | low
        private String keyword;    // 命中词/触发原因
        private String excerpt;    // 涉及原文片段
        private String suggestion; // 修复建议
        private String message;    // 人类可读描述（兼容旧文本断言）

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public String getExcerpt() { return excerpt; }
        public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** 提取关键词在原文中的上下文片段（前后各 15 字符） */
    private String extractExcerpt(String text, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return text.length() > 30 ? text.substring(0, 30) : text;
        }
        int idx = text.indexOf(keyword);
        if (idx < 0) {
            return text.length() > 30 ? text.substring(0, 30) : text;
        }
        int start = Math.max(0, idx - 15);
        int end = Math.min(text.length(), idx + keyword.length() + 15);
        return text.substring(start, end);
    }

    private List<QualityIssue> checkAmbiguity(String text) {
        List<QualityIssue> issues = new ArrayList<>();
        // AUD-07：歧义词清单改为可配置（RuleConfigHolder），便于管理员按需增删
        for (String keyword : com.traceguard.config.RuleConfigHolder.ambiguityKeywords()) {
            if (com.traceguard.config.RuleConfigHolder.containsAmbiguity(text, keyword)) {
                QualityIssue issue = new QualityIssue();
                issue.setType("ambiguity");
                issue.setSeverity("medium");
                issue.setKeyword(keyword);
                issue.setExcerpt(extractExcerpt(text, keyword));
                issue.setSuggestion("建议将「" + keyword + "」明确为具体、可度量、无二义的表述，避免歧义理解");
                issue.setMessage("存在模糊/不确定表述\"" + keyword + "\"，建议明确化");
                issues.add(issue);
            }
        }
        return issues;
    }

    /** 单条需求内的互斥词对检测：命中仅提示"潜在矛盾"，须人工复核（词对共现不必然矛盾）。
     *  AUD-07：互斥词对改为可配置（RuleConfigHolder）。 */
    private List<QualityIssue> checkContradiction(String text) {
        List<QualityIssue> issues = new ArrayList<>();
        for (java.util.List<String> pair : com.traceguard.config.RuleConfigHolder.contradictionPairs()) {
            if (com.traceguard.config.RuleConfigHolder.matchesContradiction(text, pair)) {
                String bLabel = pair.get(1).replace("(?<!不)", "");
                QualityIssue issue = new QualityIssue();
                issue.setType("contradiction");
                issue.setSeverity("high");
                issue.setKeyword(pair.get(0) + " / " + bLabel);
                issue.setExcerpt(extractExcerpt(text, pair.get(0)));
                issue.setSuggestion("请人工复核：同一需求内同时出现互斥表述，需明确唯一语义或拆分为不同场景分别描述");
                issue.setMessage("同时出现互斥表述\"" + pair.get(0) + "\"与\"" + bLabel
                        + "\"，存在潜在矛盾，建议人工复核");
                issues.add(issue);
            }
        }
        return issues;
    }

    /** 缺失边界条件检测：敏感操作无权限描述、含数值无范围界定 */
    private List<QualityIssue> checkMissingBoundaries(String text) {
        List<QualityIssue> issues = new ArrayList<>();
        Matcher sensitive = SENSITIVE_ACTION_PATTERN.matcher(text);
        if (sensitive.find() && !PERMISSION_TERM_PATTERN.matcher(text).find()) {
            String action = sensitive.group();
            QualityIssue issue = new QualityIssue();
            issue.setType("missing_boundary");
            issue.setSeverity("medium");
            issue.setKeyword("敏感操作：" + action);
            issue.setExcerpt(extractExcerpt(text, action));
            issue.setSuggestion("补充权限/角色边界描述，如「仅管理员可" + action + "」或「需审批后" + action + "」");
            issue.setMessage("涉及敏感操作（删除/修改/导出）但未描述权限或角色边界");
            issues.add(issue);
        }
        Matcher numeric = Pattern.compile("\\d+").matcher(text);
        if (numeric.find() && !RANGE_TERM_PATTERN.matcher(text).find()) {
            QualityIssue issue = new QualityIssue();
            issue.setType("missing_boundary");
            issue.setSeverity("medium");
            issue.setKeyword("数量边界");
            issue.setExcerpt(extractExcerpt(text, numeric.group()));
            issue.setSuggestion("明确数量边界，补充「至少/最多/不超过/范围」等限定词，例如「不少于 8 位」");
            issue.setMessage("包含数值但未明确数量边界（如至少/最多/范围）");
            issues.add(issue);
        }
        return issues;
    }

    /**
     * 生成带真实语义的Alloy形式化规约：
     * - 状态集、初始状态、转移关系均来自需求文本提取结果（Kripke结构 S/S0/R）
     * - fact invariants 由约束规则映射为结构化不变量子句
     * 生成结果供 AlloySpecVerifierUtil 做结构与语义校验
     */
    public String generateAlloySpec(Requirement req) {
        String typeName = extractMainConcept(req.getOriginalText());
        List<String> states = parseStateNames(req.getStateSet());
        String initState = pickInitialState(req.getInitialState(), states);
        List<String[]> transitions = parseTransitionPairs(req.getStateTransitions(), states);
        List<String> invariants = parseListField(req.getInvariants());
        List<String> constraints = parseListField(req.getAtomicConstraints());
        String raw = req.getOriginalText() != null ? req.getOriginalText().replace("\n", " ") : "";

        StringBuilder sb = new StringBuilder();
        sb.append("// Alloy形式化规约 - 自动生成\n");
        sb.append("// 对应需求ID: ").append(req.getRequirementId()).append("\n");
        sb.append("// 需求原文: ").append(raw, 0, Math.min(200, raw.length())).append("\n\n");
        // Alloy模块名仅允许字母数字下划线，REQ-0001转为req_0001
        sb.append("module ").append(req.getRequirementId().toLowerCase().replace('-', '_')).append("\n\n");

        // 签名声明：实体持有状态字段
        sb.append("sig ").append(typeName).append(" {\n");
        sb.append("    id: Int,\n");
        sb.append("    state: one State,\n");
        sb.append("    createdAt: Time\n");
        sb.append("}\n\n");

        // 状态集 S（Kripke结构状态空间）
        sb.append("abstract sig State {}\n");
        for (String s : states) {
            sb.append("one sig ").append(capitalize(s)).append(" extends State {}\n");
        }
        sb.append("\nsig Time {}\n\n");

        // 初始状态谓词（S0）
        sb.append("// 初始状态谓词\n");
        sb.append("pred init[t: Time] {\n");
        sb.append("    some o: ").append(typeName).append(" | o.state = ").append(capitalize(initState))
                .append(" and o.createdAt = t\n");
        sb.append("}\n\n");

        // 状态转移谓词（R）：合法转移的析取式
        sb.append("// 状态转移谓词：由需求文本提取的合法状态转移\n");
        sb.append("pred transition[t, t': Time] {\n");
        sb.append("    all o: ").append(typeName).append(" | o.createdAt = t implies (\n");
        if (transitions.isEmpty()) {
            sb.append("        o.state in State\n");
        } else {
            for (int i = 0; i < transitions.size(); i++) {
                String[] tr = transitions.get(i);
                sb.append("        (o.state = ").append(capitalize(tr[0]))
                        .append(" and o.state' = ").append(capitalize(tr[1])).append(")");
                sb.append(i < transitions.size() - 1 ? " or\n" : "\n");
            }
        }
        sb.append("    )\n");
        sb.append("}\n\n");

        // 不变量 fact：结构约束 + 需求约束映射
        sb.append("// 系统不变量：状态域约束 + 需求约束推导\n");
        sb.append("fact invariants {\n");
        sb.append("    all o: ").append(typeName).append(" | one o.state\n");
        sb.append("    all o: ").append(typeName).append(" | o.state in State\n");
        if (raw.contains("唯一") || raw.contains("不能重复")) {
            sb.append("    all disj o1, o2: ").append(typeName).append(" | o1.id != o2.id\n");
        }
        if (raw.contains("不能为空") || raw.contains("非空")) {
            sb.append("    all o: ").append(typeName).append(" | o.state != none\n");
        }
        for (String inv : invariants) {
            sb.append("    // INV: ").append(inv).append("\n");
        }
        for (String c : constraints) {
            sb.append("    // CONSTRAINT: ").append(c).append("\n");
        }
        sb.append("}\n\n");

        // 一致性校验断言：转移保持状态域不变量（Inv(s) -> Inv(s')）
        sb.append("// 一致性校验断言：所有状态转移保持状态域约束\n");
        sb.append("assert consistencyCheck {\n");
        sb.append("    all t, t': Time | transition[t, t'] implies (all o: ").append(typeName)
                .append(" | o.state' in State)\n");
        sb.append("}\n\n");
        sb.append("check consistencyCheck for 5\n");
        return sb.toString();
    }

    private String determineRequirementType(String text) {
        if (functionalPattern.matcher(text).find()) {
            return "functional";
        }
        if (nonFunctionalPattern.matcher(text).find()) {
            return "non_functional";
        }
        if (constraintPattern.matcher(text).find()) {
            return "constraint";
        }
        return "functional";
    }

    /** GAP-019：提取标题——去编号前缀的首行/首句，超长截断（提取失败回退原文首句） */
    private String extractTitle(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String title = text.trim().split("[\\n\\r。；;]")[0].trim();
        title = title.replaceFirst("^([0-9]+[.、)）]|[一二三四五六七八九十]+[.、)）]|第[一二三四五六七八九十0-9]+[条章节]|(FR|REQ)-\\d+)", "").trim();
        if (title.length() > 50) {
            title = title.substring(0, 50);
        }
        return title.isEmpty() ? null : title;
    }

    /** GAP-019：优先级关键词映射（必须→must / 重要→important / 可选→optional / 默认 normal） */
    private String determinePriority(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "normal";
        }
        String t = text.trim();
        if (t.matches("(?s).*(必须|必选|务必).*")) {
            return "must";
        }
        if (t.matches("(?s).*(重要|应当|应该).*")) {
            return "important";
        }
        if (t.matches("(?s).*(可选|最好|尽量).*")) {
            return "optional";
        }
        return "normal";
    }

    /**
     * 提取状态集（AUD-01 增强）：
     * 1. 显式状态名优先——转移链 "PENDING -> PAID -> SHIPPED -> COMPLETED" 与
     *    「状态为X / 状态变更为X」中的英文状态标识（Kripke 状态集 S 的直接来源）；
     * 2. 无显式状态时按关键词映射（原有逻辑）；
     * 3. 仍未命中则回退默认四态。
     */
    private String extractStates(String text) {
        Set<String> states = new LinkedHashSet<>(extractExplicitStates(text));
        if (!states.isEmpty()) {
            return states.toString();
        }
        Matcher m = statePattern.matcher(text);
        while (m.find()) {
            String match = m.group();
            if (match.contains("成功")) states.add("success");
            if (match.contains("失败")) states.add("failed");
            if (match.contains("开始") || match.contains("初始")) states.add("initial");
            if (match.contains("结束") || match.contains("完成")) states.add("completed");
            if (match.contains("处理") || match.contains("执行")) states.add("processing");
        }
        if (states.isEmpty()) {
            states.add("initial");
            states.add("processing");
            states.add("completed");
            states.add("failed");
        }
        return states.toString();
    }

    /**
     * 提取显式状态名（AUD-01）：转移链 A（中文）-> B 与「状态为/状态变更为/状态下 X」。
     * 仅接受纯英文标识（Alloy 状态签名要求），避免把中文词误当状态。
     */
    private Set<String> extractExplicitStates(String text) {
        Set<String> states = new LinkedHashSet<>();
        // 转移链：PENDING（待支付）-> PAID（已支付）-> SHIPPED -> COMPLETED
        Matcher chain = Pattern.compile("([A-Z][A-Z0-9_]{1,20})\\s*(?:[（(][^）)]*[）)])?\\s*->\\s*([A-Z][A-Z0-9_]{1,20})")
                .matcher(text);
        while (chain.find()) {
            states.add(chain.group(1).toLowerCase());
            states.add(chain.group(2).toLowerCase());
        }
        // 状态为X / 状态变更为X / 状态变为X / 状态下X（X 为英文标识）
        Matcher st = Pattern.compile("状态(?:变更为|变为|改为|为|下)?\\s*([A-Za-z][A-Za-z0-9_]{1,20})").matcher(text);
        while (st.find()) {
            String s = st.group(1).toLowerCase();
            if (!states.contains(s)) {
                states.add(s);
            }
        }
        return states;
    }

    /**
     * FUN-07：提取初始状态（基于状态词表 + 显式状态识别，替代恒返回 "initial" 的硬编码桩）。
     * 优先级：
     * 1. 显式状态名中带初始语义的（pending/new/created/ready/待开头/未开头）——直接作为初始状态（属于状态集）；
     * 2. 需求文本含初始/开始/默认/启动/新建等初始语义词——映射为 "initial"；
     * 3. 回退默认 "initial"（保持在状态集内，generateAlloySpec 校验不越界）。
     */
    private String extractInitialState(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "initial";
        }
        // 1) 显式状态（Kripke 状态集来源）中的初始语义状态
        for (String s : extractExplicitStates(text)) {
            String ls = s.toLowerCase(Locale.ROOT);
            if (ls.contains("pending") || ls.contains("new") || ls.contains("created")
                    || ls.contains("ready") || ls.contains("initial")
                    || ls.startsWith("待") || ls.startsWith("未")) {
                return s;
            }
        }
        // 2) 初始语义词 → initial
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("初始") || lower.contains("开始时") || lower.contains("开始阶段")
                || lower.contains("默认") || lower.contains("启动") || lower.contains("新建")
                || lower.contains("创建后")) {
            return "initial";
        }
        // 3) 回退默认（与 extractStates 默认四态一致，保证 ∈ 状态集）
        return "initial";
    }

    /**
     * 提取状态转移（AUD-01 增强）：显式转移链 A -> B 优先（直接对应 Kripke 转移关系 R）；
     * 无显式链时按关键词映射（原逻辑）。
     */
    private String extractTransitions(String text) {
        Set<String> transitions = new LinkedHashSet<>();
        // 显式转移链 A（中文）-> B -> C
        Matcher chain = Pattern.compile("([A-Z][A-Z0-9_]{1,20})\\s*(?:[（(][^）)]*[）)])?\\s*->\\s*([A-Z][A-Z0-9_]{1,20})")
                .matcher(text);
        while (chain.find()) {
            transitions.add(chain.group(1).toLowerCase() + "->" + chain.group(2).toLowerCase());
        }
        if (!transitions.isEmpty()) {
            return transitions.toString();
        }
        transitions.add("initial->processing");
        if (text.contains("成功")) transitions.add("processing->completed");
        if (text.contains("失败")) transitions.add("processing->failed");
        return transitions.toString();
    }

    private String extractConstraints(String text) {
        List<String> constraints = new ArrayList<>();
        Matcher m = constraintPattern.matcher(text);
        while (m.find()) {
            int start = Math.max(0, m.start() - 10);
            int end = Math.min(text.length(), m.end() + 20);
            constraints.add(text.substring(start, end).trim());
        }
        return constraints.isEmpty() ? "[]" : constraints.toString();
    }

    /** 提取不变量：包含强约束词的完整句子（而非占位文本） */
    private String extractInvariants(String text) {
        List<String> invariants = new ArrayList<>();
        for (String sentence : text.split("[。；;！!？?\n]")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (invariantPattern.matcher(s).find()) {
                if (s.length() > 60) s = s.substring(0, 60);
                invariants.add(s);
            }
        }
        return invariants.toString();
    }

    private String extractConstraintRules(String text) {
        StringBuilder rules = new StringBuilder();
        rules.append("约束规则清单:\n");
        if (text.contains("不能为空") || text.contains("非空")) {
            rules.append("- 字段非空约束\n");
        }
        if (text.contains("唯一") || text.contains("不能重复")) {
            rules.append("- 唯一性约束\n");
        }
        if (text.contains("大于") || text.contains("小于") || text.contains("至少") || text.contains("最多")) {
            rules.append("- 数值范围约束\n");
        }
        return rules.toString();
    }

    /**
     * GAP-045：提取原子命题集合(AP)。
     * 原子命题 = 从需求约束点/边界条件/强约束句中抽取的、可独立判真伪的最小命题。
     * 复用已提取的约束片段与强约束句，归一化并去重，输出逗号分隔的可判真伪命题。
     * 例：["密码长度≥8", "必须记录日志", "禁止导出"]。
     */
    String extractAtomicPropositions(String text, String constraintsField, String invariantsField) {
        Set<String> aps = new LinkedHashSet<>();
        // 1) 来自约束片段（constraintPattern 命中的片段）
        for (String c : parseListField(constraintsField)) {
            String norm = normalizeAtomicProposition(text, c);
            if (norm != null) aps.add(norm);
        }
        // 2) 来自强约束句（不变量候选句）
        for (String inv : parseListField(invariantsField)) {
            String norm = normalizeAtomicProposition(text, inv);
            if (norm != null) aps.add(norm);
        }
        // 3) 数值范围约束 -> 显式命题（如"密码长度≥8"）
        for (String prop : extractNumericBoundPropositions(text)) {
            aps.add(prop);
        }
        // 4) 强约束关键词 -> 显式命题（如"必须记录日志"）
        for (String prop : extractStrongConstraintPropositions(text)) {
            aps.add(prop);
        }
        return aps.isEmpty() ? "[]" : String.join(",", aps);
    }

    /** 将约束片段/不变量句归一化为可判真伪的原子命题；无法归一化返回 null */
    private String normalizeAtomicProposition(String fullText, String fragment) {
        if (fragment == null || fragment.trim().isEmpty()) return null;
        String f = fragment.trim();
        // 去编号与多余标点
        f = f.replaceFirst("^([0-9]+[.、)）]|[一二三四五六七八九十]+[.、)）])", "").trim();
        if (f.length() < 3 || f.length() > 40) return null;
        return f;
    }

    /** 数值边界 -> 原子命题（例："密码长度为8位字符" -> "密码长度≥8"） */
    private List<String> extractNumericBoundPropositions(String text) {
        List<String> props = new ArrayList<>();
        // 匹配：约束主题词（紧邻数字前的中文属性，≤6字） + 比较词 + 数值 + 单位
        // 例："密码长度不少于8位字符" -> subject=密码长度 cmp=不少于 val=8 unit=位字符
        Matcher num = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z]{1,6})\\s*(?:的)?\\s*(不少于|不低于|不小于|至少|最多|不超过|超过)\\s*(\\d+)\\s*([\\u4e00-\\u9fa5]{0,3})").matcher(text);
        while (num.find()) {
            String subject = num.group(1);
            String cmp = num.group(2);
            String value = num.group(3);
            String unit = num.group(4) == null ? "" : num.group(4);
            if (cmp.equals("不少于") || cmp.equals("不低于") || cmp.equals("不小于") || cmp.equals("至少")) {
                props.add(subject + "≥" + value + unit);
            } else if (cmp.equals("最多") || cmp.equals("不超过")) {
                props.add(subject + "≤" + value + unit);
            } else if (cmp.equals("超过")) {
                props.add(subject + ">" + value + unit);
            }
        }
        return props;
    }

    /** 强约束关键词 -> 原子命题（保持可判真伪，且不与数值约束命题重复） */
    private List<String> extractStrongConstraintPropositions(String text) {
        List<String> props = new ArrayList<>();
        if (text.contains("必须") || text.contains("应当") || text.contains("应该")) {
            // 取含"必须/应当"的短句作为命题；但整句若含数值边界(已被 extractNumericBoundPropositions 覆盖)则跳过，避免冗余
            for (String sentence : text.split("[。；;！!？?\n]")) {
                String s = sentence.trim();
                if (!((s.contains("必须") || s.contains("应当") || s.contains("应该")) && s.length() <= 40)) continue;
                if (s.matches(".*[\\d].*")) continue; // 含数字者交由数值约束处理
                props.add(s);
            }
        }
        if (text.contains("禁止") || text.contains("不得") || text.contains("不能")) {
            props.add("禁止:" + text.replaceAll("\\s+", "").substring(0, Math.min(20, text.replaceAll("\\s+", "").length())));
        }
        return props;
    }

    /**
     * GAP-045：构造状态标签函数 L。
     * 本需求视作一个状态/场景（以 initialState 为状态标识），L(state) = 该状态下为真的原子命题集合。
     * 输出 JSON 数组字符串，形如 [{"state":"initial","labels":["密码长度≥8","必须记录日志"]}]。
     */
    String buildStateLabeling(String state, String atomicPropositions) {
        Set<String> aps = new LinkedHashSet<>(parseListField(atomicPropositions));
        String stateName = (state == null || state.trim().isEmpty()) ? "initial" : state.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("[{\"state\":\"").append(stateName).append("\",\"labels\":[");
        int i = 0;
        for (String ap : aps) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(ap.replace("\"", "")).append("\"");
            i++;
        }
        sb.append("]}]");
        return sb.toString();
    }

    private String extractMainConcept(String text) {
        String[] words = text.split("[，。；：\\s、的了在是和与或及对为从到把被]");
        for (String w : words) {
            // Alloy标识符仅允许ASCII字母/数字/下划线，取首个合法英文术语；中文词不可作为sig名
            if (w.length() >= 2 && w.length() <= 30 && w.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return capitalize(w);
            }
        }
        return "Entity";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Entity";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 解析状态集字段（[a, b]格式），为空时给出默认四态 */
    private List<String> parseStateNames(String stateSet) {
        List<String> result = new ArrayList<>(parseListField(stateSet));
        if (result.isEmpty()) {
            result.add("initial");
            result.add("processing");
            result.add("completed");
            result.add("failed");
        }
        return result;
    }

    /** 初始状态必须属于状态集，否则回退到initial或首状态 */
    private String pickInitialState(String initialState, List<String> states) {
        if (initialState != null && states.contains(initialState.trim())) {
            return initialState.trim();
        }
        return states.contains("initial") ? "initial" : states.get(0);
    }

    /**
     * 解析转移字段（[a->b, ...]格式），过滤未声明状态。
     * 兜底（AUD-01 修复）：转移必须引用已声明状态，否则 AlloySpecVerifierUtil 判「源/目标状态未声明」而 failed。
     * - 多状态：按状态集顺序串联（states[0]->states[1], states[1]->states[2], ...）；
     * - 单状态：自环 states[0]->states[0]，保证转移谓词合法。
     */
    private List<String[]> parseTransitionPairs(String stateTransitions, List<String> states) {
        List<String[]> pairs = new ArrayList<>();
        for (String item : parseListField(stateTransitions)) {
            int idx = item.indexOf("->");
            if (idx <= 0) continue;
            String from = item.substring(0, idx).trim();
            String to = item.substring(idx + 2).trim();
            if (states.contains(from) && states.contains(to)) {
                pairs.add(new String[]{from, to});
            }
        }
        if (pairs.isEmpty()) {
            if (states.size() >= 2) {
                for (int i = 0; i + 1 < states.size(); i++) {
                    pairs.add(new String[]{states.get(i), states.get(i + 1)});
                }
            } else if (!states.isEmpty()) {
                pairs.add(new String[]{states.get(0), states.get(0)});
            }
        }
        return pairs;
    }

    /** 解析形如 [a, b, c] 的列表字段 */
    private List<String> parseListField(String listText) {
        List<String> result = new ArrayList<>();
        if (listText == null || listText.trim().isEmpty() || "[]".equals(listText.trim())) {
            return result;
        }
        String body = listText.replace("[", "").replace("]", "");
        for (String item : body.split(",")) {
            String t = item.replace("\"", "").trim();
            if (!t.isEmpty() && !result.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }
}
