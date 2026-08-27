package com.traceguard.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.traceguard.config.CodeParseScope;
import com.traceguard.config.CodeParseScopeHolder;
import com.traceguard.config.SootProperties;
import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class JavaCodeParserUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaCodeParserUtil.class);

    /** GAP-003：Soot 任务级熔断阈值：连续 5 个方法 CFG 构建失败 -> 本任务剩余方法全部 AST */
    static final int SOOT_CIRCUIT_BREAK_THRESHOLD = 5;

    /** GAP-025：代码解析受控线程池大小（默认 min(4, CPU核)，可由 traceguard.perf.parse-threads 覆盖） */
    private static final int DEFAULT_PARSE_THREAD_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    /** FR-CODE-001 规则3（2.4 整改项）：类字段/属性字段清单单元的 methodName 标记（区别于普通方法单元） */
    public static final String FIELD_LIST_MARKER = "[字段清单]";

    /** 字段清单单元判定（一致性校验/语义比较时应跳过，仅作展示） */
    public static boolean isFieldListUnit(CodeUnit unit) {
        return unit != null && FIELD_LIST_MARKER.equals(unit.getMethodName());
    }

    /** GAP-025：解析并行线程数配置（0=使用默认值） */
    @Value("${traceguard.perf.parse-threads:0}")
    private int parseThreads;

    private int parseThreadPoolSize() {
        return parseThreads > 0 ? parseThreads : DEFAULT_PARSE_THREAD_POOL_SIZE;
    }

    @Autowired(required = false)
    private SootCfgBuilderUtil sootCfgBuilderUtil;

    @Autowired(required = false)
    private SootProperties sootProperties;

    private final Pattern sqlInjectionPattern = Pattern.compile("(\".*select.*\"\\s*\\+|\".*insert.*\"\\s*\\+|\".*update.*\"\\s*\\+|\".*delete.*\"\\s*\\+)", Pattern.CASE_INSENSITIVE);
    private final Pattern resourcePattern = Pattern.compile("new\\s+(FileInputStream|FileOutputStream|BufferedReader|BufferedWriter|Connection|Statement|ResultSet)\\s*\\(", Pattern.CASE_INSENSITIVE);
    /** Optional.get()直接使用（无isPresent/orElse保护） */
    private final Pattern optionalGetPattern = Pattern.compile("\\.get\\(\\)\\s*\\.");
    /** Map/List的get()结果直接链式调用（可能返回null导致NPE） */
    private final Pattern nullableChainPattern = Pattern.compile("\\b(map|cache|hashMap|treeMap)\\s*\\.\\s*get\\s*\\([^)]*\\)\\s*\\.");
    /** 循环边界使用<=与length/size()比较（典型数组越界），变量名加词边界防myindex类误报 */
    private final Pattern offByOnePattern = Pattern.compile("\\b(i|j|k|index)\\s*<=\\s*[\\w.]+\\.(length|size\\(\\))");

    public List<CodeUnit> parseProject(String projectPath) {
        return parseProject(projectPath, null);
    }

    /** GAP-003：任务级传入 Soot 编译结果（AnalysisService 解析前编译一次并缓存），无则全量 AST */
    public List<CodeUnit> parseProject(String projectPath, SootCfgBuilderUtil.CompileResult compileResult) {
        List<CodeUnit> codeUnits = new ArrayList<>();
        File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            return codeUnits;
        }
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(projectDir, javaFiles);
        // GAP-003：任务级熔断器（连续 5 个方法 CFG 失败 -> 剩余方法全部 AST）
        TaskCircuitBreaker sootBreaker = new TaskCircuitBreaker(SOOT_CIRCUIT_BREAK_THRESHOLD);
        for (File javaFile : javaFiles) {
            codeUnits.addAll(parseFile(javaFile, projectPath, compileResult, sootBreaker));
        }
        return codeUnits;
    }

    /** GAP-014：单文件隔离解析结果 */
    public static class ProjectParseResult {
        public final List<CodeUnit> codeUnits = new ArrayList<>();
        public final List<com.traceguard.service.ParseFailure> failures = new ArrayList<>();
    }

    /**
     * GAP-014 + GAP-025：单文件隔离解析——任一文件解析异常仅跳过该文件并计入 failures（key 为源码相对路径），
     * 不中断整体解析（需求 5.3"文档/代码解析失败不影响其他文件"）。
     * 
     * GAP-025：代码解析并行化——文件级独立任务使用受控线程池并行处理，提升大规模工程解析速度。
     */
    public ProjectParseResult parseProjectIsolated(String projectPath, SootCfgBuilderUtil.CompileResult compileResult) {
        ProjectParseResult result = new ProjectParseResult();
        File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            return result;
        }
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(projectDir, javaFiles);
        
        if (javaFiles.isEmpty()) {
            return result;
        }
        
        // GAP-025：并行解析（受控线程池，最多 parseThreadPoolSize() 个并发）
        boolean useParallel = javaFiles.size() >= 4; // 文件数较少时串行避免线程开销
        
        try {
            TaskCircuitBreaker sootBreaker = new TaskCircuitBreaker(SOOT_CIRCUIT_BREAK_THRESHOLD);
            ExecutorService executor = Executors.newFixedThreadPool(
                    useParallel ? parseThreadPoolSize() : 1,
                    r -> {
                        Thread t = new Thread(r);
                        t.setName("code-parser");
                        t.setDaemon(true);
                        return t;
                    });
            
            if (useParallel) {
                LOGGER.info("GAP-025: 代码解析启动并行模式，文件数={}，线程池大小={}", javaFiles.size(), parseThreadPoolSize());
            }
            
            // 提交所有解析任务
            List<CompletableFuture<List<CodeUnit>>> futures = new ArrayList<>();
            for (File javaFile : javaFiles) {
                CompletableFuture<List<CodeUnit>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return parseFile(javaFile, projectPath, compileResult, sootBreaker);
                    } catch (Exception e) {
                        LOGGER.warn("GAP-014: 代码文件解析失败已隔离（跳过该文件）: {} - {}", 
                                relativePath(javaFile, projectPath), e.getMessage());
                        return Collections.emptyList();
                    }
                }, executor);
                futures.add(future);
            }
            
            // 收集结果并统计失败
            List<CompletableFuture<Void>> completionFutures = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                File javaFile = javaFiles.get(i);
                CompletableFuture<List<CodeUnit>> future = futures.get(i);
                CompletableFuture<Void> onComplete = future.thenAccept(codeUnits -> {
                    if (codeUnits != null && !codeUnits.isEmpty()) {
                        synchronized (result.codeUnits) {
                            result.codeUnits.addAll(codeUnits);
                        }
                    }
                }).whenComplete((Void x, Throwable ex) -> {
                    if (ex != null) {
                        String rel = relativePath(javaFile, projectPath);
                        Exception e = (ex instanceof Exception) ? (Exception) ex : new RuntimeException(ex);
                        synchronized (result.failures) {
                            result.failures.add(com.traceguard.service.ParseFailure.fromException(rel, e, javaFile.length()));
                        }
                        LOGGER.warn("GAP-014: 代码文件解析异常已隔离（跳过该文件）: {} - {}", rel, ex.getMessage());
                    }
                });
                completionFutures.add(onComplete);
            }
            
            // 等待所有任务完成
            CompletableFuture<Void>[] allDone = completionFutures.toArray(new CompletableFuture[0]);
            CompletableFuture.allOf(allDone).join();
            
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                    LOGGER.warn("GAP-025: 代码解析超时，强制终止解析线程池");
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            if (!result.failures.isEmpty()) {
                LOGGER.info("GAP-025: 代码解析完成，成功={}，失败={}", 
                        result.codeUnits.size(), result.failures.size());
            }
            
        } catch (Exception e) {
            LOGGER.error("GAP-025: 代码解析并行化失败，降级为串行模式: {}", e.getMessage());
            // 降级到串行模式
            TaskCircuitBreaker sootBreaker = new TaskCircuitBreaker(SOOT_CIRCUIT_BREAK_THRESHOLD);
            for (File javaFile : javaFiles) {
                try {
                    result.codeUnits.addAll(parseFile(javaFile, projectPath, compileResult, sootBreaker));
                } catch (Exception ex) {
                    String rel = relativePath(javaFile, projectPath);
                    result.failures.add(com.traceguard.service.ParseFailure.fromException(rel, ex, javaFile.length()));
                    LOGGER.warn("GAP-014: 代码文件解析失败已隔离（跳过该文件）: {} - {}", rel, ex.getMessage());
                }
            }
        }
        
        return result;
    }

    private void collectJavaFiles(File dir, List<File> javaFiles) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".") && !file.getName().equals("target") && !file.getName().equals("build")) {
                    collectJavaFiles(file, javaFiles);
                }
            } else if (file.getName().endsWith(".java")) {
                javaFiles.add(file);
            }
        }
    }

    /** 以UTF-8读取源码文本（解决Windows默认GBK编码下中文注释/字符串乱码问题） */
    private String readUtf8(File file) throws java.io.IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 计算相对工程根目录的路径（避免绝对路径泄漏到缺陷报告） */
    private String relativePath(File file, String projectPath) {
        String abs = file.getAbsolutePath().replace("\\", "/");
        if (projectPath != null && !projectPath.isEmpty()) {
            String base = projectPath.replace("\\", "/");
            if (!base.endsWith("/")) base = base + "/";
            if (abs.startsWith(base)) {
                return abs.substring(base.length());
            }
        }
        return file.getName();
    }

    public List<CodeUnit> parseFile(File file, String projectPath) {
        return parseFile(file, projectPath, null, new TaskCircuitBreaker(SOOT_CIRCUIT_BREAK_THRESHOLD));
    }

    /**
     * GAP-003：cfgData 生成改造--Soot 字节码级 CFG 优先（编译成功、开关开启、未熔断），
     * 单方法失败回退 AST 级 CfgBuilderUtil 并计数，连续 SOOT_CIRCUIT_BREAK_THRESHOLD 次失败熔断。
     */
    public List<CodeUnit> parseFile(File file, String projectPath,
                                    SootCfgBuilderUtil.CompileResult compileResult,
                                    TaskCircuitBreaker sootBreaker) {
        List<CodeUnit> codeUnits = new ArrayList<>();
        try {
            JavaParser javaParser = new JavaParser();
            ParseResult<CompilationUnit> result = javaParser.parse(readUtf8(file));
            Optional<CompilationUnit> cuOpt = result.getResult();
            if (!cuOpt.isPresent()) {
                return codeUnits;
            }
            CompilationUnit cu = cuOpt.get();
            String relativePath = relativePath(file, projectPath);
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");
            boolean sootReady = compileResult != null && compileResult.isSuccess()
                    && sootProperties != null && sootProperties.isEnabled()
                    && sootCfgBuilderUtil != null;
            // FR-CODE-001 规则3/4（2.4 整改项）：解析范围配置（包含/排除包、类、方法，热生效）
            CodeParseScope parseScope = CodeParseScopeHolder.get();
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration cls, Void arg) {
                    super.visit(cls, arg);
                    String className = cls.getNameAsString();
                    String fullClassName = packageName.isEmpty() ? className : packageName + "." + className;
                    boolean classIncluded = parseScope.matchesClass(packageName, className);
                    // FR-CODE-001 规则3（2.4 整改项）：类字段/属性字段清单单元——单独结构化展示，
                    // 不参与一致性校验/语义比较（下游通过 isFieldListUnit 过滤），供代码浏览与审计
                    List<FieldDeclaration> fields = cls.getFields();
                    if (classIncluded && !fields.isEmpty()) {
                        CodeUnit fieldUnit = new CodeUnit();
                        fieldUnit.setCodeId(generateCodeId(relativePath, className, FIELD_LIST_MARKER));
                        fieldUnit.setFilePath(relativePath);
                        fieldUnit.setClassName(className);
                        fieldUnit.setMethodName(FIELD_LIST_MARKER);
                        fieldUnit.setStartLine(fields.get(0).getBegin().map(p -> p.line).orElse(0));
                        fieldUnit.setEndLine(fields.get(fields.size() - 1).getEnd().map(p -> p.line).orElse(0));
                        StringBuilder fieldSb = new StringBuilder();
                        fieldSb.append("// 类字段/属性声明清单（共 ").append(fields.size()).append(" 个）\n");
                        for (FieldDeclaration f : fields) {
                            fieldSb.append(f.toString()).append("\n");
                        }
                        fieldUnit.setCodeContent(fieldSb.toString().trim());
                        fieldUnit.setLogicDescription("类字段/属性声明清单，共 " + fields.size() + " 个字段");
                        codeUnits.add(fieldUnit);
                    }
                    for (MethodDeclaration method : cls.getMethods()) {
                        // FR-CODE-001 规则4（2.4 整改项）：按方法名包含/排除过滤
                        if (!parseScope.matchesMethod(packageName, className, method.getNameAsString())) {
                            continue;
                        }
                        CodeUnit unit = new CodeUnit();
                        unit.setCodeId(generateCodeId(relativePath, className, method.getNameAsString()));
                        unit.setFilePath(relativePath);
                        unit.setClassName(className);
                        unit.setMethodName(method.getNameAsString());
                        unit.setStartLine(method.getBegin().map(p -> p.line).orElse(0));
                        unit.setEndLine(method.getEnd().map(p -> p.line).orElse(0));
                        unit.setCodeContent(method.toString());
                        String comment = method.getComment().map(Comment::getContent).orElse("");
                        unit.setLogicDescription(extractLogicDescription(method, comment));
                        unit.setCfgData(buildCfg(method, sootReady, compileResult, fullClassName, sootBreaker));
                        unit.setSemanticVector(SemanticVectorUtil.buildVector(method));
                        // GAP-016：方法圈复杂度（单个方法 AST 异常按 1 兜底，不阻塞解析）
                        try {
                            unit.setCyclomaticComplexity(calcCyclomaticComplexity(method));
                        } catch (Exception ce) {
                            unit.setCyclomaticComplexity(1);
                        }
                        codeUnits.add(unit);
                    }
                }
            }, null);
        } catch (Exception e) {
            LOGGER.warn("解析Java文件失败[{}]: {}", file.getName(), e.getMessage());
        }
        return codeUnits;
    }

    /** GAP-003：Soot 字节码 CFG 优先，单方法失败/熔断回退 AST 级 CfgBuilderUtil */
    private String buildCfg(MethodDeclaration method, boolean sootReady,
                            SootCfgBuilderUtil.CompileResult compileResult,
                            String fullClassName, TaskCircuitBreaker sootBreaker) {
        if (sootReady && !sootBreaker.isOpen()) {
            SootCfgBuilderUtil.CfgResult cfg = sootCfgBuilderUtil.buildMethodCfg(
                    compileResult, fullClassName,
                    method.getNameAsString(), method.getParameters().size());
            if (cfg.isSuccess()) {
                sootBreaker.recordSuccess();
                return cfg.getCfgJson();
            }
            // 单方法失败 -> 该方法回退 AST，任务级失败计数 +1
            sootBreaker.recordFailure();
            LOGGER.debug("Soot CFG 构建失败，回退 AST: {}.{} - {}", fullClassName, method.getNameAsString(), cfg.getMessage());
        }
        return CfgBuilderUtil.build(method);
    }

    /**
     * GAP-016：方法圈复杂度（McCabe）——遍历方法 AST 统计判定节点，基值 1。
     * 判定节点：if/for/foreach/while/do-while/switch-case/catch/三元/&&/|| 各 +1。
     */
    private int calcCyclomaticComplexity(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 1;
        }
        ComplexityVisitor visitor = new ComplexityVisitor();
        method.accept(visitor, null);
        return 1 + visitor.decisions;
    }

    /** GAP-016：圈复杂度判定节点计数器（VoidVisitorAdapter 遍历） */
    private static class ComplexityVisitor extends VoidVisitorAdapter<Void> {
        int decisions = 0;

        @Override
        public void visit(IfStmt n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForEachStmt n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(DoStmt n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(CatchClause n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchEntry n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ConditionalExpr n, Void arg) {
            decisions++;
            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                decisions++;
            }
            super.visit(n, arg);
        }
    }

    public List<CodeDefect> detectBasicDefects(File file, String projectPath, Long projectId, Long taskId) {
        List<CodeDefect> defects = new ArrayList<>();
        try {
            String relativePath = relativePath(file, projectPath);
            JavaParser javaParser = new JavaParser();
            ParseResult<CompilationUnit> result = javaParser.parse(readUtf8(file));
            Optional<CompilationUnit> cuOpt = result.getResult();
            if (!cuOpt.isPresent()) {
                return defects;
            }
            CompilationUnit cu = cuOpt.get();
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration method, Void arg) {
                    super.visit(method, arg);
                    String methodName = method.getNameAsString();
                    String className = "";
                    Optional<ClassOrInterfaceDeclaration> clsOpt = method.findAncestor(ClassOrInterfaceDeclaration.class);
                    if (clsOpt.isPresent()) {
                        className = clsOpt.get().getNameAsString();
                    }
                    String code = method.toString();
                    if (sqlInjectionPattern.matcher(code).find()) {
                        // 行号定位到首个SQL字符串拼接表达式（原实现误记为方法起始行）
                        int sqlLine = firstSqlConcatLine(method);
                        if (sqlLine <= 0) sqlLine = method.getBegin().map(p -> p.line).orElse(0);
                        CodeDefect defect = createDefect(projectId, taskId, relativePath, className, methodName,
                                sqlLine, "SQL注入", "high",
                                "检测到可能存在SQL注入风险的代码，字符串拼接SQL语句",
                                "使用参数化查询（PreparedStatement）替代字符串拼接SQL");
                        defects.add(defect);
                    }
                    checkInfiniteLoop(method, relativePath, className, methodName, projectId, taskId, defects);
                    checkResourceLeak(method, relativePath, className, methodName, projectId, taskId, defects);
                    checkNullPointer(method, code, relativePath, className, methodName, projectId, taskId, defects);
                    checkArrayIndexOutOfBounds(method, code, relativePath, className, methodName, projectId, taskId, defects);
                    checkFixedIndexOutOfBounds(method, relativePath, className, methodName, projectId, taskId, defects);
                    checkEmptyListGetZero(method, code, relativePath, className, methodName, projectId, taskId, defects);
                    checkComparatorTransitivity(method, relativePath, className, methodName, projectId, taskId, defects);
                    checkEmptyCatch(method, relativePath, className, methodName, projectId, taskId, defects);
                }
            }, null);
        } catch (Exception e) {
            LOGGER.warn("缺陷检测失败[{}]: {}", file.getName(), e.getMessage());
        }
        return defects;
    }

    private void checkInfiniteLoop(MethodDeclaration method, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(WhileStmt whileStmt, Void arg) {
                super.visit(whileStmt, arg);
                if (whileStmt.getCondition().isBooleanLiteralExpr() && whileStmt.getCondition().asBooleanLiteralExpr().getValue()) {
                    String body = whileStmt.getBody().toString();
                    if (!body.contains("break") && !body.contains("return") && !body.contains("System.exit")) {
                        int line = whileStmt.getBegin().map(p -> p.line).orElse(0);
                        CodeDefect defect = createDefect(projectId, taskId, filePath, className, methodName, line,
                                "逻辑死循环", "medium", "检测到无条件break/return的while(true)循环",
                                "请在循环体内添加退出条件或break/return语句");
                        defects.add(defect);
                    }
                }
            }
        }, null);
    }

    private void checkResourceLeak(MethodDeclaration method, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        String methodCode = method.toString();
        if (resourcePattern.matcher(methodCode).find()) {
            if (!methodCode.contains("try (") && !methodCode.contains("finally") && !methodCode.contains(".close()")) {
                // 行号定位到首个资源创建语句（原实现误记为方法起始行）
                int line = firstMatchLine(method, resourcePattern);
                CodeDefect defect = createDefect(projectId, taskId, filePath, className, methodName, line,
                        "资源未释放", "medium", "检测到资源对象创建但未在finally块或try-with-resources中关闭",
                        "使用try-with-resources语句或在finally块中调用close()方法释放资源");
                defects.add(defect);
            }
        }
    }

    /**
     * 空指针风险检测：
     * 1) Optional.get()直接链式使用且无isPresent/orElse/ifPresent保护
     * 2) Map.get()结果直接链式调用（Map.get可能返回null）
     */
    private void checkNullPointer(MethodDeclaration method, String code, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        int methodLine = method.getBegin().map(p -> p.line).orElse(0);
        boolean guarded = code.contains("isPresent") || code.contains("orElse") || code.contains("ifPresent");
        if (!guarded && optionalGetPattern.matcher(code).find()) {
            // 行号定位到首个Optional.get()链式调用语句
            int line = firstOptionalGetChainLine(method);
            if (line <= 0) line = methodLine;
            defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                    "空指针风险", "high",
                    "Optional.get()直接使用且方法内未见isPresent()/orElse()判空保护，Optional为空时将抛出NoSuchElementException",
                    "调用get()前先使用isPresent()判断，或改用orElse()/orElseGet()/ifPresent()安全取值"));
        }
        if (nullableChainPattern.matcher(code).find()) {
            // 行号定位到首个Map.get()链式调用语句
            int line = firstNullableChainLine(method);
            if (line <= 0) line = methodLine;
            defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                    "空指针风险", "medium",
                    "Map.get()返回值直接链式调用成员方法，键不存在时返回null将导致空指针异常",
                    "先判空再使用：Object v = map.get(key); if (v != null) { v.xxx(); }，或使用getOrDefault()"));
        }
    }

    /**
     * 数组越界风险检测：循环边界使用<=与length/size()比较（差一错误）
     */
    private void checkArrayIndexOutOfBounds(MethodDeclaration method, String code, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        if (offByOnePattern.matcher(code).find()) {
            // 行号定位到首个<=比较表达式（原实现误记为方法起始行）
            int line = firstOffByOneLine(method);
            if (line <= 0) line = method.getBegin().map(p -> p.line).orElse(0);
            defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                    "数组越界风险", "high",
                    "循环条件使用<=与length/size()比较，最后一次迭代将访问越界下标（差一错误）",
                    "将循环条件改为 i < arr.length（或 i < list.size()），有效下标范围为0到length-1"));
        }
    }

    /** 在方法内定位首个节点文本匹配给定正则的语句行号（AST真实行号，避免全记方法起始行） */
    private int firstMatchLine(MethodDeclaration method, Pattern pattern) {
        int[] line = {-1};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ObjectCreationExpr obj, Void arg) {
                super.visit(obj, arg);
                if (line[0] < 0 && pattern.matcher(obj.toString()).find()) {
                    line[0] = obj.getBegin().map(p -> p.line).orElse(0);
                }
            }
            @Override
            public void visit(MethodCallExpr call, Void arg) {
                super.visit(call, arg);
                if (line[0] < 0 && pattern.matcher(call.toString()).find()) {
                    line[0] = call.getBegin().map(p -> p.line).orElse(0);
                }
            }
        }, null);
        return line[0];
    }

    /** 定位首个Optional.get()链式调用（get()结果继续链式调用成员）的真实行号 */
    private int firstOptionalGetChainLine(MethodDeclaration method) {
        return firstChainedGetLine(method, null);
    }

    /** 定位首个Map类变量get()链式调用的真实行号，scopeNames为null表示不限制接收者 */
    private int firstChainedGetLine(MethodDeclaration method, java.util.regex.Pattern scopeNames) {
        int[] line = {-1};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr call, Void arg) {
                super.visit(call, arg);
                if (line[0] >= 0 || !"get".equals(call.getNameAsString())) {
                    return;
                }
                if (scopeNames != null) {
                    Expression scope = call.getScope().orElse(null);
                    if (!(scope instanceof NameExpr) || !scopeNames.matcher(((NameExpr) scope).getNameAsString()).matches()) {
                        return;
                    }
                }
                if (call.getParentNode().isPresent() && call.getParentNode().get() instanceof MethodCallExpr) {
                    MethodCallExpr parent = (MethodCallExpr) call.getParentNode().get();
                    if (parent.getScope().isPresent() && parent.getScope().get() == call) {
                        line[0] = call.getBegin().map(p -> p.line).orElse(0);
                    }
                }
            }
        }, null);
        return line[0];
    }

    /** 定位首个Map.get()链式调用（map/cache等接收者）的真实行号 */
    private int firstNullableChainLine(MethodDeclaration method) {
        return firstChainedGetLine(method, Pattern.compile("(map|cache|hashMap|treeMap)"));
    }

    /** 定位首个 i <= xxx.length/size() 比较表达式的真实行号 */
    private int firstOffByOneLine(MethodDeclaration method) {
        int[] line = {-1};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(BinaryExpr bin, Void arg) {
                super.visit(bin, arg);
                if (line[0] < 0 && bin.getOperator() == BinaryExpr.Operator.LESS_EQUALS
                        && offByOnePattern.matcher(bin.toString()).find()) {
                    line[0] = bin.getBegin().map(p -> p.line).orElse(0);
                }
            }
        }, null);
        return line[0];
    }

    /** 定位首个SQL字符串拼接表达式（字面量含SQL关键字后接+拼接）的真实行号 */
    private int firstSqlConcatLine(MethodDeclaration method) {
        int[] line = {-1};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(BinaryExpr bin, Void arg) {
                super.visit(bin, arg);
                // 嵌套拼接深度优先访问内层节点，自动取最靠前的拼接点
                if (line[0] < 0 && bin.getOperator() == BinaryExpr.Operator.PLUS
                        && sqlInjectionPattern.matcher(bin.toString()).find()) {
                    line[0] = bin.getBegin().map(p -> p.line).orElse(0);
                }
            }
        }, null);
        return line[0];
    }

    /**
     * 未处理异常检测（FR-CODE-004）：空catch块或仅含注释的catch块，异常被静默吞掉
     */
    private void checkEmptyCatch(MethodDeclaration method, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(CatchClause catchClause, Void arg) {
                super.visit(catchClause, arg);
                BlockStmt body = catchClause.getBody();
                // 无任何语句（含仅有注释的catch块）均视为异常被静默吞掉
                if (!body.getStatements().isEmpty()) {
                    return;
                }
                int line = catchClause.getBegin().map(p -> p.line).orElse(0);
                defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                        "未处理异常", "medium",
                        "检测到空的catch块，异常被静默吞掉，问题发生时难以定位",
                        "在catch块中记录日志（如LOGGER.warn）或向上抛出异常，至少保留异常现场信息"));
            }
        }, null);
    }

    /**
     * 固定下标越界检测（FR-CODE-004）：X.get(X.size()) 或 X.get(X.length)——有效下标最大为 size-1/length-1，
     * 使用 size()/length 作为下标必然越界（区别于循环差一错误 off-by-one）。
     * 例：answers.get(answers.size()) 应改为 answers.get(answers.size()-1)。
     */
    private final Pattern fixedIndexPattern = Pattern.compile("\\.get\\s*\\(\\s*[^()]*\\.(size\\(\\)|length)\\s*\\)");
    private void checkFixedIndexOutOfBounds(MethodDeclaration method, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        String code = method.toString();
        if (fixedIndexPattern.matcher(code).find()) {
            int line = firstMatchLine(method, fixedIndexPattern);
            if (line <= 0) line = method.getBegin().map(p -> p.line).orElse(0);
            defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                    "数组越界风险", "high",
                    "检测到使用集合/数组的 size()/length 作为下标直接取值（如 list.get(list.size())），有效下标范围为 0 到 size-1/length-1，该下标必然越界",
                    "将下标改为 size()-1/length-1，或先判空/判边界再取值"));
        }
    }

    /**
     * 空集合取首元素越界风险（FR-CODE-004）：方法内出现 X.get(0)/X.get(1) 等字面小整数下标取值，
     * 且方法内未先做 isEmpty()/size()>0 判空保护，集合为空时必然抛出 IndexOutOfBoundsException。
     * 例：order.getItems().get(0)（未检查 items 是否为空）。
     */
    private final Pattern listGetZeroPattern = Pattern.compile("\\.get\\s*\\(\\s*(0|1)\\s*\\)");
    private void checkEmptyListGetZero(MethodDeclaration method, String code, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        if (listGetZeroPattern.matcher(code).find() && !code.contains("size() == 0") && !code.contains("size()>0") && !code.contains("size() > 0") && !code.contains("size() != 0") && !code.contains("size()<1") && !code.contains("size() < 1")) {
            int line = firstMatchLine(method, listGetZeroPattern);
            if (line <= 0) line = method.getBegin().map(p -> p.line).orElse(0);
            defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                    "数组越界风险", "medium",
                    "检测到对集合直接取下标 0/1 的元素，且方法内未见 isEmpty()/size() 判空保护，集合为空时将抛出越界异常",
                    "取值前先判断集合非空：if (!list.isEmpty()) { list.get(0); }，或做好边界校验"));
        }
    }

    /**
     * 比较器传递性违反检测（FR-CODE-004）：排序 Comparator/Lambda 中当两个元素相等时返回非 0（应为 0），
     * 违反 Comparator 的 sgn(compare(x,y)) == -sgn(compare(y,x)) 与相等返回 0 的约定，
     * 可能导致排序结果不确定或抛出 IllegalArgumentException。
     * 形态：(a, b) -> { if (a <= b) return 1; return -1; }（相等 a==b 时进入 a<=b 分支返回 1）。
     */
    private void checkComparatorTransitivity(MethodDeclaration method, String filePath, String className, String methodName, Long projectId, Long taskId, List<CodeDefect> defects) {
        try {
            method.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(com.github.javaparser.ast.expr.LambdaExpr lambda, Void arg) {
                    super.visit(lambda, arg);
                    String body = lambda.getBody().toString();
                    boolean hasReturnOne = body.contains("return 1");
                    boolean hasLe = body.contains("<=");
                    boolean hasReturnNeg = body.contains("return -1");
                    if (hasReturnOne && hasLe && hasReturnNeg) {
                        int line = lambda.getBegin().map(p -> p.line).orElse(0);
                        defects.add(createDefect(projectId, taskId, filePath, className, methodName, line,
                                "业务逻辑不一致", "high",
                                "排序比较器在两个元素相等时返回非 0（如 if (a<=b) return 1; return -1;），违反 Comparator 传递性约定与相等返回 0 的要求",
                                "相等时应返回 0：if (a.equals(b)) return 0; 或直接使用 Integer.compare(a, b)"));
                    }
                }
            }, null);
        } catch (Exception e) {
            LOGGER.debug("checkComparatorTransitivity 跳过[{}#{}]: {}", className, methodName, e.getMessage());
        }
    }

    private CodeDefect createDefect(Long projectId, Long taskId, String filePath, String className, String methodName, int line, String type, String severity, String desc, String suggestion) {
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

    private String generateCodeId(String filePath, String className, String methodName) {
        return "CODE-" + Math.abs((filePath + "#" + className + "#" + methodName).hashCode());
    }

    private String extractLogicDescription(MethodDeclaration method, String comment) {
        StringBuilder desc = new StringBuilder();
        if (comment != null && !comment.isEmpty()) {
            // 仅剥离每行行首的Javadoc装饰星号，保留正文中的*与/字符
            // 注：String.lines()为Java 11 API，本项目基线Java 8，改用split兼容
            String cleaned = java.util.Arrays.stream(comment.split("\\r?\\n"))
                    .map(l -> l.replaceFirst("^\\s*\\*\\s?", "").trim())
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.joining(" "));
            if (!cleaned.isEmpty()) {
                desc.append(cleaned).append("; ");
            }
        }
        desc.append("方法").append(method.getNameAsString()).append(": ");
        if (method.isPublic()) desc.append("public ");
        if (method.isPrivate()) desc.append("private ");
        if (method.isStatic()) desc.append("static ");
        desc.append("返回").append(method.getType().asString()).append("; ");
        method.getParameters().forEach(p -> desc.append("参数").append(p.getNameAsString()).append("(").append(p.getTypeAsString()).append("), "));
        return desc.toString();
    }
}
