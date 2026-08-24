package com.traceguard.util;

import com.traceguard.config.AlloyProperties;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 真实 Alloy 语义求解器（GAP-002）
 * 基于 Alloy Analyzer 官方库（kodkod + Sat4j）对 check/run 命令做真实可满足性求解，
 * 输出 SAT（存在反例/实例）/ UNSAT（断言成立）/ UNKNOWN / TIMEOUT / ERROR 五态。
 *
 * 统一"开关 + 超时 + 熔断 + 兜底"四要素：
 * - 开关：traceguard.alloy.enabled 控制（由 AlloySpecVerifierUtil 判定）；
 * - 超时：单次求解 FutureTask 超时控制（timeout-seconds），超时 cancel(true)；
 * - 串行：专用单线程执行器串行求解（Sat4J 与静态状态非线程安全）；
 * - 内存：实例迭代上限 max-instances、作用域上限 max-scope 收敛搜索空间。
 */
@Component
public class AlloyAnalyzerUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlloyAnalyzerUtil.class);

    /** 反例/实例文本截断上限（防 LONGTEXT 存储膨胀） */
    private static final int COUNTEREXAMPLE_MAX_LEN = 4000;

    /** 专用单线程求解执行器（daemon，串行规避 Sat4J 并发问题） */
    private static final ExecutorService SOLVER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alloy-solver");
        t.setDaemon(true);
        return t;
    });

    private static AlloyProperties properties;

    @Autowired
    public void setProperties(AlloyProperties properties) {
        AlloyAnalyzerUtil.properties = properties;
    }

    /** 测试用：重置静态配置（包私有） */
    static void configure(AlloyProperties properties) {
        AlloyAnalyzerUtil.properties = properties;
    }

    /** 求解结果 */
    public static class AlloyAnalyzeResult {
        /** SAT / UNSAT / UNKNOWN / TIMEOUT / ERROR */
        private String status;
        /** 实例数（SAT 时，受 max-instances 限制） */
        private int instanceCount;
        /** 反例/实例文本（A4Solution 序列化，截断存储） */
        private String counterexample;
        private long elapsedMs;
        private String message;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getInstanceCount() { return instanceCount; }
        public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }
        public String getCounterexample() { return counterexample; }
        public void setCounterexample(String counterexample) { this.counterexample = counterexample; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** 入口：对一段 Alloy 代码执行全部 check/run 命令并聚合结论 */
    public static AlloyAnalyzeResult analyze(String alloyCode) {
        AlloyAnalyzeResult result = new AlloyAnalyzeResult();
        long start = System.currentTimeMillis();
        int timeoutSeconds = properties != null ? properties.getTimeoutSeconds() : 30;
        int maxInstances = properties != null ? properties.getMaxInstances() : 5;
        int maxScope = properties != null ? properties.getMaxScope() : 6;

        if (alloyCode == null || alloyCode.trim().isEmpty()) {
            result.setStatus("ERROR");
            result.setMessage("Alloy 代码为空");
            return result;
        }

        Path tempDir = null;
        FutureTask<AlloyAnalyzeResult> future = null;
        try {
            tempDir = Files.createTempDirectory("traceguard-alloy");
            Path alsFile = tempDir.resolve("spec.als");
            Files.write(alsFile, alloyCode.getBytes(StandardCharsets.UTF_8));
            File als = alsFile.toFile();

            Callable<AlloyAnalyzeResult> task = () -> solve(als, maxInstances, maxScope);
            future = new FutureTask<>(task);
            SOLVER_EXECUTOR.execute(future);
            AlloyAnalyzeResult solved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            solved.setElapsedMs(System.currentTimeMillis() - start);
            return solved;
        } catch (TimeoutException te) {
            if (future != null) {
                future.cancel(true);
            }
            result.setStatus("TIMEOUT");
            result.setMessage("Alloy 求解超时（" + timeoutSeconds + "s）");
            LOGGER.warn("Alloy 求解超时（{}s）", timeoutSeconds);
        } catch (Exception e) {
            result.setStatus("ERROR");
            result.setMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            LOGGER.warn("Alloy 求解失败: {}", e.getMessage());
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir.toFile());
            }
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    /** 实际求解：解析 -> 逐命令执行 -> 聚合结论 */
    private static AlloyAnalyzeResult solve(File alsFile, int maxInstances, int maxScope) throws Exception {
        AlloyAnalyzeResult result = new AlloyAnalyzeResult();
        Module world = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, alsFile.getAbsolutePath());

        boolean anySat = false;
        boolean anyUnsat = false;
        int satCount = 0;
        StringBuilder counterexample = new StringBuilder();

        for (Command command : world.getAllCommands()) {
            capScope(command, maxScope);
            A4Options options = new A4Options();
            // Alloy 6.2.0：A4Options.solver 默认即 SATFactory.DEFAULT（SAT4J，随 pardinus.core 传递引入），无需显式指定
            options.skolemDepth = 1;
            A4Solution sol = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP, world.getAllReachableSigs(), command, options);

            if (!sol.satisfiable()) {
                anyUnsat = true;
                continue;
            }
            anySat = true;
            int count = 1;
            appendCounterexample(counterexample, sol, satCount);
            satCount++;
            for (int i = 1; i < maxInstances; i++) {
                sol = sol.next();
                if (sol == null || !sol.satisfiable()) {
                    break;
                }
                count++;
                appendCounterexample(counterexample, sol, satCount);
                satCount++;
            }
            result.setInstanceCount(result.getInstanceCount() + count);
        }

        if (anySat) {
            result.setStatus("SAT");
            result.setMessage("检测到可满足实例" + (result.getInstanceCount() > 0
                    ? "（发现反例/实例，共" + result.getInstanceCount() + "个）" : ""));
        } else if (anyUnsat) {
            result.setStatus("UNSAT");
            result.setMessage("全部 check 断言成立（规约一致，未发现反例）");
        } else {
            result.setStatus("UNKNOWN");
            result.setMessage("规约中未包含 check/run 命令，无法判定");
        }
        String ce = counterexample.toString().trim();
        result.setCounterexample(ce.isEmpty() ? null : ce);
        return result;
    }

    /**
     * 收敛超大型作用域（避免组合爆炸）。overall 为 Alloy 库 final 字段，需反射修改（CQ-10 脆弱点）。
     * 加固：失败提升为 WARN（可观测），并提示 Alloy 版本升级后需核对反射兼容性（Java 17+ 模块系统
     * 可能抛 InaccessibleObjectException，此时回退不收缩作用域，分析仍可运行但可能组合爆炸）。
     */
    private static void capScope(Command command, int maxScope) {
        try {
            if (command.overall > maxScope) {
                java.lang.reflect.Field f = Command.class.getField("overall");
                f.setAccessible(true);
                f.setInt(command, maxScope);
            }
        } catch (Exception e) {
            LOGGER.warn("Alloy 作用域收缩失败（反射修改 final 字段，Alloy 库升级可能失效），本次按原作用域执行: {}", e.getMessage());
        }
    }

    private static void appendCounterexample(StringBuilder sb, A4Solution sol, int index) {
        String text = sol.toString();
        if (sb.length() + text.length() > COUNTEREXAMPLE_MAX_LEN) {
            if (sb.length() < COUNTEREXAMPLE_MAX_LEN) {
                sb.append(text, 0, COUNTEREXAMPLE_MAX_LEN - sb.length());
            }
            return;
        }
        sb.append("--- 实例 ").append(index + 1).append(" ---\n").append(text).append("\n");
    }

    /** 递归删除临时目录（finally 清理） */
    private static void deleteRecursively(File dir) {
        try {
            if (dir == null || !dir.exists()) {
                return;
            }
            Files.walk(dir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            LOGGER.debug("清理 Alloy 临时目录失败: {}", e.getMessage());
        }
    }
}
