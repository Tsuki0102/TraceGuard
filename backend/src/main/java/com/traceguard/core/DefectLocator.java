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

    /** 按枚举子类型定位（推荐入口） */
    public static Integer locate(DefectSubType subType, String reqText, String codeContent, Integer startLine) {
        String code = codeContent == null ? "" : codeContent;
        if (code.isEmpty()) {
            return base(startLine) ? startLine : null;
        }
        LocatorStrategy strategy = subType == null ? LocatorStrategy.FIRST_STATEMENT : subType.locator();
        String[] lines = code.split("\n", -1);

        // P2-2（实测修正）：CodeUnit.codeContent = 方法 toString()，含方法前导 javadoc/注解。
        // 若直接 startLine + 内容行号会把 javadoc 行数重复计入（实测系统性 +N 偏移）。
        // 统一锚定到"方法声明行"：目标绝对行号 = startLine + (目标内容行号 - 方法声明内容行号)。
        MethodDeclaration method = parseMethod(code);
        int methodIdx = method != null ? methodContentIndex(method) : estimateMethodIndex(lines);

        Integer target = method == null ? null : locateByAst(strategy, reqText, method);
        if (target == null) {
            target = locateByKeyword(strategy, reqText, lines);
        }
        // 关键词启发式可能命中前导 javadoc/注释区（如注释含 REQ-004 数字、状态词），
        // 其行号位于方法声明之前（target < methodIdx），属噪声，丢弃后走下一级兜底
        if (target != null && target < methodIdx) {
            target = null;
        }
        if (target == null) {
            target = findFirstStatementLine(lines);
        }
        if (target != null && target < methodIdx) {
            target = methodIdx; // 首条语句仍在方法声明前（纯 javadoc 无方法体）时锚定方法声明行
        }
        if (target == null) {
            target = methodIdx;
        }
        if (target < 0) {
            target = 0;
        }
        if (base(startLine)) {
            return startLine + (target - methodIdx);
        }
        return target + 1;
    }

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
