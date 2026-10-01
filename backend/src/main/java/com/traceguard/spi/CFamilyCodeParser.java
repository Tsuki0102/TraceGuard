package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.service.ParseFailure;
import com.traceguard.util.CFamilyCfgBuilderUtil;
import com.traceguard.util.ContentHashUtil;
import com.traceguard.util.SemanticVectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T11 多语言扩展：C/C++ 代码解析器公共基类（tree-sitter），language=c / cpp 两个注册名共用内核。
 * 与 Java/Python 链路同构产出：方法级 {@link CodeUnit}（AST 级 CFG / 语义特征词 / 圈复杂度 / 内容哈希）
 * + 文件级 C/C++ 特色基础缺陷检测（malloc 内存泄漏 / fopen 资源未释放 / 危险函数 / malloc 未判空 / 裸 catch(...)）。
 * 函数名需穿透 declarator 链（function_declarator → pointer/array declarator → identifier）。
 */
public abstract class CFamilyCodeParser implements CodeParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(CFamilyCodeParser.class);

    /** 子类提供：语言注册名（c / cpp） */
    protected abstract String languageId();

    /** 子类提供：tree-sitter 语法（TreeSitterC / TreeSitterCpp） */
    protected abstract TSLanguage tsLanguage();

    /** 子类提供：源文件扩展名集合（小写含点） */
    protected abstract List<String> extensions();

    @Override
    public final String language() {
        return languageId();
    }

    @Override
    public final List<String> sourceExtensions() {
        return extensions();
    }

    @Override
    public final List<File> collectSourceFiles(File projectDir) {
        List<String> exts = extensions();
        List<File> files = new ArrayList<>();
        collectSourceFilesWalk(projectDir, exts, files);
        return files;
    }

    private void collectSourceFilesWalk(File dir, List<String> exts, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (name.startsWith(".") || name.equals("build") || name.equals("target")
                        || name.equals("obj") || name.equals("bin") || name.equals("out")
                        || name.equals("node_modules") || name.equals("cmake-build-debug")) {
                    continue;
                }
                collectSourceFilesWalk(f, exts, files);
            } else {
                String lower = f.getName().toLowerCase();
                for (String ext : exts) {
                    if (lower.endsWith(ext)) {
                        files.add(f);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public final ProjectParseResult parseProject(String projectPath, CompileResult compileResult) {
        ProjectParseResult result = new ProjectParseResult();
        File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            return result;
        }
        for (File file : collectSourceFiles(projectDir)) {
            try {
                result.codeUnits.addAll(parseFile(file, projectPath));
            } catch (Exception e) {
                String rel = relativePath(file, projectPath);
                result.failures.add(ParseFailure.fromException(rel, e, file.length()));
                LOGGER.warn("T11: C/C++ 源文件解析失败已隔离（跳过该文件）: {} - {}", rel, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public final Map<String, String> scanContentHashes(String projectPath) {
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
    public final List<CodeUnit> parseFileForAnalysis(File file, String projectPath, CompileResult compileResult) {
        try {
            return parseFile(file, projectPath);
        } catch (Exception e) {
            LOGGER.warn("T11: C/C++ 单文件解析失败: {} - {}", file.getName(), e.getMessage());
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
        if (!parser.setLanguage(tsLanguage())) {
            throw new IllegalStateException("tree-sitter " + languageId() + " 语法加载失败");
        }
        TSTree tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8);
        return tree.getRootNode();
    }

    /** 递归收集函数定义：顶层函数 + C++ 类/结构体方法；穿透 template/namespace；跳过函数体内嵌套定义 */
    private List<FuncInfo> collectFunctions(TSNode root, byte[] sourceBytes) {
        List<FuncInfo> out = new ArrayList<>();
        collectFunctionsWalk(root, "", sourceBytes, out, 0);
        return out;
    }

    private void collectFunctionsWalk(TSNode node, String className, byte[] sourceBytes,
                                      List<FuncInfo> out, int depth) {
        if (depth > 6) {
            return;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            String type = child.getType();
            if (type.startsWith("preproc") || "comment".equals(type)) {
                continue;
            }
            switch (type) {
                case "function_definition": {
                    TSNode declarator = child.getChildByFieldName("declarator");
                    String name = resolveFunctionName(declarator, sourceBytes);
                    String cls = className;
                    // C++ 顶层定义 Logger::method(...)：qualified_identifier 首段作为类名（演示口径，
                    // 命名空间前缀与类名无法仅凭 AST 区分，工程内无 namespace 场景）
                    if ("function_declarator".equals(declarator.getType())) {
                        TSNode inner = declarator.getChildByFieldName("declarator");
                        if ("qualified_identifier".equals(inner.getType()) && inner.getNamedChildCount() >= 2) {
                            cls = nodeText(inner.getNamedChild(0), sourceBytes);
                        }
                    }
                    out.add(new FuncInfo(child, cls, name, sourceBytes));
                    break; // 函数体内不嵌套定义（GNU 扩展忽略）
                }
                case "template_declaration":
                case "namespace_definition":
                case "linkage_specification":
                    collectFunctionsWalk(child, className, sourceBytes, out, depth + 1);
                    break;
                case "class_specifier":
                case "struct_specifier": {
                    TSNode body = child.getChildByFieldName("body");
                    if (body.isNull()) {
                        break; // 前向声明
                    }
                    TSNode name = child.getChildByFieldName("name");
                    String clsName = name.isNull() ? className : nameText(name, sourceBytes);
                    collectFunctionsWalk(body, clsName.isEmpty() ? className : clsName, sourceBytes, out, depth + 1);
                    break;
                }
                default:
                    collectFunctionsWalk(child, className, sourceBytes, out, depth + 1);
            }
        }
    }

    /** 函数名穿透 declarator 链：function_declarator → pointer/reference/array declarator → identifier */
    private String resolveFunctionName(TSNode declarator, byte[] sourceBytes) {
        if (declarator.isNull()) {
            return "<anonymous>";
        }
        String type = declarator.getType();
        if ("identifier".equals(type) || "field_identifier".equals(type)) {
            return nodeText(declarator, sourceBytes);
        }
        if ("qualified_identifier".equals(type)) {
            // C++ 类方法 Logger::open_file：取最后一个子节点（方法名）
            int n = declarator.getNamedChildCount();
            if (n > 0) {
                return nodeText(declarator.getNamedChild(n - 1), sourceBytes);
            }
            return nodeText(declarator, sourceBytes);
        }
        if ("function_declarator".equals(type)) {
            return resolveFunctionName(declarator.getChildByFieldName("declarator"), sourceBytes);
        }
        if (type.endsWith("declarator")) {
            // pointer_declarator / reference_declarator / array_declarator / parenthesized_declarator：递归内层
            for (int i = 0; i < declarator.getNamedChildCount(); i++) {
                TSNode inner = declarator.getNamedChild(i);
                if (inner.getType().endsWith("declarator") || inner.getType().endsWith("identifier")) {
                    String resolved = resolveFunctionName(inner, sourceBytes);
                    if (!resolved.startsWith("<")) {
                        return resolved;
                    }
                }
            }
        }
        // 兜底（析构/运算符重载等特殊名）：取文本
        String text = nodeText(declarator, sourceBytes);
        return text.isEmpty() ? "<anonymous>" : (text.length() > 40 ? text.substring(0, 40) : text);
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
        unit.setLogicDescription(extractDocComment(func));
        unit.setCfgData(CFamilyCfgBuilderUtil.build(func.node, func.sourceBytes));
        unit.setSemanticVector(SemanticVectorUtil.termsOnly(String.join(" ", extractTerms(func))));
        unit.setCyclomaticComplexity(calcCyclomaticComplexity(func));
        unit.setContentHash(fileHash);
        return unit;
    }

    /** 优先取函数前紧邻的 doxygen 风格注释，否则签名摘要 */
    private String extractDocComment(FuncInfo func) {
        TSNode prev = func.node.getPrevNamedSibling();
        if (!prev.isNull() && "comment".equals(prev.getType())) {
            String raw = func.text(prev);
            if (raw.contains("\n") || raw.startsWith("/*") || raw.startsWith("///")) {
                String cleaned = raw.replaceAll("/\\*+|\\*+/", "")
                        .replaceAll("(?m)^\\s*[*#]?\\s?", "")
                        .replace("///", "").trim();
                if (!cleaned.isEmpty()) {
                    return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
                }
            }
        }
        String kind = func.className.isEmpty() ? "函数" : "方法";
        return (languageId().equals("cpp") ? "C++ " : "C ") + kind + " " + func.name
                + "，共 " + (func.node.getEndPoint().getRow() - func.node.getStartPoint().getRow() + 1) + " 行";
    }

    // ==================== 语义特征词 / 圈复杂度 ====================

    /** 语义特征词：控制流形态 + 调用名（snake 与 camel 双切分，C/C++ 两种命名习惯都覆盖） */
    private List<String> extractTerms(FuncInfo func) {
        Set<String> terms = new LinkedHashSet<>();
        walkTerms(func.node, func.name, terms, func.sourceBytes);
        return new ArrayList<>(terms);
    }

    private void walkTerms(TSNode node, String selfName, Set<String> terms, byte[] sourceBytes) {
        switch (node.getType()) {
            case "for_statement":
            case "while_statement":
            case "do_statement":
                terms.add("loop");
                break;
            case "if_statement":
                terms.add("branch");
                break;
            case "switch_statement":
                terms.add("switch");
                break;
            case "try_statement":
                terms.add("try");
                break;
            case "throw_statement":
                terms.add("throw");
                break;
            case "return_statement":
                terms.add("return");
                break;
            case "call_expression": {
                TSNode fn = node.getChildByFieldName("function");
                if (!fn.isNull()) {
                    String callName = resolveCallName(fn, sourceBytes);
                    if (callName != null && !callName.isEmpty()) {
                        if (callName.equals(selfName)) {
                            terms.add("recursion");
                        }
                        for (String w : splitIdent(callName)) {
                            terms.add(w);
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

    /** call 的 function 字段：identifier 直接取；field_expression 取 method 字段（C++ obj.method()） */
    private String resolveCallName(TSNode fn, byte[] sourceBytes) {
        String t = fn.getType();
        if ("identifier".equals(t)) {
            return nodeText(fn, sourceBytes);
        }
        if ("field_expression".equals(t)) {
            TSNode field = fn.getChildByFieldName("field");
            return field.isNull() ? null : nodeText(field, sourceBytes);
        }
        return null;
    }

    /** snake_case 与 camelCase 双切分（save_order / saveOrder -> save, order） */
    private List<String> splitIdent(String name) {
        List<String> out = new ArrayList<>();
        for (String part : name.split("_+")) {
            if (part.isEmpty()) {
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isUpperCase(c) && cur.length() > 0) {
                    out.add(cur.toString().toLowerCase());
                    cur.setLength(0);
                }
                cur.append(Character.toLowerCase(c));
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
        }
        return out;
    }

    /** 圈复杂度（McCabe，基值 1）：if/for/while/do/case/catch/&&/|| 各 +1 */
    private int calcCyclomaticComplexity(FuncInfo func) {
        int[] decisions = {0};
        walkComplexity(func.node, func.sourceBytes, decisions);
        return 1 + decisions[0];
    }

    private void walkComplexity(TSNode node, byte[] sourceBytes, int[] decisions) {
        switch (node.getType()) {
            case "if_statement":
            case "for_statement":
            case "while_statement":
            case "do_statement":
            case "case_statement":
            case "catch_clause":
                decisions[0]++;
                break;
            case "binary_expression": {
                String text = nodeText(node, sourceBytes);
                if (text.contains("&&") || text.contains("||")) {
                    decisions[0]++;
                }
                break;
            }
            default:
                break;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkComplexity(node.getNamedChild(i), sourceBytes, decisions);
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
                checkDefects(func, relativePath, projectId, taskId, defects);
            }
        } catch (Exception e) {
            LOGGER.warn("T11: C/C++ 缺陷检测失败[{}]: {}", file.getName(), e.getMessage());
        }
        return defects;
    }

    private void checkDefects(FuncInfo func, String relativePath, Long projectId, Long taskId,
                              List<CodeDefect> defects) {
        String code = func.text();
        // 堆分配调用检测（malloc/calloc/realloc）
        boolean hasAlloc = containsCall(func, code, "malloc") || containsCall(func, code, "calloc")
                || containsCall(func, code, "realloc");
        // 1) 内存泄漏：malloc/calloc/realloc 后函数内未见 free(
        if (hasAlloc && !code.contains("free(")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstCallLine(func, "malloc") > 0 ? firstCallLine(func, "malloc") : func.node.getStartPoint().getRow() + 1,
                    "内存泄漏", "high",
                    "检测到 malloc/calloc/realloc 堆分配但方法内未见 free() 释放，重复调用将持续泄漏内存",
                    "分配与释放配对管理：使用后调用 free(ptr)，或封装 RAII 智能指针/内存池"));
        }
        // 2) 空指针风险：malloc 返回值未见判空（NULL/nullptr/assert 均视为已防护）
        if (hasAlloc && !code.contains("NULL") && !code.contains("nullptr") && !code.contains("assert")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstCallLine(func, "malloc") > 0 ? firstCallLine(func, "malloc") : func.node.getStartPoint().getRow() + 1,
                    "空指针风险", "medium",
                    "检测到堆分配返回值未经 NULL 判空直接使用，系统内存不足时将解引用空指针导致崩溃",
                    "分配后立即判空：if (ptr == NULL) { return error; }，或使用断言防护"));
        }
        // 3) 资源未释放：fopen 无 fclose
        if (containsCall(func, code, "fopen") && !code.contains("fclose(")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstCallLine(func, "fopen") > 0 ? firstCallLine(func, "fopen") : func.node.getStartPoint().getRow() + 1,
                    "资源未释放", "medium",
                    "检测到 fopen() 打开文件但方法内未见 fclose()，文件句柄泄漏",
                    "使用后调用 fclose(fp)，或封装 RAII 文件守卫对象自动释放"));
        }
        // 4) 危险函数：gets/strcpy/strcat/sprintf（CWE 权威：无边界检查）
        for (String danger : new String[]{"gets(", "strcpy(", "strcat(", "sprintf("}) {
            int idx = code.indexOf(danger);
            if (idx >= 0) {
                defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                        lineOfOffset(func, idx), "危险函数调用", "high",
                        "检测到无边界检查的危险函数 " + danger.substring(0, danger.length() - 1)
                                + "()，目标缓冲区溢出可被利用执行任意代码（CWE-120）",
                        "改用带边界检查的安全版本：fgets/strncpy/strncat/snprintf"));
                break; // 每函数报首个即可
            }
        }
        // 5) 裸 catch(...)：吞掉所有异常
        walkSubtree(func.node, node -> {
            if ("catch_clause".equals(node.getType())) {
                TSNode params = node.getChildByFieldName("parameters");
                if (!params.isNull() && func.text(params).contains("...")) {
                    defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                            node.getStartPoint().getRow() + 1, "异常吞没", "medium",
                            "检测到 catch (...) 捕获所有异常且类型信息丢失，故障被静默隐藏难以排查",
                            "捕获具体异常类型并记录日志，或重新抛出让上层处理"));
                }
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

    /** 函数内是否存在对目标函数的调用（按 call_expression 节点 function 名精确匹配） */
    private boolean containsCall(FuncInfo func, String code, String callee) {
        boolean[] found = {false};
        walkSubtree(func.node, node -> {
            if (found[0]) {
                return;
            }
            if ("call_expression".equals(node.getType())) {
                TSNode fn = node.getChildByFieldName("function");
                if (!fn.isNull() && callee.equals(func.text(fn))) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    /** 首个目标调用所在行号（无则 -1） */
    private int firstCallLine(FuncInfo func, String callee) {
        int[] line = {-1};
        walkSubtree(func.node, node -> {
            if (line[0] > 0 || !"call_expression".equals(node.getType())) {
                return;
            }
            TSNode fn = node.getChildByFieldName("function");
            if (!fn.isNull() && callee.equals(func.text(fn))) {
                line[0] = node.getStartPoint().getRow() + 1;
            }
        });
        return line[0];
    }

    /** 函数内相对偏移 -> 源文件行号 */
    private int lineOfOffset(FuncInfo func, int offsetInCode) {
        int codeStartByte = func.codeStartByte();
        int target = codeStartByte + offsetInCode;
        byte[] bytes = func.sourceBytes;
        int line = 1;
        for (int i = 0; i < target && i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                line++;
            }
        }
        return line;
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

    private String nodeText(TSNode node, byte[] sourceBytes) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (node.isNull() || start < 0 || end <= start || end > sourceBytes.length) {
            return "";
        }
        return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
    }

    private String nameText(TSNode node, byte[] sourceBytes) {
        // 类名可能是 type_identifier（C++）或 type_definition 下的 identifier
        if ("type_identifier".equals(node.getType()) || "identifier".equals(node.getType())) {
            return nodeText(node, sourceBytes);
        }
        String direct = nodeText(node, sourceBytes);
        return direct.length() > 40 ? direct.substring(0, 40) : direct;
    }

    /** 函数定义信息（codeStartByte 供函数内偏移换算行号） */
    protected static class FuncInfo {
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
            return text(node);
        }

        String text(TSNode n) {
            int start = n.getStartByte();
            int end = n.getEndByte();
            if (n.isNull() || start < 0 || end <= start || end > sourceBytes.length) {
                return "";
            }
            return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
        }

        int codeStartByte() {
            return node.getStartByte();
        }
    }
}
