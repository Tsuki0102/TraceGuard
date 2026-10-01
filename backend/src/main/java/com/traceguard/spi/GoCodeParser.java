package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.service.ParseFailure;
import com.traceguard.util.ContentHashUtil;
import com.traceguard.util.GoCfgBuilderUtil;
import com.traceguard.util.SemanticVectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

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
 * T11 多语言扩展：Go 代码解析器（language=go），tree-sitter-go 语法包。
 * 与 Java/Python/C 系同构产出：函数级 {@link CodeUnit}（AST 级 CFG / 语义特征词 / 圈复杂度 / 内容哈希）
 * + Go 特色基础缺陷检测（错误未处理 / 错误被 _ 丢弃 / Open 资源未释放 / Lock 锁未释放 / panic 滥用）。
 * 函数提取：function_declaration（顶层）+ method_declaration（receiver 归属 struct 类型名）。
 */
@Component
public class GoCodeParser implements CodeParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoCodeParser.class);

    @Override
    public String language() {
        return "go";
    }

    @Override
    public List<String> sourceExtensions() {
        return List.of(".go");
    }

    @Override
    public List<File> collectSourceFiles(File projectDir) {
        List<File> files = new ArrayList<>();
        collectGoFiles(projectDir, files);
        return files;
    }

    @Override
    public ProjectParseResult parseProject(String projectPath, CompileResult compileResult) {
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
                LOGGER.warn("T11: Go 源文件解析失败已隔离（跳过该文件）: {} - {}", rel, e.getMessage());
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
            LOGGER.warn("T11: Go 单文件解析失败: {} - {}", file.getName(), e.getMessage());
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
        if (!parser.setLanguage(new TreeSitterGo())) {
            throw new IllegalStateException("tree-sitter Go 语法加载失败");
        }
        TSTree tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8);
        return tree.getRootNode();
    }

    /** 收集函数：function_declaration（顶层）+ method_declaration（receiver -> className） */
    private List<FuncInfo> collectFunctions(TSNode root, byte[] sourceBytes) {
        List<FuncInfo> out = new ArrayList<>();
        collectFunctionsWalk(root, sourceBytes, out, 0);
        return out;
    }

    private void collectFunctionsWalk(TSNode node, byte[] sourceBytes, List<FuncInfo> out, int depth) {
        if (depth > 4) {
            return;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            String type = child.getType();
            switch (type) {
                case "function_declaration":
                    out.add(new FuncInfo(child, "",
                            text(child.getChildByFieldName("name"), sourceBytes), sourceBytes));
                    break; // Go 无嵌套函数声明
                case "method_declaration": {
                    TSNode receiver = child.getChildByFieldName("receiver");
                    String cls = resolveReceiverClass(receiver, sourceBytes);
                    out.add(new FuncInfo(child, cls,
                            text(child.getChildByFieldName("name"), sourceBytes), sourceBytes));
                    break;
                }
                default:
                    collectFunctionsWalk(child, sourceBytes, out, depth + 1);
            }
        }
    }

    /** receiver (r *TaskRunner) -> TaskRunner（递归找 type_identifier） */
    private String resolveReceiverClass(TSNode receiver, byte[] sourceBytes) {
        if (receiver.isNull()) {
            return "";
        }
        String[] found = {""};
        walkSubtree(receiver, node -> {
            if (found[0].isEmpty() && "type_identifier".equals(node.getType())) {
                found[0] = nodeText(node, sourceBytes);
            }
        });
        return found[0];
    }

    private void walkSubtree(TSNode node, NodeVisitor visitor) {
        visitor.visit(node);
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkSubtree(node.getNamedChild(i), visitor);
        }
    }

    private interface NodeVisitor {
        void visit(TSNode node);
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
        unit.setCfgData(GoCfgBuilderUtil.build(func.node, func.sourceBytes));
        unit.setSemanticVector(SemanticVectorUtil.termsOnly(String.join(" ", extractTerms(func))));
        unit.setCyclomaticComplexity(calcCyclomaticComplexity(func));
        unit.setContentHash(fileHash);
        return unit;
    }

    /** 优先取函数前紧邻的注释（godoc 风格），否则签名摘要 */
    private String extractDocComment(FuncInfo func) {
        TSNode prev = func.node.getPrevNamedSibling();
        if (!prev.isNull() && "comment".equals(prev.getType())) {
            String cleaned = func.text(prev).replace("//", "").trim();
            if (!cleaned.isEmpty()) {
                return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
            }
        }
        String kind = func.className.isEmpty() ? "函数" : "方法";
        return "Go " + kind + " " + func.name
                + "，共 " + (func.node.getEndPoint().getRow() - func.node.getStartPoint().getRow() + 1) + " 行";
    }

    // ==================== 语义特征词 / 圈复杂度 ====================

    /** 语义特征词：控制流形态 + goroutine/defer 特色词 + 调用名切分 */
    private List<String> extractTerms(FuncInfo func) {
        Set<String> terms = new LinkedHashSet<>();
        walkTerms(func.node, func.name, terms, func.sourceBytes);
        return new ArrayList<>(terms);
    }

    private void walkTerms(TSNode node, String selfName, Set<String> terms, byte[] sourceBytes) {
        switch (node.getType()) {
            case "for_statement":
                terms.add("loop");
                break;
            case "if_statement":
                terms.add("branch");
                break;
            case "switch_statement":
                terms.add("switch");
                break;
            case "go_statement":
                terms.add("goroutine");
                break;
            case "defer_statement":
                terms.add("defer");
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

    /** call 的 function 字段：identifier 直接取；selector_expression 取 field 字段（obj.Method()） */
    private String resolveCallName(TSNode fn, byte[] sourceBytes) {
        String t = fn.getType();
        if ("identifier".equals(t)) {
            return nodeText(fn, sourceBytes);
        }
        if ("selector_expression".equals(t)) {
            TSNode field = fn.getChildByFieldName("field");
            return field.isNull() ? null : nodeText(field, sourceBytes);
        }
        return null;
    }

    /** snake_case 与 camelCase 双切分 */
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

    /** 圈复杂度（McCabe，基值 1）：if/for/case/&&/|| 各 +1 */
    private int calcCyclomaticComplexity(FuncInfo func) {
        int[] decisions = {0};
        walkComplexity(func.node, func.sourceBytes, decisions);
        return 1 + decisions[0];
    }

    private void walkComplexity(TSNode node, byte[] sourceBytes, int[] decisions) {
        switch (node.getType()) {
            case "if_statement":
            case "for_statement":
            case "expression_case":
            case "default_case":
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
            LOGGER.warn("T11: Go 缺陷检测失败[{}]: {}", file.getName(), e.getMessage());
        }
        return defects;
    }

    private void checkDefects(FuncInfo func, String relativePath, Long projectId, Long taskId,
                              List<CodeDefect> defects) {
        String code = func.text();
        // 文本判定统一使用去注释版本：注释中的 "if err"/"Close"/"Unlock" 字样不应视为已防护
        String codeNoComment = stripGoComments(code);
        int line0 = func.node.getStartPoint().getRow() + 1;

        // 1) 错误未处理：err 接收（, err := / , err =）但函数内无 if err / err != nil 检查
        boolean errReceived = codeNoComment.contains(", err :=") || codeNoComment.contains(", err =")
                || codeNoComment.contains("err :=");
        if (errReceived && !codeNoComment.contains("err != nil") && !codeNoComment.contains("err == nil")
                && !codeNoComment.contains("if err")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name, line0,
                    "错误未处理", "high",
                    "检测到 err 返回值接收后未经 if err != nil 检查直接继续执行，失败路径被静默跳过",
                    "按 Go 惯例立即检查错误：if err != nil { return err }"));
        }
        // 2) 错误被 _ 丢弃：赋值/短声明 left 末位为 blank 且 right 为函数调用
        walkSubtree(func.node, node -> {
            String t = node.getType();
            if (!"short_var_declaration".equals(t) && !"assignment_statement".equals(t)
                    && !"assignment".equals(t)) {
                return;
            }
            TSNode left = node.getChildByFieldName("left");
            TSNode right = node.getChildByFieldName("right");
            if (left.isNull() || right.isNull() || left.getNamedChildCount() == 0
                    || right.getNamedChildCount() == 0) {
                return;
            }
            TSNode lastLeft = left.getNamedChild(left.getNamedChildCount() - 1);
            TSNode lastRight = right.getNamedChild(right.getNamedChildCount() - 1);
            if ("_".equals(nodeText(lastLeft, func.sourceBytes))
                    && "call_expression".equals(lastRight.getType())) {
                defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                        node.getStartPoint().getRow() + 1, "错误被忽略", "medium",
                        "检测到函数返回的 error 被空标识符 _ 丢弃，失败无法感知（Go 错误处理惯例违背）",
                        "接收并检查 error：res, err := ...; if err != nil { ... }"));
            }
        });
        // 3) 资源未释放：Open 调用后无 defer Close 也无 Close
        boolean hasOpen = containsMethodCall(func, "Open");
        if (hasOpen && !codeNoComment.contains("Close")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstMethodCallLine(func, "Open") > 0 ? firstMethodCallLine(func, "Open") : line0,
                    "资源未释放", "medium",
                    "检测到 Open 打开资源后方法内未见 defer Close()/Close()，异常路径下句柄泄漏",
                    "打开后立即 defer f.Close()，保证所有路径释放"));
        }
        // 4) 锁未释放：Lock 调用后无 Unlock
        boolean hasLock = containsMethodCall(func, "Lock");
        if (hasLock && !codeNoComment.contains("Unlock")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstMethodCallLine(func, "Lock") > 0 ? firstMethodCallLine(func, "Lock") : line0,
                    "锁未释放", "medium",
                    "检测到 mutex Lock() 加锁后方法内未见 Unlock()，panic/return 路径将造成死锁",
                    "加锁后立即 defer mu.Unlock()，或缩小临界区范围"));
        }
        // 5) panic 滥用：库/工具函数直接 panic 而非返回 error
        if (containsCall(func, "panic")) {
            defects.add(createDefect(projectId, taskId, relativePath, func.className, func.name,
                    firstCallLine(func, "panic") > 0 ? firstCallLine(func, "panic") : line0,
                    "panic 滥用", "medium",
                    "检测到业务函数直接 panic 而非返回 error，调用方无法以常规错误处理路径恢复",
                    "改为返回 error（fmt.Errorf），仅程序不可恢复状态才使用 panic"));
        }
    }

    private boolean containsCall(FuncInfo func, String callee) {
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

    private boolean containsMethodCall(FuncInfo func, String methodName) {
        boolean[] found = {false};
        walkSubtree(func.node, node -> {
            if (found[0]) {
                return;
            }
            if ("call_expression".equals(node.getType())) {
                TSNode fn = node.getChildByFieldName("function");
                if (!fn.isNull() && methodName.equals(resolveCallName(fn, func.sourceBytes))) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private int firstMethodCallLine(FuncInfo func, String methodName) {
        int[] line = {-1};
        walkSubtree(func.node, node -> {
            if (line[0] > 0 || !"call_expression".equals(node.getType())) {
                return;
            }
            TSNode fn = node.getChildByFieldName("function");
            if (!fn.isNull() && methodName.equals(resolveCallName(fn, func.sourceBytes))) {
                line[0] = node.getStartPoint().getRow() + 1;
            }
        });
        return line[0];
    }

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

    /** 剥离 Go 注释（双斜杠行注释与斜杠星块注释）：文本类检测不应把注释中的防护关键字当作已防护 */
    private static String stripGoComments(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("//[^\n]*", " ").replaceAll("(?s)/\\*.*?\\*/", " ");
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

    private String text(TSNode node, byte[] sourceBytes) {
        return nodeText(node, sourceBytes);
    }

    private void collectGoFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (name.startsWith(".") || name.equals("vendor") || name.equals("bin")
                        || name.equals("target") || name.equals("node_modules")) {
                    continue;
                }
                collectGoFiles(f, files);
            } else if (f.getName().endsWith(".go")) {
                files.add(f);
            }
        }
    }

    /** 函数定义信息 */
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
    }
}
