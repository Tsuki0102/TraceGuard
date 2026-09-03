package com.traceguard.core;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2-2 + P2-4：缺陷行号定位器（AST 优先，关键词启发式降级兜底）。
 *
 * 原实现（ConsistencyChecker#locateDefectLine）依赖"中文子类型字符串 contains"做分支，
 * 且 {@code findLineByKeywords} 返回首个含关键词的行（常命中注释/变量名/字符串字面量），
 * 与缺陷成因无因果关系。本类改为：
 * <ol>
 *   <li>由 {@link DefectSubType#locator()} 得到 {@link LocatorStrategy}（枚举 switch，编译器可校验）；</li>
 *   <li>用 JavaParser 解析代码单元（codeContent 即方法声明源码）按语法节点定位；</li>
 *   <li>AST 失败（片段不可解析/语法不支持）时降级关键词启发式，再降级"方法体首条语句"。</li>
 * </ol>
 *
 * 行号口径不变：返回源文件绝对行号 = {@code startLine + 方法内 0-based 偏移}；startLine 缺失时按 1-based 偏移。
 */
public final class DefectLocator {

    private static final Logger log = LoggerFactory.getLogger(DefectLocator.class);

    private DefectLocator() {}

    /** 合成包装类名：把方法声明片段包成可解析的编译单元（用于取准确的 AST 行号） */
    private static final String PROBE_CLASS = "__TraceGuardProbe__";

    /** 需求文本中的数值（与既有 findNumericLine 口径一致） */
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,4})\\b");

    /** 状态字段/状态赋值方法名特征 */
    private static final String[] STATE_TOKENS = {"status", "state", "stage", "phase", "statuscode"};

    /** 比较运算符（数值/阈值类缺陷只在这些表达式里定位字面量） */
    private static final Set<BinaryExpr.Operator> COMPARISON_OPS = EnumSet.of(
            BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS,
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS);

    /**
     * 定位缺陷行号（绝对行号）。
     *
     * @param subTypeLabel 缺陷子类型（中文标签，兼容存量字符串；未知值按逻辑偏离处理）
     * @param reqText      需求原文（数值冲突比对用）
     * @param codeContent  代码单元内容（方法声明源码）
     * @param startLine    代码单元在源文件中的起始行号（&gt;0 生效）
     */
    public static Integer locate(String subTypeLabel, String reqText, String codeContent, Integer startLine) {
        DefectSubType sub = DefectSubType.fromLabel(subTypeLabel);
        return locate(sub != null ? sub : DefectSubType.LOGIC_DEVIATION, reqText, codeContent, startLine);
    }

    /** 按枚举子类型定位（推荐入口，等价 locateTopK(...,1) 的首个候选） */
    public static Integer locate(DefectSubType subType, String reqText, String codeContent, Integer startLine) {
        List<Integer> top = locateTopK(subType, reqText, codeContent, startLine, 1);
        return top.isEmpty() ? null : top.get(0);
    }

    /**
     * A5（参赛优化批次 2026-09-03）：候选行生成 + 评分排序，返回按置信度降序的 top-k 绝对行号。
     *
     * v2 的单行定位在"业务逻辑不一致/约束缺失"类上实际退化为方法起始行（首语句常与方法声明重合），
     * 与基线无异（实测 52.9% vs 47.1%）。v3 候选池按证据强度评分：
     *   +3.0 数值冲突行（AST 比较表达式内字面量与需求数值冲突，NUMERIC 策略主证据）
     *   +2.5 状态赋值行（setStatus/状态字段赋值，STATE 策略主证据）
     *   +2.2 需求数值词面锚点行
     *   +2.0 比较表达式行（含数值字面量）/ +1.0 无字面量
     *   +1.6 需求词→代码标识符对齐行（中文分词 + 双语词典 + camelCase 切分，每命中词 0.8，上限 2.4）
     *   +1.2 校验/拒绝行（validate、check、require、assert 前缀调用）
     *   +1.0 关键词启发式命中（降级链保留）
     *   +0.5 方法体首条语句（兜底）/ +0.2 方法声明行（最终兜底）
     * 同分行靠后者优先（缺陷语义多位于方法体深处）。
     */
    public static List<Integer> locateTopK(DefectSubType subType, String reqText, String codeContent,
                                           Integer startLine, int k) {
        String code = codeContent == null ? "" : codeContent;
        List<Integer> emptyOut = new ArrayList<>();
        if (code.isEmpty() || k <= 0) {
            if (base(startLine)) {
                emptyOut.add(startLine);
            }
            return emptyOut;
        }
        LocatorStrategy strategy = subType == null ? LocatorStrategy.FIRST_STATEMENT : subType.locator();
        String[] lines = code.split("\n", -1);
        MethodDeclaration method = parseMethod(code);
        int methodIdx = method != null ? methodContentIndex(method) : estimateMethodIndex(lines);

        // 候选打分表：内容行号（0-based） -> 分数
        java.util.TreeMap<Integer, Double> score = new java.util.TreeMap<>();

        // A) AST 候选
        if (method != null) {
            Integer numeric = numericLiteralLine(method, reqText);
            if (numeric != null && numeric >= methodIdx) {
                score.merge(numeric, 3.0, Double::sum);
            }
            for (BinaryExpr be : method.findAll(BinaryExpr.class)) {
                if (!COMPARISON_OPS.contains(be.getOperator())) {
                    continue;
                }
                Integer line = offsetOf(be);
                if (line == null || line < methodIdx) {
                    continue;
                }
                boolean hasLiteral = !be.findAll(IntegerLiteralExpr.class).isEmpty()
                        || !be.findAll(LongLiteralExpr.class).isEmpty();
                score.merge(line, hasLiteral ? 2.0 : 1.0, Double::sum);
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if (name.length() > 3 && name.startsWith("set") && containsStateToken(name.substring(3))) {
                    Integer line = offsetOf(call);
                    if (line != null && line >= methodIdx) {
                        score.merge(line, 2.5, Double::sum);
                    }
                }
            }
            for (AssignExpr assign : method.findAll(AssignExpr.class)) {
                Expression tgt = assign.getTarget();
                String name = tgt instanceof NameExpr ? ((NameExpr) tgt).getNameAsString()
                        : tgt instanceof FieldAccessExpr ? ((FieldAccessExpr) tgt).getNameAsString() : "";
                if (containsStateToken(name)) {
                    Integer line = offsetOf(assign);
                    if (line != null && line >= methodIdx) {
                        score.merge(line, 2.5, Double::sum);
                    }
                }
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString().toLowerCase(Locale.ROOT);
                if (name.startsWith("validate") || name.startsWith("check") || name.startsWith("require")
                        || name.startsWith("assert")) {
                    Integer line = offsetOf(call);
                    if (line != null && line >= methodIdx) {
                        score.merge(line, 1.2, Double::sum);
                    }
                }
            }
            // A5：约束缺失类（MISSING_VALIDATION）对"最后一条校验/throw 行"加权——
            // 参数校验不完整通常缺在末尾（遗漏最后一项检查/最后一个参数）
            for (com.github.javaparser.ast.stmt.ThrowStmt ts : method.findAll(
                    com.github.javaparser.ast.stmt.ThrowStmt.class)) {
                Integer tLine = offsetOf(ts);
                if (tLine != null && tLine >= methodIdx) {
                    score.merge(tLine, 1.2, Double::sum);
                }
            }
            if (strategy == LocatorStrategy.MISSING_VALIDATION) {
                Integer lastGuard = null;
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    String nm = call.getNameAsString().toLowerCase(java.util.Locale.ROOT);
                    if (nm.startsWith("validate") || nm.startsWith("check") || nm.startsWith("require")
                            || nm.startsWith("assert")) {
                        Integer l2 = offsetOf(call);
                        if (l2 != null && l2 >= methodIdx && (lastGuard == null || l2 > lastGuard)) {
                            lastGuard = l2;
                        }
                    }
                }
                for (com.github.javaparser.ast.stmt.ThrowStmt ts : method.findAll(
                        com.github.javaparser.ast.stmt.ThrowStmt.class)) {
                    Integer l3 = offsetOf(ts);
                    if (l3 != null && l3 >= methodIdx && (lastGuard == null || l3 > lastGuard)) {
                        lastGuard = l3;
                    }
                }
                if (lastGuard != null) {
                    score.merge(lastGuard, 1.5, Double::sum);
                }
            }
            Integer first = bodyFirstStatementLine(method);
            if (first != null && first >= methodIdx) {
                score.merge(first, 0.5, Double::sum);
            }
        }

        // B) 需求数值词面锚点
        Integer numericKw = findNumericLine(lines, reqText);
        if (numericKw != null && numericKw >= methodIdx) {
            score.merge(numericKw, 2.2, Double::sum);
        }

        // C) 需求词 -> 代码标识符对齐（中文分词 + 双语词典 + camelCase）
        Map<Integer, Integer> termHits = termAlignmentLines(reqText, lines, methodIdx);
        for (Map.Entry<Integer, Integer> e : termHits.entrySet()) {
            score.merge(e.getKey(), Math.min(2.4, 0.8 * e.getValue()), Double::sum);
        }

        // D) 关键词启发式（降级链保留）
        Integer kw = locateByKeyword(strategy, reqText, lines);
        if (kw != null && kw >= methodIdx) {
            score.merge(kw, 1.0, Double::sum);
        }


        // 方法声明行兜底
        score.merge(methodIdx, 0.2, Double::sum);

        // 排序：分数降序，同分行号靠后优先（缺陷语义多位于方法体深处）
        List<Integer> ranked = new ArrayList<>(score.entrySet()).stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : Integer.compare(b.getKey(), a.getKey());
                })
                .limit(Math.max(k, 0))
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());

        // 绝对行号换算
        List<Integer> out = new ArrayList<>();
        for (Integer t : ranked) {
            if (t < 0) {
                t = 0;
            }
            out.add(base(startLine) ? startLine + (t - methodIdx) : t + 1);
        }
        return out;
    }

    /**
     * A5：需求词 -> 代码行对齐。中文分词后经双语词典映射英文等价词，
     * 与代码行的 camelCase/下划线切分词（英文）或词面（中文）比对，返回 行号 -> 命中词数。
     */
    private static Map<Integer, Integer> termAlignmentLines(String reqText, String[] lines, int methodIdx) {
        Map<Integer, Integer> hits = new java.util.HashMap<>();
        if (reqText == null || reqText.isEmpty()) {
            return hits;
        }
        Set<String> want = new java.util.HashSet<>();
        for (String zh : SimilarityScorer.tokenize(reqText).keySet()) {
            want.add(zh);
            List<String> en = ZH_EN_DICT.get(zh);
            if (en != null) {
                for (String e : en) {
                    want.add(e.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (want.isEmpty()) {
            return hits;
        }
        for (int i = Math.max(methodIdx, 0); i < lines.length; i++) {
            String raw = lines[i];
            String lineLower = raw.toLowerCase(Locale.ROOT);
            int count = 0;
            for (String w : want) {
                if (w.length() < 2) {
                    continue;
                }
                boolean hit;
                if (w.matches("[a-z0-9_]+")) {
                    hit = false;
                    for (String tok : SimilarityScorer.splitCamelCase(raw)) {
                        if (tok.equals(w)) {
                            hit = true;
                            break;
                        }
                    }
                } else {
                    hit = lineLower.contains(w);
                }
                if (hit) {
                    count++;
                }
            }
            if (count > 0) {
                hits.merge(i, count, Integer::sum);
            }
        }
        return hits;
    }

    /** A5：中英语义词典（与 SimilarityScorer 同源加载，供需求词→代码标识符对齐） */
    private static final Map<String, List<String>> ZH_EN_DICT =
            com.traceguard.util.BilingualDictLoader.loadDictionary();

    private static boolean base(Integer startLine) {
        return startLine != null && startLine > 0;
    }

    // ==================== AST 定位 ====================

    /** AST 定位；返回目标节点在 codeContent 中的 0-based 行号；失败返回 null（降级关键词启发式） */
    private static Integer locateByAst(LocatorStrategy strategy, String reqText, MethodDeclaration method) {
        try {
            switch (strategy) {
                case NUMERIC_LITERAL:
                    return numericLiteralLine(method, reqText);
                case STATE_FIELD:
                    return stateAssignLine(method);
                case MISSING_VALIDATION:
                case FIRST_STATEMENT:
                    return bodyFirstStatementLine(method);
                case METHOD_START:
                default:
                    return methodContentIndex(method);
            }
        } catch (Exception e) {
            log.debug("AST 定位失败，降级关键词启发式: strategy={}, {}", strategy, e.getMessage());
            return null;
        }
    }

    /** 方法声明在 codeContent 中的 0-based 行号（包装类多出 1 行，故 begin.line - 2） */
    private static int methodContentIndex(MethodDeclaration method) {
        Integer begin = method.getBegin().map(p -> p.line).orElse(null);
        return begin != null ? Math.max(0, begin - 2) : 0;
    }

    /** AST 解析失败时的方法声明行估计：内容中第一个非注释/非注解/非括号行 */
    private static int estimateMethodIndex(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("/") || t.startsWith("*") || t.startsWith("@")) continue;
            if (t.equals("{") || t.equals("}")) continue;
            return i;
        }
        return 0;
    }

    /**
     * 数值/阈值不一致：定位与需求数值冲突的比较表达式所在行。
     * 优先"字面量不在需求数值集合内"的比较行（潜在越界/阈值错误），否则取首个含数值字面量的比较行。
     */
    private static Integer numericLiteralLine(MethodDeclaration method, String reqText) {
        Set<Integer> reqNums = extractNumbers(reqText);
        Integer firstCmp = null;
        Integer firstConflictLiteral = null;
        Integer firstLiteral = null;
        // 优先：比较表达式内的数值字面量（需求数值 vs 代码比较值冲突，如 amount > 100 而需求要求 50）
        for (BinaryExpr be : method.findAll(BinaryExpr.class)) {
            if (!COMPARISON_OPS.contains(be.getOperator())) {
                continue;
            }
            for (IntegerLiteralExpr lit : be.findAll(IntegerLiteralExpr.class)) {
                Integer line = offsetOf(lit);
                if (line == null) {
                    continue;
                }
                if (firstCmp == null) {
                    firstCmp = line;
                }
                int value = parseInt(lit.getValue());
                if (!reqNums.isEmpty() && value > 0 && !reqNums.contains(value)) {
                    return line;
                }
            }
            for (LongLiteralExpr lit : be.findAll(LongLiteralExpr.class)) {
                Integer line = offsetOf(lit);
                if (line != null && firstCmp == null) {
                    firstCmp = line;
                }
            }
        }
        // 兜底：方法内任意数值字面量（比较对象常量的声明行，如 int max = 100），优先与需求数值冲突者
        for (IntegerLiteralExpr lit : method.findAll(IntegerLiteralExpr.class)) {
            Integer line = offsetOf(lit);
            if (line == null) {
                continue;
            }
            if (firstLiteral == null) {
                firstLiteral = line;
            }
            int value = parseInt(lit.getValue());
            if (!reqNums.isEmpty() && value > 0 && !reqNums.contains(value) && firstConflictLiteral == null) {
                firstConflictLiteral = line;
            }
        }
        if (firstConflictLiteral != null) {
            return firstConflictLiteral;
        }
        if (firstCmp != null) {
            return firstCmp;
        }
        return firstLiteral;
    }

    /** 状态流转/不变量违反：定位状态字段被赋值的行（setStatus(x) / this.status = x） */
    private static Integer stateAssignLine(MethodDeclaration method) {
        Integer best = null;
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.length() > 3 && name.startsWith("set") && containsStateToken(name.substring(3))) {
                Integer line = offsetOf(call);
                if (line != null && (best == null || line < best)) {
                    best = line;
                }
            }
        }
        for (AssignExpr assign : method.findAll(AssignExpr.class)) {
            Expression target = assign.getTarget();
            String name = target instanceof NameExpr ? ((NameExpr) target).getNameAsString()
                    : target instanceof FieldAccessExpr ? ((FieldAccessExpr) target).getNameAsString() : "";
            if (containsStateToken(name)) {
                Integer line = offsetOf(assign);
                if (line != null && (best == null || line < best)) {
                    best = line;
                }
            }
        }
        return best;
    }

    /** 约束/校验缺失：定位方法体首条语句（语义为"应在该行之前插入校验"） */
    private static Integer bodyFirstStatementLine(MethodDeclaration method) {
        Optional<com.github.javaparser.ast.stmt.BlockStmt> body = method.getBody();
        if (!body.isPresent() || body.get().getStatements().isEmpty()) {
            return 0;
        }
        Statement first = body.get().getStatements().get(0);
        Integer line = offsetOf(first);
        return line != null ? line : 0;
    }

    /** 把方法声明片段包成编译单元解析；失败（语法不支持/非方法片段）返回 null 触发降级 */
    private static MethodDeclaration parseMethod(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse("class " + PROBE_CLASS + " {\n" + code + "\n}");
            return cu.findFirst(MethodDeclaration.class).orElse(null);
        } catch (Exception e) {
            // 片段含不支持语法（record/文本块等）或不是方法声明 -> 降级
            return null;
        }
    }

    /** 节点在 codeContent 中的 0-based 行偏移（包装类多出 1 行，故 line - 2） */
    private static Integer offsetOf(Node node) {
        return node.getBegin().map(p -> p.line - 2).orElse(null);
    }

    // ==================== 关键词启发式降级（原实现保留） ====================

    private static Integer locateByKeyword(LocatorStrategy strategy, String reqText, String[] lines) {
        switch (strategy) {
            case STATE_FIELD:
                return findLineByKeywords(lines, "status", "state", "pending", "paid", "published",
                        "active", "inactive", "success", "failed", "completed", "unpublished");
            case NUMERIC_LITERAL:
                return findNumericLine(lines, reqText);
            case MISSING_VALIDATION:
                return findLineByKeywords(lines, "if", "validate", "check", "assert", "require",
                        "null", "empty", "blank");
            case FIRST_STATEMENT:
                return findFirstStatementLine(lines);
            case METHOD_START:
            default:
                return 0;
        }
    }

    /** 在代码行中查找首个包含任一关键词（忽略大小写）的行，返回 0-based 偏移；无则返回 null */
    private static Integer findLineByKeywords(String[] lines, String... keywords) {
        String[] lowerKw = new String[keywords.length];
        for (int i = 0; i < keywords.length; i++) {
            lowerKw[i] = keywords[i].toLowerCase(Locale.ROOT);
        }
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            for (String k : lowerKw) {
                if (l.contains(k)) return i;
            }
        }
        return null;
    }

    /** 定位含数值常量的代码行；若需求含数值，优先不匹配需求数值的代码行；返回 0-based 偏移 */
    private static Integer findNumericLine(String[] lines, String reqText) {
        Set<Integer> reqNums = extractNumbers(reqText);
        Integer fallback = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = NUMBER.matcher(lines[i]);
            if (m.find()) {
                if (fallback == null) fallback = i;
                int n = Integer.parseInt(m.group(1));
                if (!reqNums.isEmpty() && !reqNums.contains(n)) {
                    return i;
                }
            }
        }
        return fallback;
    }

    /** 定位方法体首个非空、非纯注释的语句行（0-based 偏移）；无则返回 null */
    private static Integer findFirstStatementLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            if (trimmed.equals("{") || trimmed.equals("}")) continue;
            return i;
        }
        return null;
    }

    // ==================== 小工具 ====================

    private static Set<Integer> extractNumbers(String text) {
        Set<Integer> nums = new java.util.HashSet<>();
        if (text == null) {
            return nums;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            nums.add(Integer.parseInt(m.group(1)));
        }
        return nums;
    }

    private static boolean containsStateToken(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String t : STATE_TOKENS) {
            if (lower.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String literal) {
        try {
            String v = literal.replace("_", "");
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return (int) Long.parseLong(v.substring(2), 16);
            }
            return (int) Long.parseLong(v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 诊断用：返回该子类型的定位策略（前端/报告展示"定位依据"） */
    public static String describeStrategy(String subTypeLabel) {
        DefectSubType sub = DefectSubType.fromLabel(subTypeLabel);
        return (sub != null ? sub.locator() : LocatorStrategy.FIRST_STATEMENT).name();
    }

    /** 诊断用：全部已知子类型（评测报告留档） */
    public static List<String> knownSubTypes() {
        List<String> out = new ArrayList<>();
        for (DefectSubType t : DefectSubType.values()) {
            out.add(t.label());
        }
        return out;
    }
}
