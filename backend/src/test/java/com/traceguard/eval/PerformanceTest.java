package com.traceguard.eval;

import com.traceguard.entity.AnalysisTask;
import com.traceguard.entity.Project;
import com.traceguard.service.AnalysisService;
import com.traceguard.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.junit.jupiter.api.Tag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GAP-008：性能基准测试（千行 ≤ 2min、万行 ≤ 10min）。
 *
 * 运行口径：
 *   - 规则模式（llm.enabled=false, embedding 不可用自动跳过）作为达标判定基准
 *   - 各阶段耗时从 AnalysisService.execution_log 提取 [performance] 记录
 *   - 每个规模（kilo/tenk）跑 3 次取中位数
 *   - 输出 docs/03-报告/性能基准报告-{kilo|tenk}.md（含环境快照、分阶段耗时表、达标结论、瓶颈 Top3）
 *
 * 前置条件：
 *   - samples/benchmark/kilo/ 与 tenk/ 工程已生成（含 requirements.txt + code/）
 *   - 数据库连接正常（测试会写入 project/analysis_task/consistency_result/defect 等表）
 *   - application.yml 中 llm.enabled=false（确保规则模式）
 */
@SpringBootTest
@ActiveProfiles("test")  // 使用 test profile（如未配置则沿用 dev，建议 test 库隔离）
@TestPropertySource(properties = "traceguard.analysis.strict-spec-verify=false")
@Sql(scripts = "/gap045-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("GAP-008 性能基准测试")
@Tag("perf") // TST-04：默认排除，需 -Pperf 单独运行
class PerformanceTest {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectService projectService;

    /** 基准工程根目录（surefire/IDE 工作目录不同，运行时动态定位） */
    private File benchmarkRoot;

    /** 达标阈值（毫秒） */
    private static final long KILO_THRESHOLD_MS = 2 * 60 * 1000;   // 2min
    private static final long TENK_THRESHOLD_MS = 10 * 60 * 1000;  // 10min

    /**
     * 运行次数覆盖（-Dperf.runs=N）。LLM 增强模式单次全链路可达小时级，
     * 数据采集场景建议 -Dperf.runs=1 跑单次取完整阶段耗时。
     */
    private static final int RUNS_OVERRIDE = Integer.getInteger("perf.runs", 0);

    /**
     * 跳过达标断言（-Dperf.skip.threshold.check=true）。
     * LLM 增强模式耗时远超规则模式阈值属预期行为，采集数据时跳过断言以免测试失败。
     */
    private static final boolean SKIP_THRESHOLD_CHECK = Boolean.getBoolean("perf.skip.threshold.check");

    /** 运行环境快照 */
    private static final Map<String, String> envSnapshot = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        // 设置用户上下文（project.create 需要）
        com.traceguard.util.UserContext.set(1L, "benchmark-test", "admin");
        benchmarkRoot = resolveBenchmarkRoot();

        // 收集环境快照（仅一次）
        if (envSnapshot.isEmpty()) {
            envSnapshot.put("java.version", System.getProperty("java.version"));
            envSnapshot.put("java.vendor", System.getProperty("java.vendor"));
            envSnapshot.put("os.name", System.getProperty("os.name"));
            envSnapshot.put("os.arch", System.getProperty("os.arch"));
            envSnapshot.put("availableProcessors", String.valueOf(Runtime.getRuntime().availableProcessors()));
            envSnapshot.put("maxMemoryMB", String.valueOf(Runtime.getRuntime().maxMemory() / 1024 / 1024));
            // MySQL 版本通过连接获取（需数据库连接）
        }
    }

    /** 从多级候选路径定位 samples/benchmark（surefire 工作目录在不同模块/IDE 下可能不同） */
    private File resolveBenchmarkRoot() {
        String[] candidates = {
                "samples/benchmark",
                "../samples/benchmark",
                "../../samples/benchmark"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) {
                return f;
            }
        }
        throw new IllegalStateException("未找到基准工程 samples/benchmark（当前目录: " + System.getProperty("user.dir") + "）");
    }

    @Test
    @DisplayName("kilo 千行级基准（3次中位数）")
    void kiloBenchmark() throws Exception {
        runBenchmark("kilo", KILO_THRESHOLD_MS, 3);
    }

    @Test
    @DisplayName("tenk 万行级基准（3次中位数）")
    void tenkBenchmark() throws Exception {
        runBenchmark("tenk", TENK_THRESHOLD_MS, 3);
    }

    /**
     * 运行指定规模的基准测试。
     * @param scale 规模名（kilo/tenk）
     * @param thresholdMs 达标阈值（毫秒）
     * @param runs 运行次数（取中位数）
     */
    private void runBenchmark(String scale, long thresholdMs, int runs) throws Exception {
        // -Dperf.runs=N 可覆盖默认次数（LLM 数据采集模式建议 1）
        int effectiveRuns = RUNS_OVERRIDE > 0 ? RUNS_OVERRIDE : runs;
        File benchmarkDir = new File(benchmarkRoot, scale);
        assertTrue(benchmarkDir.exists() && benchmarkDir.isDirectory(),
                "基准工程不存在: " + benchmarkDir.getAbsolutePath());

        List<Map<String, Long>> allTimings = new ArrayList<>();
        List<Long> totalTimes = new ArrayList<>();

        for (int i = 0; i < effectiveRuns; i++) {
            // 创建项目记录
            Project project = createBenchmarkProject(scale, benchmarkDir);

            // 创建分析任务
            AnalysisTask task = analysisService.createTask(project.getId(), "GAP-008-benchmark-" + scale,
                    null, null, null, null, null);

            // 同步执行分析（不通过 @Async）
            analysisService.runAnalysis(task.getId());

            // 等待任务完成（默认 2 倍阈值超时；LLM 增强模式可用 -Dperf.wait.timeout.ms=... 放宽，见 FUN-06）
            long waitTimeoutMs = Long.getLong("perf.wait.timeout.ms", thresholdMs * 2);
            AnalysisTask completed = waitForTaskCompletion(task.getId(), waitTimeoutMs);
            assertNotNull(completed, "任务未在合理时间内完成（超时 " + (waitTimeoutMs / 1000) + "s，LLM 模式可用 -Dperf.wait.timeout.ms=... 放宽）");
            assertEquals("completed", completed.getStatus(), "任务未成功完成");

            // 提取阶段耗时
            Map<String, Long> timings = extractPhaseTimings(completed.getExecutionLog());
            allTimings.add(timings);
            totalTimes.add(timings.getOrDefault("total", 0L));

            // 清理（可选，保留数据供人工复查）
            // projectService.deleteProject(project.getId());
        }

        // 计算中位数
        Map<String, Long> medianTimings = calculateMedian(allTimings);
        long medianTotal = median(medianTimings.getOrDefault("total", 0L));
        boolean passed = medianTotal <= thresholdMs;

        // 输出结果到控制台（供 CI 查看）
        System.out.println("\n==================================================");
        System.out.println("基准测试完成: " + scale);
        System.out.println("达标阈值: " + (thresholdMs / 1000.0) + "s");
        System.out.println("中位总耗时: " + (medianTotal / 1000.0) + "s");
        System.out.println("达标结论: " + (passed ? "✅ 通过" : "❌ 未通过"));
        System.out.println("==================================================\n");

        // 生成报告
        generatePerformanceReport(scale, medianTimings, passed, thresholdMs);

        if (SKIP_THRESHOLD_CHECK) {
            System.out.println("[perf] 已跳过达标断言（-Dperf.skip.threshold.check=true，数据采集模式）");
            return;
        }
        assertTrue(passed, scale + " 规模未达标（中位耗时 " + (medianTotal / 1000.0) + "s > " + (thresholdMs / 1000.0) + "s）");
    }

    /**
     * 创建基准项目记录。
     */
    private Project createBenchmarkProject(String scale, File benchmarkDir) throws Exception {
        Project project = new Project();
        project.setProjectName("GAP-008-benchmark-" + scale);
        project.setDescription("性能基准测试工程（" + scale + "）");
        project.setCreateTime(LocalDateTime.now());

        // 设置需求文件路径
        File reqFile = new File(benchmarkDir, "requirements.txt");
        project.setRequirementFilePath(reqFile.getAbsolutePath());

        // 设置代码工程路径（解压后的目录）
        File codeDir = new File(benchmarkDir, "code");
        project.setCodeProjectPath(codeDir.getAbsolutePath());

        // 保存项目
        return projectService.create(project);
    }

    /**
     * 等待任务完成。
     */
    private AnalysisTask waitForTaskCompletion(Long taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            AnalysisTask task = analysisService.getTask(taskId);
            if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(1000);
        }
        return null; // 超时
    }

    /**
     * 从 execution_log 提取 [performance] 记录。
     */
    private Map<String, Long> extractPhaseTimings(String executionLog) {
        Map<String, Long> timings = new LinkedHashMap<>();
        Pattern pattern = Pattern.compile("\\[performance\\] \\{(.*?)\\}");
        Matcher matcher = pattern.matcher(executionLog);
        if (matcher.find()) {
            String perfStr = matcher.group(1);
            // 解析 {key=value, ...} 格式
            String[] pairs = perfStr.split(", ");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    try {
                        timings.put(kv[0], Long.parseLong(kv[1]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return timings;
    }

    /**
     * 计算中位数耗时。
     */
    private Map<String, Long> calculateMedian(List<Map<String, Long>> allTimings) {
        if (allTimings.isEmpty()) return Collections.emptyMap();
        Map<String, Long> median = new LinkedHashMap<>();
        for (String phase : allTimings.get(0).keySet()) {
            List<Long> values = new ArrayList<>();
            for (Map<String, Long> t : allTimings) {
                values.add(t.getOrDefault(phase, 0L));
            }
            Collections.sort(values);
            median.put(phase, values.get(values.size() / 2));
        }
        return median;
    }

    private long median(long value) {
        return value; // 中位数已在 calculateMedian 中处理
    }

    /**
     * 生成性能基准报告。
     */
    private void generatePerformanceReport(String scale, Map<String, Long> medianTimings, boolean passed, long thresholdMs) throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("# 性能基准测试报告\n\n");
        report.append("**生成时间**: ").append(LocalDateTime.now()).append("\n\n");
        report.append("## 1. 运行环境快照\n\n");
        report.append("| 参数 | 值 |\n");
        report.append("|---|---|\n");
        for (Map.Entry<String, String> e : envSnapshot.entrySet()) {
            report.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        report.append("\n");

        report.append("## 2. 测试规模\n\n");
        report.append("- **规模**: ").append(scale).append("\n");
        report.append("- **达标阈值**: ").append(thresholdMs / 1000.0).append("s\n");
        report.append("- **运行次数**: 3 次（取中位数）\n");
        // FUN-06：模式标注支持 -Dperf.mode.label=... 区分规则/增强模式，避免增强模式覆盖后误标为规则模式
        String modeLabel = System.getProperty("perf.mode.label", "规则模式（llm.enabled=false, embedding 跳过）");
        report.append("- **测试模式**: ").append(modeLabel).append("\n\n");

        report.append("## 3. 分阶段耗时（中位数）\n\n");
        report.append("| 阶段 | 耗时(ms) | 耗时(s) | 占比 |\n");
        report.append("|---|---|---|---|\n");
        long total = medianTimings.getOrDefault("total", 0L);
        for (Map.Entry<String, Long> e : medianTimings.entrySet()) {
            String phase = e.getKey();
            long ms = e.getValue();
            double seconds = ms / 1000.0;
            double percent = total > 0 ? (ms * 100.0 / total) : 0;
            report.append("| ").append(phase).append(" | ").append(ms).append(" | ")
                  .append(String.format("%.2f", seconds)).append(" | ")
                  .append(String.format("%.1f%%", percent)).append(" |\n");
        }
        report.append("\n");

        report.append("## 4. 达标结论\n\n");
        report.append("**中位总耗时**: ").append(total / 1000.0).append("s\n\n");
        if (passed) {
            report.append("✅ **达标**（低于阈值 ").append(thresholdMs / 1000.0).append("s）\n\n");
        } else {
            report.append("❌ **未达标**（超过阈值 ").append(thresholdMs / 1000.0).append("s）\n\n");
            report.append("**差距**: ").append((total - thresholdMs) / 1000.0).append("s\n\n");
        }

        report.append("## 5. 瓶颈分析 Top3\n\n");
        List<Map.Entry<String, Long>> phases = new ArrayList<>(medianTimings.entrySet());
        phases.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int top = Math.min(3, phases.size());
        for (int i = 0; i < top; i++) {
            Map.Entry<String, Long> e = phases.get(i);
            report.append("").append(i + 1).append(". ").append(e.getKey())
                  .append(": ").append(e.getValue() / 1000.0).append("s\n");
        }
        report.append("\n");

        report.append("## 6. 优化建议\n\n");
        if (phases.size() > 0) {
            String slowest = phases.get(0).getKey();
            report.append("最慢阶段: ").append(slowest).append("\n\n");
            if ("parseCode".equals(slowest)) {
                report.append("- 建议启用 GAP-025 代码解析并行化（迭代三）\n");
            } else if ("runConsistencyCheck".equals(slowest)) {
                report.append("- 建议启用 GAP-025 一致性计算分块缓存（迭代三）\n");
            } else if ("embedSemantics".equals(slowest)) {
                report.append("- 建议检查 Embedding 服务响应或考虑本地向量化（GAP-004）\n");
            }
        }
        report.append("\n");

        report.append("---\n**报告生成**: GAP-008 PerformanceTest\n");

        // 写入文件（报告输出到仓库根目录 docs/03-报告/，与工作目录无关）
        File reportDir = new File(benchmarkRoot.getParentFile().getParentFile(), "docs/03-报告");
        Files.createDirectories(reportDir.toPath());
        File reportFile = new File(reportDir, "性能基准报告-" + scale + ".md");
        Files.writeString(reportFile.toPath(), report.toString(), StandardCharsets.UTF_8);
        System.out.println("报告已生成: " + reportFile.getAbsolutePath());
    }
}
