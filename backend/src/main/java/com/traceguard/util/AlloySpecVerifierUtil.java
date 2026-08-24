package com.traceguard.util;

import com.traceguard.config.AlloyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alloy形式化规约校验器
 * GAP-002：优先走 AlloyAnalyzerUtil 真实语义求解（engine=alloy，输出 SAT/UNSAT/UNKNOWN 求解态与反例），
 * 开关关闭 / 求解异常 / 超时 / 任务级熔断时回退自研结构校验（engine=structure，改造前行为）。
 * 结构语法校验：module/sig/pred/fact/check完整性、花括号与圆括号配对、关键块非空
 * Kripke语义校验：初始状态有效、转移引用状态已声明、状态可达性、不变量充分性
 * 对应校验公式：∀s∈S0: Inv(s) ∧ ∀(s,s')∈R: Inv(s)→Inv(s')
 */
@Component
public class AlloySpecVerifierUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlloySpecVerifierUtil.class);

    /** 任务级熔断阈值（GAP-002：单任务内连续 3 次求解异常/超时 -> 本任务后续直接结构校验） */
    public static final int CIRCUIT_BREAK_THRESHOLD = 3;

    private static AlloyAnalyzerUtil alloyAnalyzer;
    private static AlloyProperties alloyProperties;

    @Autowired
    public void init(AlloyAnalyzerUtil analyzer, AlloyProperties properties) {
        AlloySpecVerifierUtil.alloyAnalyzer = analyzer;
        AlloySpecVerifierUtil.alloyProperties = properties;
    }

    /** 测试用：重置静态配置（包私有） */
    static void configure(AlloyAnalyzerUtil analyzer, AlloyProperties properties) {
        AlloySpecVerifierUtil.alloyAnalyzer = analyzer;
        AlloySpecVerifierUtil.alloyProperties = properties;
    }

    private static final Pattern STATE_SIG = Pattern.compile("one\\s+sig\\s+(\\w+)\\s+extends\\s+State");
    private static final Pattern INIT_STATE = Pattern.compile("o\\.state\\s*=\\s*(\\w+)");
    private static final Pattern TRANSITION_PAIR = Pattern.compile("o\\.state\\s*=\\s*(\\w+)\\s+and\\s+o\\.state'\\s*=\\s*(\\w+)");
    private static final Pattern MODULE_DECL = Pattern.compile("\\bmodule\\s+[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SIG_DECL = Pattern.compile("\\bsig\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\{");
    private static final Pattern PRED_INIT = Pattern.compile("\\bpred\\s+init\\s*\\[");
    private static final Pattern PRED_TRANSITION = Pattern.compile("\\bpred\\s+transition\\s*\\[");
    private static final Pattern FACT_DECL = Pattern.compile("\\bfact\\s+\\w+\\s*\\{");
    private static final Pattern CHECK_DECL = Pattern.compile("\\bcheck\\s+\\w+");

    /** 校验结果 */
    public static class VerifyResult {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int stateCount;
        int transitionCount;
        int invariantCount;
        /** GAP-002：结果来源 engine=alloy（真实求解）/ structure（结构校验降级） */
        String engine = "structure";
        /** GAP-002：求解状态 SAT/UNSAT/UNKNOWN（engine=alloy 时有值） */
        String satStatus;
        /** GAP-002：实例数（SAT 时） */
        int instanceCount;
        /** GAP-002：反例/实例文本 */
        String counterexample;
        /** GAP-002：求解耗时（毫秒） */
        long elapsedMs;
        /** GAP-002：补充消息 */
        String message;

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public String getSatStatus() { return satStatus; }
        public void setSatStatus(String satStatus) { this.satStatus = satStatus; }
        public int getInstanceCount() { return instanceCount; }
        public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }
        public String getCounterexample() { return counterexample; }
        public void setCounterexample(String counterexample) { this.counterexample = counterexample; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public boolean isPassed() {
            return errors.isEmpty() && warnings.isEmpty();
        }

        /** passed=结构与语义均通过；warning=通过但有告警；failed=存在错误 */
        public String status() {
            if (!errors.isEmpty()) return "failed";
            return warnings.isEmpty() ? "passed" : "warning";
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("状态数=").append(stateCount)
              .append(", 转移数=").append(transitionCount)
              .append(", 不变量数=").append(invariantCount).append("。");
            if (errors.isEmpty() && warnings.isEmpty()) {
                sb.append("结构语法校验与Kripke不变量一致性校验全部通过。");
            } else {
                if (!errors.isEmpty()) {
                    sb.append("校验失败：");
                    for (int i = 0; i < errors.size(); i++) {
                        sb.append(i + 1).append(")").append(errors.get(i)).append(" ");
                    }
                } else {
                    sb.append("校验通过（含告警）：");
                    for (int i = 0; i < warnings.size(); i++) {
                        sb.append(i + 1).append(")").append(warnings.get(i)).append(" ");
                    }
                }
            }
            return sb.toString();
        }

        /** 优化建议（通过时为空）：针对每条 errors/warnings 生成可落地的具体修复指令（2.3 整改项） */
        public String suggestion() {
            if (isPassed()) return "";
            StringBuilder sb = new StringBuilder();
            for (String e : errors) {
                sb.append("【错误】").append(e).append("\n修复：").append(fixForError(e)).append("\n");
            }
            for (String w : warnings) {
                sb.append("【建议】").append(w).append("\n处理：").append(fixForWarning(w)).append("\n");
            }
            return sb.toString();
        }

        /** 错误 -> 具体修复指令映射（2.3 整改项） */
        private String fixForError(String error) {
            if (error == null || error.isEmpty()) {
                return "请修正规约内容后重新校验。";
            }
            if (error.contains("规约内容为空")) {
                return "请填写 Alloy 规约内容：需包含 module 声明、sig 签名、pred init/transition、fact 不变量与 check 命令。";
            }
            if (error.contains("缺少module")) {
                return "在文件首行补充 `module 名称` 声明（module 名仅允许字母/数字/下划线，如 `module req_0001`）。";
            }
            if (error.contains("缺少sig")) {
                return "补充实体签名声明，例如 `sig Entity { id: Int, state: one State }`，并将 Entity 替换为实际业务实体名。";
            }
            if (error.contains("缺少初始状态谓词")) {
                return "补充 `pred init[t: Time] { some o: Entity | o.state = 初始状态 and o.createdAt = t }`，其中初始状态须为已声明的状态之一。";
            }
            if (error.contains("缺少状态转移谓词")) {
                return "补充 `pred transition[t, t': Time] { ... }`，用析取式声明合法转移，例如 `(o.state = A and o.state' = B) or (o.state = B and o.state' = C)`。";
            }
            if (error.contains("缺少fact")) {
                return "补充 `fact invariants { ... }` 不变量声明，用 `all` 量化描述需求约束，例如 `all o: Entity | o.state in Initial + Completed`。";
            }
            if (error.contains("缺少check")) {
                return "补充校验命令：`assert 断言名 { ... }` 声明断言，并用 `check 断言名 for 5` 执行校验。";
            }
            if (error.contains("未声明任何状态")) {
                return "补充状态签名，一个状态一个 sig，例如 `one sig Pending extends State {}`、`one sig Paid extends State {}`。";
            }
            if (error.contains("初始状态谓词未指明初始状态")) {
                return "在 `pred init` 中补充初始状态赋值：`o.state = 状态名`（状态名须与状态签名保持一致）。";
            }
            if (error.contains("未在状态集中声明")) {
                return "为该状态补充 `one sig X extends State {}` 声明，或修正 init/transition 中引用的状态名，使引用与状态集一致。";
            }
            if (error.contains("状态转移谓词为空")) {
                return "在 `pred transition` 中补充至少一条合法转移，例如 `(o.state = A and o.state' = B)`；若暂无明确转移关系，可先声明自环 `(o.state = A and o.state' = A)`。";
            }
            if (error.contains("的源状态")) {
                return "为转移的源状态补充状态签名声明，或修正转移引用使其属于状态集。";
            }
            if (error.contains("的目标状态")) {
                return "为转移的目标状态补充状态签名声明，或修正转移引用使其属于状态集。";
            }
            if (error.contains("符号{}不配对")) {
                return "检查花括号配对：补全缺失的 `}` 或删除多余的 `{`（可在编辑器中用括号匹配定位）。";
            }
            if (error.contains("符号()不配对")) {
                return "检查圆括号配对：补全缺失的 `)` 或删除多余的 `(`（可在编辑器中用括号匹配定位）。";
            }
            if (error.contains("可满足实例")) {
                return "求解发现可满足实例，断言可能存在反例。请根据反例调整断言前提或补充 fact 约束排除非法状态，确保断言在所有可达状态下成立。";
            }
            return "请根据上述错误修正需求描述或规约内容后重新校验。";
        }

        /** 警告 -> 处理建议映射（2.3 整改项） */
        private String fixForWarning(String warning) {
            if (warning == null || warning.isEmpty()) {
                return "请结合规约上下文复核该告警是否可接受。";
            }
            if (warning.contains("结构化不变量不足")) {
                return "在 fact 中补充与需求约束对应的不变量子句，例如 `all o: Entity | o.state in Initial + Completed`；建议至少 2 条 `all` 量化子句。";
            }
            if (warning.contains("未提取到需求级不变量文本")) {
                return "在 Alloy 规约的 fact 子句上方补充 `// INV: <需求约束原文>` 注释，便于需求级约束追溯。";
            }
            if (warning.contains("不可达")) {
                return "为该状态补充从初始状态可达的转移路径（如 `(o.state = 前置状态 and o.state' = 该状态)`），或确认其为永不可达的终态后移除该状态。";
            }
            if (warning.contains("无法判定")) {
                return "真实求解无法给出判定（UNKNOWN），建议缩小搜索范围或简化不变量后重试；可暂以结构校验结果为准。";
            }
            return "请结合规约上下文复核该告警是否可接受。";
        }
    }

    /**
     * 规约校验入口（GAP-002）：
     * 优先真实 Alloy 语义求解（engine=alloy）；开关关闭 / 求解异常 / 超时 / 任务级熔断时
     * 降级结构校验（engine=structure，改造前行为）。
     */
    public static VerifyResult verify(String alloyCode) {
        TaskCircuitBreaker breaker = TaskBreakerHolder.get();
        boolean circuitOpen = breaker != null && breaker.isOpen();
        if (alloyProperties != null && alloyProperties.isEnabled()
                && alloyAnalyzer != null && !circuitOpen) {
            try {
                AlloyAnalyzerUtil.AlloyAnalyzeResult r = AlloyAnalyzerUtil.analyze(alloyCode);
                if ("SAT".equals(r.getStatus()) || "UNSAT".equals(r.getStatus())
                        || "UNKNOWN".equals(r.getStatus())) {
                    if (breaker != null) {
                        breaker.recordSuccess();
                    }
                    return fromAlloyResult(r);
                }
                // TIMEOUT / ERROR：记录任务级失败并继续降级
                if (breaker != null) {
                    breaker.recordFailure();
                }
                LOGGER.debug("Alloy 求解未产出可判定结果（{}），降级结构校验", r.getStatus());
            } catch (Exception e) {
                if (breaker != null) {
                    breaker.recordFailure();
                }
                LOGGER.warn("Alloy 求解异常，降级结构校验: {}", e.getMessage());
            }
        }
        return structureVerify(alloyCode);
    }

    /** 将真实求解结果转换为 VerifyResult（engine=alloy；success 由 satStatus 推导：UNSAT 成立，SAT 发现反例） */
    private static VerifyResult fromAlloyResult(AlloyAnalyzerUtil.AlloyAnalyzeResult r) {
        VerifyResult result = new VerifyResult();
        result.setEngine("alloy");
        result.setSatStatus(r.getStatus());
        result.setInstanceCount(r.getInstanceCount());
        result.setCounterexample(r.getCounterexample());
        result.setElapsedMs(r.getElapsedMs());
        result.setMessage(r.getMessage());
        if ("SAT".equals(r.getStatus())) {
            result.errors.add("真实求解发现可满足实例（SAT），断言可能存在反例：" + r.getMessage());
        } else if ("UNKNOWN".equals(r.getStatus())) {
            result.warnings.add("真实求解无法判定（UNKNOWN）：" + r.getMessage());
        }
        return result;
    }

    /**
     * 结构校验（GAP-002 降级路径 / GAP-001 LLM 生成 Alloy 的语法预检）
     * 原 verify 逻辑原样保留。
     */
    public static VerifyResult structureVerify(String alloyCode) {
        VerifyResult result = new VerifyResult();
        result.setEngine("structure");
        if (alloyCode == null || alloyCode.trim().isEmpty()) {
            result.errors.add("规约内容为空");
            return result;
        }
        String code = stripComments(alloyCode);

        // ===== 结构语法校验 =====
        checkBalance(code, '{', '}', result);
        checkBalance(code, '(', ')', result);
        if (!MODULE_DECL.matcher(code).find()) result.errors.add("缺少module模块声明");
        if (!SIG_DECL.matcher(code).find()) result.errors.add("缺少sig签名声明");
        if (!PRED_INIT.matcher(code).find()) result.errors.add("缺少初始状态谓词pred init");
        if (!PRED_TRANSITION.matcher(code).find()) result.errors.add("缺少状态转移谓词pred transition");
        if (!FACT_DECL.matcher(code).find()) result.errors.add("缺少fact不变量声明");
        if (!CHECK_DECL.matcher(code).find()) result.errors.add("缺少check校验命令");

        // ===== Kripke语义校验 =====
        List<String> states = extractStates(code);
        result.stateCount = states.size();
        if (states.isEmpty()) {
            result.errors.add("未声明任何状态（one sig X extends State）");
            return result;
        }

        String initBlock = extractBlock(code, "pred", "init");
        String initState = null;
        if (initBlock != null) {
            Matcher m = INIT_STATE.matcher(initBlock);
            if (m.find()) initState = m.group(1);
        }
        if (initState == null) {
            result.errors.add("初始状态谓词未指明初始状态（o.state = X）");
        } else if (!states.contains(initState)) {
            result.errors.add("初始状态" + initState + "未在状态集中声明");
        }

        String transitionBlock = extractBlock(code, "pred", "transition");
        List<String[]> transitions = new ArrayList<>();
        if (transitionBlock != null) {
            Matcher m = TRANSITION_PAIR.matcher(transitionBlock);
            while (m.find()) {
                transitions.add(new String[]{m.group(1), m.group(2)});
            }
        }
        result.transitionCount = transitions.size();
        if (transitions.isEmpty()) {
            result.errors.add("状态转移谓词为空，未定义任何合法转移（Kripke结构R为空集）");
        }
        for (String[] tr : transitions) {
            if (!states.contains(tr[0])) {
                result.errors.add("转移" + tr[0] + "->" + tr[1] + "的源状态" + tr[0] + "未声明");
            }
            if (!states.contains(tr[1])) {
                result.errors.add("转移" + tr[0] + "->" + tr[1] + "的目标状态" + tr[1] + "未声明");
            }
        }

        // 不变量充分性：fact块中量化子句 + INV注释数
        String factBlock = extractBlock(code, "fact", null);
        if (factBlock != null) {
            Matcher inv = Pattern.compile("\\ball\\b").matcher(factBlock);
            while (inv.find()) result.invariantCount++;
        }
        Matcher invComments = Pattern.compile("//\\s*INV:").matcher(alloyCode);
        int invTextCount = 0;
        while (invComments.find()) invTextCount++;
        if (result.invariantCount < 2) {
            result.warnings.add("结构化不变量不足，建议补充需求约束对应的不变量子句");
        } else if (invTextCount == 0) {
            result.warnings.add("未提取到需求级不变量文本（// INV），建议需求描述包含明确的约束语句");
        }

        // 可达性：从初始状态沿转移边BFS
        if (initState != null && states.contains(initState) && !transitions.isEmpty()) {
            Set<String> reachable = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(initState);
            reachable.add(initState);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (String[] tr : transitions) {
                    if (tr[0].equals(cur) && reachable.add(tr[1])) {
                        queue.add(tr[1]);
                    }
                }
            }
            for (String s : states) {
                if (!reachable.contains(s)) {
                    result.warnings.add("状态" + s + "从初始状态不可达，请确认是否为终态或补充转移路径");
                }
            }
        }
        return result;
    }

    /** 去除行注释与块注释（保留语义结构） */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        return noBlock.replaceAll("//[^\n]*", " ");
    }

    private static void checkBalance(String code, char open, char close, VerifyResult result) {
        int depth = 0;
        for (char c : code.toCharArray()) {
            if (c == open) depth++;
            if (c == close) depth--;
            if (depth < 0) break;
        }
        if (depth != 0) {
            result.errors.add("符号" + open + close + "不配对（差值=" + depth + "）");
        }
    }

    private static List<String> extractStates(String code) {
        List<String> states = new ArrayList<>();
        Matcher m = STATE_SIG.matcher(code);
        while (m.find()) {
            if (!states.contains(m.group(1))) states.add(m.group(1));
        }
        return states;
    }

    /** 提取指定声明块的花括号内容：kind=pred/fact，name为谓词/事实名（null表示第一个） */
    private static String extractBlock(String code, String kind, String name) {
        Pattern p = name != null
                ? Pattern.compile("\\b" + kind + "\\s+" + name + "\\b[^{]*\\{")
                : Pattern.compile("\\b" + kind + "\\s+\\w+[^{]*\\{");
        Matcher m = p.matcher(code);
        if (!m.find()) return null;
        int start = m.end();
        int depth = 1;
        int i = start;
        while (i < code.length() && depth > 0) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            i++;
        }
        return depth == 0 ? code.substring(start, i - 1) : code.substring(start);
    }
}
