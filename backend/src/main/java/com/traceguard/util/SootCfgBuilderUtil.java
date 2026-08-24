package com.traceguard.util;

import com.traceguard.config.SootProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.GotoStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.InvokeStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.options.Options;
import soot.tagkit.LineNumberTag;
import soot.toolkits.graph.ExceptionalUnitGraph;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Soot 字节码级 CFG 构建器（GAP-003）
 * 用户上传的是源码 zip，Soot 分析对象是字节码：先用 JDK 自带 JavaCompiler 将源码 best-effort
 * 编译到临时目录，再基于字节码 ExceptionalUnitGraph 生成方法级 CFG（含异常边），
 * 输出与 CfgBuilderUtil（AST 级）完全同构的 JSON：{"nodes":[{"id,type,label,line"}],"edges":[{"from,to,label"}]}。
 *
 * 统一"开关 + 超时 + 熔断 + 兜底"四要素：
 * - 开关：traceguard.soot.enabled 控制（由 JavaCodeParserUtil 判定）；
 * - 超时：编译超时 compile-timeout-seconds / 单方法超时 method-cfg-timeout-seconds（FutureTask 中断）；
 * - 串行：专用单线程执行器串行构建（Soot 全局静态状态非线程安全，每次 G.reset()）；
 * - 兜底：编译失败 / 单方法失败 / 超时 -> 回退 AST 级 CfgBuilderUtil。
 */
