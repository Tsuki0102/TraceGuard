package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.service.ParseFailure;
import com.traceguard.util.ContentHashUtil;
import com.traceguard.util.PythonCfgBuilderUtil;
import com.traceguard.util.SemanticVectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T11 多语言扩展：Python 代码解析器（language=python），基于 tree-sitter（JVM 绑定）。
 * 与 Java 链路同构产出：方法级 {@link CodeUnit}（含 AST 级 CFG / 语义特征词 / 圈复杂度 / 内容哈希）
 * + 文件级基础缺陷检测（裸 except / 可变默认参数 / while True 死循环 / open 资源未释放 / SQL 格式化拼接）。
 * 解释型语言无字节码级 CFG，统一走 AST 级（对齐 Java 链路 Soot 缺失时的 AST 降级路径）。
 */
@Component
public class PythonCodeParser implements CodeParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonCodeParser.class);

    /** SQL 格式化拼接：f-string 同时含 SQL 关键字与插值占位 */
    private static final Pattern SQL_FSTRING_PATTERN =
            Pattern.compile("(?i)\\b(select|insert|update|delete)\\b[\\s\\S]*\\{[\\s\\S]*\\}");

    @Override
    public String language() {
        return "python";
    }

    @Override
    public List<String> sourceExtensions() {
        return List.of(".py");
    }

    @Override
    public List<File> collectSourceFiles(File projectDir) {
        List<File> files = new ArrayList<>();
        collectPythonFiles(projectDir, files);
        return files;
    }

    @Override
    public ProjectParseResult parseProject(String projectPath, CompileResult compileResult) {
        // compileResult 对解释型语言无意义（CompileResult.none()），统一 AST 级 CFG
        ProjectParseResult result = new ProjectParseResult();
        File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            return result;
        }
        List<File> pyFiles = collectSourceFiles(projectDir);
        for (File file : pyFiles) {
            try {
                result.codeUnits.addAll(parseFile(file, projectPath));
            } catch (Exception e) {
                String rel = relativePath(file, projectPath);
                result.failures.add(ParseFailure.fromException(rel, e, file.length()));
                LOGGER.warn("T11: Python 源文件解析失败已隔离（跳过该文件）: {} - {}", rel, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public Map<String, String> scanContentHashes(String projectPath) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        File dir = new File(projectPath);
        if (!dir.exists()) {
            return out;
        }
        for (File f : collectSourceFiles(dir)) {
            try {
                out.put(relativePath(f, projectPath),
                        ContentHashUtil.sha256(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                LOGGER.warn("T11: 计算内容哈希失败，跳过: {}", f.getName());
            }
        }
        return out;
    }

    @Override
    public List<CodeUnit> parseFileForAnalysis(File file, String projectPath, CompileResult compileResult) {
        try {
            return parseFile(file, projectPath);
        } catch (Exception e) {
            LOGGER.warn("T11: Python 单文件解析失败: {} - {}", file.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 解析实现 ====================

    private List<CodeUnit> parseFile(File file, String projectPath) throws Exception {
        List<CodeUnit> codeUnits = new ArrayList<>();
        byte[] sourceBytes = Files.readAllBytes(file.toPath());
        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        String fileHash = ContentHashUtil.sha256(source);
        String relativePath = relativePath(file, projectPath);

        TSNode root = parseTree(source);
        if (root.isNull()) {
            return codeUnits;
        }
        for (FuncInfo func : collectFunctions(root, sourceBytes)) {
            codeUnits.add(buildCodeUnit(func, relativePath, fileHash));
        }
        return codeUnits;
    }

    private TSNode parseTree(String source) {
        TSParser parser = new TSParser();
        if (!parser.setLanguage(new TreeSitterPython())) {
            throw new IllegalStateException("tree-sitter Python 语法加载失败");
        }
        TSTree tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8);
        return tree.getRootNode();
    }

    /** 递归收集函数定义（模块级 + 类方法；跳过函数内嵌套定义） */
    private List<FuncInfo> collectFunctions(TSNode root, byte[] sourceBytes) {
        List<FuncInfo> out = new ArrayList<>();
        collectFunctionsWalk(root, "", sourceBytes, out, 0);
        return out;
    }

    private void collectFunctionsWalk(TSNode node, String className, byte[] sourceBytes,
                                      List<FuncInfo> out, int depth) {
        if (depth > 4) {
            return;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            String type = child.getType();
            switch (type) {
                case "function_definition":
                    out.add(new FuncInfo(child, className, text(child.getChildByFieldName("name"), sourceBytes), sourceBytes));
                    break; // 函数体内嵌套 def 不再单独成单元
                case "decorated_definition":
                    // 装饰器包装：取内部 function_definition/class_definition，装饰器归属该单元
                    TSNode inner = firstOf(child, "function_definition", "class_definition");
                    if (!inner.isNull()) {
                        if ("function_definition".equals(inner.getType())) {
                            out.add(new FuncInfo(child, className, text(inner.getChildByFieldName("name"), sourceBytes), sourceBytes));
                        } else {
                            collectFunctionsWalk(inner, text(inner.getChildByFieldName("name"), sourceBytes), sourceBytes, out, depth + 1);
                        }
                    }
                    break;
                case "class_definition":
                    collectFunctionsWalk(child.getChildByFieldName("body"),
                            text(child.getChildByFieldName("name"), sourceBytes), sourceBytes, out, depth + 1);
                    break;
                default:
                    collectFunctionsWalk(child, className, sourceBytes, out, depth + 1);
            }
        }
    }

    private CodeUnit buildCodeUnit(FuncInfo func, String relativePath, String fileHash) {
        CodeUnit unit = new CodeUnit();
        unit.setCodeId(generateCodeId(relativePath, func.className, func.name));
        unit.setFilePath(relativePath);
        unit.setClassName(func.className);
        unit.setMethodName(func.name);
        unit.setStartLine(func.node.getStartPoint().getRow() + 1);
        unit.setEndLine(func.node.getEndPoint().getRow() + 1);
        unit.setCodeContent(func.text());
        unit.setLogicDescription(extractDocstring(func));
        unit.setCfgData(PythonCfgBuilderUtil.build(func.node, func.sourceBytes));
        unit.setSemanticVector(SemanticVectorUtil.termsOnly(String.join(" ", extractTerms(func))));
        unit.setCyclomaticComplexity(calcCyclomaticComplexity(func));
        unit.setContentHash(fileHash);
        return unit;
    }

    /** docstring 优先，其次签名摘要 */
    private String extractDocstring(FuncInfo func) {
        TSNode body = func.node.getChildByFieldName("body");
        if (!body.isNull() && body.getNamedChildCount() > 0) {
            TSNode first = body.getNamedChild(0);
            if ("expression_statement".equals(first.getType()) && first.getNamedChildCount() > 0
                    && "string".equals(first.getNamedChild(0).getType())) {
                String doc = func.text(first.getNamedChild(0));
                String cleaned = doc.replaceAll("^[rRbBfFuU]*[\\\"']{1,3}", "").replaceAll("[\\\"']{1,3}$", "").trim();
                if (!cleaned.isEmpty()) {
                    return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
                }
            }
        }
        return "Python 函数 " + func.name + "，共 " + (func.node.getEndPoint().getRow() - func.node.getStartPoint().getRow() + 1) + " 行";
    }

    // ==================== 语义特征词 / 圈复杂度 ====================

    /** 语义特征词：控制流形态 + 调用名 snake 切分（对齐 SemanticVectorUtil 的 Java 口径） */
    private List<String> extractTerms(FuncInfo func) {
        Set<String> terms = new LinkedHashSet<>();
        walkTerms(func.node, func.name, terms, func.sourceBytes);
        return new ArrayList<>(terms);
    }

    private void walkTerms(TSNode node, String selfName, Set<String> terms, byte[] sourceBytes) {
        switch (node.getType()) {
            case "for_statement":
            case "while_statement":
            case "async_for_statement":
            case "async_while_statement":
                terms.add("loop");
                break;
            case "if_statement":
                terms.add("branch");
                break;
            case "match_statement":
                terms.add("switch");
                break;
            case "try_statement":
                terms.add("try");
                break;
            case "raise_statement":
                terms.add("throw");
                break;
            case "return_statement":
                terms.add("return");
                break;
            case "call": {
                TSNode fn = node.getChildByFieldName("function");
                if (!fn.isNull()) {
                    String callName = resolveCallName(fn, sourceBytes);
                    if (callName != null) {
                        if (callName.equals(selfName)) {
                            terms.add("recursion");
                        }
                        for (String w : callName.split("_")) {
                            if (!w.isBlank()) {
                                terms.add(w.toLowerCase());
                            }
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkTerms(node.getNamedChild(i), selfName, terms, sourceBytes);
        }
    }

    /** call 的 function 字段：identifier 直接取；attribute 取 . 后的方法名（按字节切片取文本，避免 toString 实现差异） */
    private String resolveCallName(TSNode fn, byte[] sourceBytes) {
        String t = fn.getType();
        if ("identifier".equals(t)) {
            return nodeText(fn, sourceBytes);
        }
        if ("attribute".equals(t)) {
            TSNode attr = fn.getChildByFieldName("attribute");
            return attr.isNull() ? null : nodeText(attr, sourceBytes);
        }
        return null;
    }

    /** 圈复杂度（McCabe，基值 1）：if/elif/for/while/except/match-case/and/or 各 +1 */
    private int calcCyclomaticComplexity(FuncInfo func) {
        int[] decisions = {0};
        walkComplexity(func.node, decisions);
        return 1 + decisions[0];
    }

    private void walkComplexity(TSNode node, int[] decisions) {
        switch (node.getType()) {
            case "if_statement":
            case "elif_clause":
            case "for_statement":
            case "while_statement":
            case "except_clause":
            case "match_case":
            case "boolean_operator": // and/or
                decisions[0]++;
                break;
            default:
                break;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkComplexity(node.getNamedChild(i), decisions);
        }
    }

    // ==================== 基础缺陷检测 ====================

    @Override
    public List<CodeDefect> detectBasicDefects(File file, String projectPath, Long projectId, Long taskId) {
        List<CodeDefect> defects = new ArrayList<>();
        try {
            byte[] sourceBytes = Files.readAllBytes(file.toPath());
            String relativePath = relativePath(file, projectPath);
            TSNode root = parseTree(new String(sourceBytes, StandardCharsets.UTF_8));
            for (FuncInfo func : collectFunctions(root, sourceBytes)) {
                String fullText = func.text();
                boolean hasClose = fullText.contains(".close()");
                walkDefects(func, hasClose, relativePath, projectId, taskId, defects);
            }
        } catch (Exception e) {
            LOGGER.warn("T11: Python 缺陷检测失败[{}]: {}", file.getName(), e.getMessage());
        }
        return defects;
    }

    private void walkDefects(FuncInfo func, boolean hasClose, String relativePath,
                             Long projectId, Long taskId, List<CodeDefect> defects) {
        walkSubtree(func.node, node -> {
            switch (node.getType()) {
                case "except_clause": {
                    // 裸 except：吞掉所有异常（含 KeyboardInterrupt/SystemExit）
                    String head = func.text(node).split("\n")[0].trim();
                    if (head.startsWith("except:") || head.startsWith("except :")) {
                        defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                                node.getStartPoint().getRow() + 1, "空捕获异常", "medium",
                                "检测到裸 except: 子句，会吞掉包括程序错误在内的所有异常，故障被隐藏且难以排查",
                                "捕获具体异常类型（如 except ValueError:），并记录日志或重新抛出"));
                    }
                    break;
                }
                case "while_statement": {
                    // while True 无 break/return/raise -> 潜在死循环
                    TSNode cond = node.getChildByFieldName("condition");
                    if (!cond.isNull() && "True".equals(func.text(cond).trim())) {
                        TSNode body = node.getChildByFieldName("body");
                        if (!hasTerminal(body)) {
                            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                                    node.getStartPoint().getRow() + 1, "逻辑死循环", "medium",
                                    "检测到无 break/return/raise 的 while True 循环，存在死循环风险",
                                    "在循环体内添加退出条件或 break/return 语句"));
                        }
                    }
                    break;
                }
                case "default_parameter": {
                    // 可变默认参数：默认值为 list/dict/set 字面量，跨调用共享同一对象
                    TSNode value = node.getChildByFieldName("value");
                    if (!value.isNull()) {
                        String vt = value.getType();
                        if ("list".equals(vt) || "dictionary".equals(vt) || "set".equals(vt)) {
                            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                                    node.getStartPoint().getRow() + 1, "可变默认参数", "medium",
                                    "检测到可变对象（list/dict/set）作为参数默认值，多次调用共享同一对象导致状态串扰",
                                    "默认值改为 None，函数体内判空后再创建新对象"));
                        }
                    }
                    break;
                }
                case "call": {
                    TSNode fn = node.getChildByFieldName("function");
                    if (!fn.isNull() && "open".equals(func.text(fn))) {
                        // open() 未在 with 语句中使用且函数内无 close() -> 资源未释放
                        if (!hasWithAncestor(node, func.node) && !hasClose) {
                            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                                    node.getStartPoint().getRow() + 1, "资源未释放", "medium",
                                    "检测到 open() 调用未在 with 语句中使用且方法内未见 close()，文件句柄可能泄漏",
                                    "使用 with open(...) as f: 语句自动管理资源释放"));
                        }
                    }
                    break;
                }
                case "string": {
                    // f-string SQL 拼接（SQL 关键字 + 插值占位）
                    String s = func.text(node);
                    if (s.length() > 2 && (s.startsWith("f\"") || s.startsWith("f'") || s.startsWith("F\"") || s.startsWith("F'"))
                            && SQL_FSTRING_PATTERN.matcher(s).find()) {
                        defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                                node.getStartPoint().getRow() + 1, "SQL注入", "high",
                                "检测到 SQL 语句使用 f-string 格式化拼接变量，存在 SQL 注入风险",
                                "使用参数化查询（cursor.execute(sql, params)）替代字符串格式化拼接"));
                    }
                    break;
                }
                default:
                    break;
            }
        });
    }

    private interface NodeVisitor {
        void visit(TSNode node);
    }

    private void walkSubtree(TSNode node, NodeVisitor visitor) {
        visitor.visit(node);
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkSubtree(node.getNamedChild(i), visitor);
        }
    }

    /** 循环体内是否存在 break/return/raise（含嵌套 if，不含嵌套函数/内层循环） */
    private boolean hasTerminal(TSNode body) {
        if (body.isNull()) {
            return false;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode child = body.getNamedChild(i);
            if (hasTerminalWalk(child, 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTerminalWalk(TSNode node, int depth) {
        String t = node.getType();
        if ("break_statement".equals(t) || "return_statement".equals(t) || "raise_statement".equals(t)) {
            return true;
        }
        if ("function_definition".equals(t)) {
            return false; // 嵌套函数的 return 不算外层退出
        }
        if (depth > 12) {
            return false;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            if (hasTerminalWalk(node.getNamedChild(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** open() 调用到所属函数之间是否存在 with_statement */
    private boolean hasWithAncestor(TSNode callNode, TSNode funcNode) {
        TSNode cur = callNode.getParent();
        while (!cur.isNull() && !TSNode.eq(cur, funcNode)) {
            if ("with_statement".equals(cur.getType())) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private CodeDefect createDefect(Long projectId, Long taskId, String filePath, String className,
                                    String methodName, int line, String type, String severity,
                                    String desc, String suggestion) {
        CodeDefect defect = new CodeDefect();
        defect.setProjectId(projectId);
        defect.setTaskId(taskId);
        defect.setFilePath(filePath);
        defect.setClassName(className);
        defect.setMethodName(methodName);
        defect.setLineNumber(line);
        defect.setDefectType(type);
        defect.setSeverity(severity);
        defect.setDescription(desc);
        defect.setRepairSuggestion(suggestion);
        return defect;
    }

    // ==================== 工具方法 ====================

    private String generateCodeId(String filePath, String className, String methodName) {
        return "CODE-" + Math.abs((filePath + "#" + className + "#" + methodName).hashCode());
    }

    private String relativePath(File file, String projectPath) {
        String abs = file.getAbsolutePath().replace("\\", "/");
        if (projectPath != null && !projectPath.isEmpty()) {
            String base = projectPath.replace("\\", "/");
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            if (abs.startsWith(base)) {
                return abs.substring(base.length());
            }
        }
        return file.getName();
    }

    private String text(TSNode node, byte[] sourceBytes) {
        return nodeText(node, sourceBytes);
    }

    private static String nodeText(TSNode node, byte[] sourceBytes) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (node.isNull() || start < 0 || end <= start || end > sourceBytes.length) {
            return "";
        }
        return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
    }

    private TSNode firstOf(TSNode node, String... types) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            for (String t : types) {
                if (t.equals(child.getType())) {
                    return child;
                }
            }
        }
        TSNode nullNode = new TSNode();
        return nullNode;
    }

    private void collectPythonFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (name.startsWith(".") || name.equals("__pycache__") || name.equals("venv")
                        || name.equals(".venv") || name.equals("env") || name.equals("node_modules")
                        || name.equals("target") || name.equals("build")) {
                    continue;
                }
                collectPythonFiles(f, files);
            } else if (f.getName().endsWith(".py")) {
                files.add(f);
            }
        }
    }

    /** 函数定义信息（node 允许为 decorated_definition 包装，文本/行号取包装节点以包含装饰器） */
    private static class FuncInfo {
        final TSNode node;
        final String className;
        final String name;
        final byte[] sourceBytes;

        FuncInfo(TSNode node, String className, String name, byte[] sourceBytes) {
            this.node = node;
            this.className = className;
            this.name = name;
            this.sourceBytes = sourceBytes;
        }

        String text() {
            return nodeText(node, sourceBytes);
        }

        String text(TSNode n) {
            return nodeText(n, sourceBytes);
        }
    }
}