@Component
public class SootCfgBuilderUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(SootCfgBuilderUtil.class);

    /** 单线程编译执行器（daemon） */
    private static final ExecutorService COMPILE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soot-compile");
        t.setDaemon(true);
        return t;
    });

    /** 单线程 Soot 求解执行器（daemon，串行规避全局状态并发问题） */
    private static final ExecutorService SOOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soot-cfg");
        t.setDaemon(true);
        return t;
    });

    private static SootProperties properties;

    /** 应用自动模块 jar 文件名：Soot 模块模式只发现 jar/含 module-info 的模块，普通类目录不可发现 */
    static final String APP_JAR_NAME = "app.jar";
    /** 由 app.jar 推导的自动模块名（去掉 .jar 后取字母数字，得 app） */
    static final String APP_MODULE_NAME = "app";
    /** Soot 模块路径特殊值：挂载当前 JDK 的 jrt 文件系统（解析 java.base 等 JDK 类） */
    static final String VIRTUAL_FS_FOR_JDK = "VIRTUAL_FS_FOR_JDK";

    @Autowired
    public void setProperties(SootProperties properties) {
        SootCfgBuilderUtil.properties = properties;
    }

    /** 测试用：重置静态配置（包私有） */
    static void configure(SootProperties properties) {
        SootCfgBuilderUtil.properties = properties;
    }

    /** 源码批量编译结果 */
    public static class CompileResult {
        private boolean success;
        /** 编译产物 class 根目录（供 Soot 作为 classpath） */
        private Path outputDir;
        /** 应用自动模块 jar（供 Soot 模块路径发现应用类） */
        private Path moduleJar;
        /** 临时目录根（任务结束后由调用方 finally 递归删除） */
        private Path tempRoot;
        private List<String> errors;
        private long elapsedMs;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Path getOutputDir() { return outputDir; }
        public void setOutputDir(Path outputDir) { this.outputDir = outputDir; }
        public Path getModuleJar() { return moduleJar; }
        public void setModuleJar(Path moduleJar) { this.moduleJar = moduleJar; }
        public Path getTempRoot() { return tempRoot; }
        public void setTempRoot(Path tempRoot) { this.tempRoot = tempRoot; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }

    /** 单方法 CFG 构建结果 */
    public static class CfgResult {
        private boolean success;
        /** 与 CfgBuilderUtil 同构的 JSON */
        private String cfgJson;
        /** 来源：soot（构建成功）/ ast（调用方回退时标记） */
        private String engine;
        private String message;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getCfgJson() { return cfgJson; }
        public void setCfgJson(String cfgJson) { this.cfgJson = cfgJson; }
        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /**
     * 用 JDK 自带 JavaCompiler 将源码 best-effort 编译到临时目录（任一源文件失败 -> 整体失败，保证语义一致）。
     * GAP-048：优先按探测到的项目目标 Java 版本（8/11/17）产出对应字节码，编译失败时逐级降级 17→11→8，
     * 全部失败才返回 success=false（调用方最终回退 AST 级 CfgBuilderUtil）。
     * 不传 projectRoot 时从 javaFiles 反推（适配测试与无根目录场景）。
     */
    public static CompileResult compileSources(List<Path> javaFiles) {
        return compileSources(javaFiles, null);
    }

    public static CompileResult compileSources(List<Path> javaFiles, Path projectRoot) {
        CompileResult result = new CompileResult();
        long start = System.currentTimeMillis();
        int timeoutSeconds = properties != null ? properties.getCompileTimeoutSeconds() : 60;
        if (javaFiles == null || javaFiles.isEmpty()) {
            result.setSuccess(false);
            result.setErrors(List.of("无待编译的 Java 源文件"));
            return result;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // 纯 JRE 运行环境（如 openjdk:8-jre-slim 容器）：无 javac，直接降级 AST
            result.setSuccess(false);
            result.setErrors(List.of("当前运行环境无 javac（ToolProvider.getSystemJavaCompiler() 为 null），Soot 编译路径不可用"));
            return result;
        }
        // GAP-048：探测项目目标 Java 版本（8/11/17），无法探测默认 17（Soot 4.4.1 要求字节码 ≤ 17）
        int target = detectReleaseVersion(projectRoot, javaFiles);
        try {
            Path tempRoot = Files.createTempDirectory("traceguard-soot");
            Path classesDir = tempRoot.resolve("classes");
            Files.createDirectories(classesDir);
            result.setTempRoot(tempRoot);
            result.setOutputDir(classesDir);

            // 在专用单线程执行器上运行编译（规避 Soot/Javac 静态状态），并保留编译超时熔断
            Callable<CompileResult> task = () -> doCompile(compiler, javaFiles, classesDir, target);
            FutureTask<CompileResult> future = new FutureTask<>(task);
            COMPILE_EXECUTOR.execute(future);
            CompileResult compiled = future.get(timeoutSeconds, TimeUnit.SECONDS);
            compiled.setTempRoot(tempRoot);
            compiled.setOutputDir(classesDir);
            if (compiled.isSuccess()) {
                // GAP-003：Soot 模块模式只发现 jar/含 module-info 的模块，编译成功后把应用类打成自动模块 jar
                Path jar = tempRoot.resolve(APP_JAR_NAME);
                if (createModuleJar(classesDir, jar)) {
                    compiled.setModuleJar(jar);
                } else {
                    compiled.setSuccess(false);
                    compiled.setErrors(List.of("应用类打包为模块 jar 失败，降级 AST CFG"));
                }
            } else {
                LOGGER.warn("Soot 源码按 --release {} 编译失败，将逐级降级 17→11→8: {}",
                        target, compiled.getErrors() != null ? compiled.getErrors().size() : 0);
            }
            compiled.setElapsedMs(System.currentTimeMillis() - start);
            return compiled;
        } catch (TimeoutException te) {
            result.setSuccess(false);
            result.setErrors(List.of("源码编译超时（" + timeoutSeconds + "s），降级 AST CFG"));
            LOGGER.warn("Soot 源码编译超时（{}s）", timeoutSeconds);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrors(List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            LOGGER.warn("Soot 源码编译失败: {}", e.getMessage());
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * GAP-048：根据探测到的目标版本编译，失败时按降级链 17→11→8 依次重试。
     * 返回最后一次尝试的结果（成功则 success=true，否则含诊断错误供上报告知）。
     */
    private static CompileResult doCompile(JavaCompiler compiler, List<Path> javaFiles, Path classesDir, int target) {
        int[] candidates;
        switch (target) {
            case 8:  candidates = new int[]{8, 11, 17}; break;
            case 11: candidates = new int[]{11, 8, 17}; break;
            default: candidates = new int[]{17, 11, 8}; break; // 探测不到 / 17
        }
        CompileResult last = null;
        for (int release : candidates) {
            CompileResult tryR = tryCompileRelease(compiler, javaFiles, classesDir, release);
            if (tryR.isSuccess()) {
                LOGGER.debug("Soot 源码按 --release {} 编译成功", release);
                return tryR;
            }
            last = tryR;
            LOGGER.debug("Soot 源码按 --release {} 编译失败，尝试下一档", release);
        }
        return last != null ? last : newCompileFailure("所有 --release 候选（17/11/8）均编译失败");
    }

    private static CompileResult tryCompileRelease(JavaCompiler compiler, List<Path> javaFiles, Path classesDir, int release) {
        CompileResult result = new CompileResult();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(javaFiles);
            // GAP-048：--release 由探测版本决定（原硬编码 17）；Soot 4.4.1 无法读取 Java 21 字节码故上限 17
            List<String> options = List.of("-d", classesDir.toString(), "-encoding", "UTF-8",
                    "--release", String.valueOf(release));
            Boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            result.setSuccess(Boolean.TRUE.equals(ok));
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    String src = d.getSource() != null ? d.getSource().getName() : "?";
                    errors.add(src + ":" + d.getLineNumber() + ": " + d.getMessage(null));
                }
            }
            if (errors.size() > 20) {
                errors = new ArrayList<>(errors.subList(0, 20));
                errors.add("... 共 " + (diagnostics.getDiagnostics().size() - errors.size()) + " 条诊断被截断");
            }
            result.setErrors(errors);
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrors(List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return result;
        }
    }

    private static CompileResult newCompileFailure(String msg) {
        CompileResult r = new CompileResult();
        r.setSuccess(false);
        r.setErrors(List.of(msg));
        return r;
    }

    /**
     * GAP-048：探测项目目标 Java 版本（8/11/17）。
     * 优先级：projectRoot 下的 pom.xml（maven.compiler.release/source、java.version）
     *   > build.gradle(.kts) 的 sourceCompatibility/release
     *   > 从 javaFiles 反推项目根再探测
     *   > 探测不到默认 17（Soot 4.4.1 上限）。
     */
    private static int detectReleaseVersion(Path projectRoot, List<Path> javaFiles) {
        Path root = projectRoot;
        if (root == null && javaFiles != null && !javaFiles.isEmpty()) {
            root = findProjectRoot(javaFiles.get(0));
        }
        if (root != null) {
            int v = detectFromPom(root);
            if (v > 0) return v;
            v = detectFromGradle(root);
            if (v > 0) return v;
        }
        return 17;
    }

    private static Path findProjectRoot(Path anyJavaFile) {
        Path dir = anyJavaFile.toAbsolutePath().getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) || Files.exists(dir.resolve("build.gradle"))
                    || Files.exists(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return anyJavaFile.toAbsolutePath().getParent();
    }

    private static int detectFromPom(Path root) {
        Path pom = root.resolve("pom.xml");
        if (!Files.exists(pom)) return -1;
        try {
            String content = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
            // 简单标签值提取（不引入 XML 解析依赖，避免对构建环境耦合）
            String release = tagValue(content, "maven.compiler.release");
            if (release != null) { int v = parseVersion(release); if (v > 0) return v; }
            String source = tagValue(content, "maven.compiler.source");
            if (source != null) { int v = parseVersion(source); if (v > 0) return v; }
            String javaVer = tagValue(content, "java.version");
            if (javaVer != null) { int v = parseVersion(javaVer); if (v > 0) return v; }
        } catch (Exception e) {
            LOGGER.debug("GAP-048 解析 pom.xml 失败: {}", e.getMessage());
        }
        return -1;
    }

    private static int detectFromGradle(Path root) {
        for (String name : new String[]{"build.gradle", "build.gradle.kts"}) {
            Path g = root.resolve(name);
            if (!Files.exists(g)) continue;
            try {
                String content = new String(Files.readAllBytes(g), StandardCharsets.UTF_8);
                // 匹配 sourceCompatibility = '11' / sourceCompatibility = 11 / release = 17 等
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "(?:sourceCompatibility|targetCompatibility|release)\\s*[=:]\\s*['\"]?(\\d+)['\"]?");
                java.util.regex.Matcher m = p.matcher(content);
                if (m.find()) {
                    int v = parseVersion(m.group(1));
                    if (v > 0) return v;
                }
            } catch (Exception e) {
                LOGGER.debug("GAP-048 解析 {} 失败: {}", name, e.getMessage());
            }
        }
        return -1;
    }

    private static String tagValue(String xml, String tag) {
        // 取最后一个出现的该标签（允许属性/命名空间变体），返回纯文本值
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<" + java.util.regex.Pattern.quote(tag) + "\\b[^>]*>([^<]*)<");
        java.util.regex.Matcher m = p.matcher(xml);
        String last = null;
        while (m.find()) last = m.group(1).trim();
        return last;
    }

    private static int parseVersion(String s) {
        if (s == null) return -1;
        String t = s.trim();
        // 兼容 "1.8" / "11" / "17" / "JavaSE-17" 等写法
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 8 && n <= 17) return n;          // 合法 LTS 范围
            if (n == 1) { // "1.8" 风格
                if (m.find()) { int minor = Integer.parseInt(m.group(1)); if (minor == 8) return 8; }
            }
        }
        return -1;
    }

    /** 将编译产物目录递归打包为自动模块 jar（条目保持 package 相对路径） */
    private static boolean createModuleJar(Path classesDir, Path jar) {
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            try (java.util.stream.Stream<Path> walk = Files.walk(classesDir)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String entry = classesDir.relativize(p).toString().replace('\\', '/');
                    jos.putNextEntry(new java.util.jar.JarEntry(entry));
                    Files.copy(p, jos);
                    jos.closeEntry();
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Soot 应用类打包失败: {}", e.getMessage());
            return false;
        }
    }

    /** 基于字节码为指定类的方法生成 CFG JSON（Soot ExceptionalUnitGraph，含异常 catch 边） */
    public static CfgResult buildMethodCfg(CompileResult compileResult, String className, String methodName, int paramCount) {
        CfgResult result = new CfgResult();
        if (compileResult == null || compileResult.getModuleJar() == null) {
            result.setSuccess(false);
            result.setMessage("Soot 编译产物或模块 jar 缺失");
            return result;
        }
        int timeoutSeconds = properties != null ? properties.getMethodCfgTimeoutSeconds() : 10;
        try {
            Callable<CfgResult> task = () -> doBuild(compileResult.getOutputDir(), compileResult.getModuleJar(),
                    className, methodName, paramCount);
            FutureTask<CfgResult> future = new FutureTask<>(task);
            SOOT_EXECUTOR.execute(future);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            result.setSuccess(false);
            result.setMessage("Soot 单方法 CFG 构建超时（" + timeoutSeconds + "s）");
            LOGGER.warn("Soot 单方法 CFG 构建超时: {}.{}", className, methodName);
        } catch (Throwable e) {
            result.setSuccess(false);
            result.setMessage(toBriefError(e));
            LOGGER.debug("Soot 单方法 CFG 构建失败: {}.{} - {}", className, methodName, result.getMessage());
        }
        return result;
    }

    private static CfgResult doBuild(Path classesDir, Path moduleJar, String className, String methodName, int paramCount) {
        CfgResult result = new CfgResult();
        try {
            // Soot 全局静态状态必须重置（非线程安全）
            G.reset();
            Options.v().set_src_prec(Options.src_prec_class);
            Options.v().set_keep_line_number(true);
            // JDK21 + Soot 4.x 正确姿势：classpath=应用类目录；modulepath=VIRTUAL_FS_FOR_JDK（JDK 类，jrt 文件系统）+ 应用模块 jar
            Options.v().set_soot_classpath(classesDir.toAbsolutePath().toString().replace('\\', '/'));
            Options.v().set_soot_modulepath(VIRTUAL_FS_FOR_JDK + File.pathSeparator
                    + moduleJar.toAbsolutePath().toString().replace('\\', '/'));

            // 先调用 SourceLocator.getClassSource 触发应用模块发现（注册 app 自动模块），
            // 否则 loadClassAndSupport 解析自动模块的 module-info 会抛 SootClassNotFoundException
            soot.SourceLocator.v().getClassSource(APP_MODULE_NAME + ":" + className);
            // 模块模式下类须带模块前缀（自动模块名 app）
            SootClass cls = Scene.v().loadClassAndSupport(APP_MODULE_NAME + ":" + className);
            cls.setApplicationClass();
            // ThrowableSet 等分析组件需要 JDK 异常类先行加载
            Scene.v().loadNecessaryClasses();
            SootMethod method = findMethod(cls, methodName, paramCount);
            if (method == null) {
                result.setSuccess(false);
                result.setMessage("未找到方法 " + className + "." + methodName + "(" + paramCount + " 参数)");
                return result;
            }
            // 模块模式下方法体可能未随类加载激活，显式加载
            try {
                method.retrieveActiveBody();
            } catch (Throwable e) {
                result.setSuccess(false);
                result.setMessage("方法体加载失败 " + className + "." + methodName + ": " + toBriefError(e));
                return result;
            }
            if (!method.hasActiveBody()) {
                result.setSuccess(false);
                result.setMessage("方法体不可用 " + className + "." + methodName + "(" + paramCount + " 参数)");
                return result;
            }
            ExceptionalUnitGraph graph = new ExceptionalUnitGraph(method.getActiveBody());
            result.setCfgJson(toJson(graph));
            result.setSuccess(true);
            result.setEngine("soot");
            return result;
        } catch (Throwable e) {
            // Soot 在 JDK 21 上可能抛出 AssertionError 等 Error，一律捕获降级 AST
            result.setSuccess(false);
            result.setMessage(toBriefError(e));
            return result;
        }
    }

    /** 错误消息：带首个异常消息与截断堆栈（便于诊断 Soot 内部异常） */
    private static String toBriefError(Throwable e) {
        StringBuilder sb = new StringBuilder();
        if (e.getMessage() != null) {
            sb.append(e.getMessage());
        } else {
            sb.append(e.getClass().getSimpleName());
        }
        StackTraceElement[] st = e.getStackTrace();
        if (st != null) {
            for (int i = 0; i < Math.min(8, st.length); i++) {
                sb.append(" @ ").append(st[i].getClassName()).append(".").append(st[i].getMethodName())
                        .append(":").append(st[i].getLineNumber());
            }
        }
        String s = sb.toString();
        return s.length() > 800 ? s.substring(0, 800) : s;
    }

    private static SootMethod findMethod(SootClass cls, String methodName, int paramCount) {
        for (SootMethod m : cls.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    /** 序列化为与 CfgBuilderUtil 完全同构的 JSON：{nodes:[{id,type,label,line}],edges:[{from,to,label}]} */
    private static String toJson(ExceptionalUnitGraph graph) {
        Map<Unit, Integer> idMap = new LinkedHashMap<>();
        List<Unit> units = new ArrayList<>(graph.getBody().getUnits());

        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        int startId = 0;
        sb.append("{\"id\":").append(startId).append(",\"type\":\"start\",\"label\":\"START\",\"line\":0}");
        int nextId = 1;
        for (Unit u : units) {
            idMap.put(u, nextId);
            sb.append(",{\"id\":").append(nextId)
                    .append(",\"type\":\"").append(classify(u))
                    .append("\",\"label\":\"").append(escape(truncate(u.toString(), 40)))
                    .append("\",\"line\":").append(lineOf(u)).append("}");
            nextId++;
        }
        int endId = nextId;
        sb.append(",{\"id\":").append(endId).append(",\"type\":\"end\",\"label\":\"END\",\"line\":0}");
        sb.append("],\"edges\":[");

        boolean first = true;
        // 入口边：start -> heads
        for (Unit head : graph.getHeads()) {
            first = appendEdge(sb, first, startId, idMap.get(head), "");
        }
        // 普通后继边 + 异常目标边
        for (Unit u : units) {
            int from = idMap.get(u);
            for (Unit succ : graph.getSuccsOf(u)) {
                Integer to = idMap.get(succ);
                if (to != null) {
                    first = appendEdge(sb, first, from, to, "");
                }
            }
            for (Unit target : graph.getExceptionalSuccsOf(u)) {
                Integer to = idMap.get(target);
                if (to != null) {
                    first = appendEdge(sb, first, from, to, "catch");
                }
            }
        }
        // 出口边：tails -> end
        for (Unit tail : graph.getTails()) {
            first = appendEdge(sb, first, idMap.get(tail), endId, "");
        }
        if (first) {
            // 空方法体：start 直接连 end
            appendEdge(sb, first, startId, endId, "");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static boolean appendEdge(StringBuilder sb, boolean first, Integer from, Integer to, String label) {
        if (from == null || to == null) {
            return first;
        }
        if (!first) {
            sb.append(",");
        }
        sb.append("{\"from\":").append(from).append(",\"to\":").append(to)
                .append(",\"label\":\"").append(escape(label)).append("\"}");
        return false;
    }

    /** Unit 归类：assign/if/goto/return/invoke/identity/stmt */
    private static String classify(Unit u) {
        if (u instanceof IfStmt) return "if";
        if (u instanceof GotoStmt) return "goto";
        if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt) return "return";
        if (u instanceof InvokeStmt) return "invoke";
        if (u instanceof IdentityStmt) return "identity";
        if (u instanceof AssignStmt) return "assign";
        return "stmt";
    }

    private static int lineOf(Unit u) {
        LineNumberTag tag = (LineNumberTag) u.getTag("LineNumberTag");
        return tag != null ? tag.getLineNumber() : 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 递归删除临时目录（任务结束 finally 调用，入参为 compileSources 返回的 tempRoot） */
    public static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            LOGGER.debug("清理 Soot 临时目录失败: {}", e.getMessage());
        }
    }
}
