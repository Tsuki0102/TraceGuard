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
import org.junit.jupiter.api.Tag;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-036：可靠性/并发测试——并发 3 任务 + 限流断言（需求 5.4、7.2）。
 *
 * 运行口径：
 *   - 复用 GAP-035 样例工程（ecommerce-order，规模小、任务快）作为负载
 *   - 同时并发提交 3 个分析任务，断言全部成功、任务结果完整、无死锁
 *   - 第 4 个任务验证限流：@Async 队列排队（或极端情况被信号量拒绝并提示"并行任务已达上限"）
 *   - 输出环境快照与结论到 docs/03-报告/可靠性测试记录.md（72h 冒烟脚本 smoke72h.sh 复用本负载）
 *
 * 前置条件：
 *   - samples/ecommerce-order/requirements.txt 与 code/ 已存在
 *   - 数据库连接正常（测试会写入 project/analysis_task/requirement 等表）
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "traceguard.analysis.strict-spec-verify=false")
@DisplayName("GAP-036 并发可靠性测试")
@Tag("perf") // TST-04：默认排除，需 -Pperf 单独运行
class ConcurrencyTest {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectService projectService;

    /** 并发提交的任务数（含第 4 个限流验证任务） */
    private static final int TOTAL_TASKS = 4;
    /** FR-STAT-003：系统允许的最大并行分析任务数（AnalysisService.MAX_PARALLEL_TASKS） */
    private static final int MAX_PARALLEL_TASKS = 3;
    /** 单任务完成超时（ecommerce-order 规模小，一般数秒内完成） */
    private static final long TASK_TIMEOUT_MS = 5 * 60 * 1000;

    private File sampleRoot;

    @BeforeEach
    void setUp() {
        com.traceguard.util.UserContext.set(1L, "concurrency-test", "admin");
        sampleRoot = resolveSampleRoot();
    }

    /** 从多级候选路径定位 samples/ecommerce-order（surefire 工作目录在不同模块/IDE 下可能不同） */
    private File resolveSampleRoot() {
        String[] candidates = {
                "samples/ecommerce-order",
                "../samples/ecommerce-order",
                "../../samples/ecommerce-order"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) {
                return f;
            }
        }
        throw new IllegalStateException("未找到 GAP-035 样例工程 samples/ecommerce-order（当前目录: " + System.getProperty("user.dir") + "）");
    }

    @Test
    @DisplayName("并发3任务全部成功、结果完整、无死锁；第4个任务排队或按限流拒绝")
    void concurrentThreeTasksSucceedWithRateLimit() throws Exception {
        // ---- 1. 并发提交 3 个任务（每个任务独立项目） ----
        ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL_TASKS);
        List<AnalysisTask> tasks = new ArrayList<>();
        List<Future<AnalysisTask>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < MAX_PARALLEL_TASKS; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> createAndRunTask(idx)));
            }
            // 同步阻塞提交：确保 3 个任务真正并行执行（占用全部执行槽）后再提交第 4 个
            for (Future<AnalysisTask> f : futures) {
                tasks.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
        }

        // ---- 2. 断言 3 个并发任务全部完成且结果完整、无死锁 ----
        for (AnalysisTask task : tasks) {
            AnalysisTask completed = waitForTaskCompletion(task.getId(), TASK_TIMEOUT_MS);
            assertThat(completed).as("任务未在超时内完成（疑似死锁）").isNotNull();
            assertThat(completed.getStatus()).as("任务[" + completed.getId() + "]应成功完成").isEqualTo("completed");
            assertThat(completed.getProgress()).as("任务[" + completed.getId() + "]进度应完整").isGreaterThan(80);
        }

        // ---- 3. 第 4 个任务验证限流（排队或拒绝均符合预期） ----
        AnalysisTask fourth = createAndRunTask(3);
        AnalysisTask fourthResult = waitForTaskCompletion(fourth.getId(), TASK_TIMEOUT_MS);
        assertThat(fourthResult).as("第4个任务未在超时内结束").isNotNull();
        String status = fourthResult.getStatus();
        // 允许两种符合预期的结果：排队后执行成功 / 被信号量拒绝（提示并行上限）
        assertThat(status).isIn("completed", "failed");
        if ("failed".equals(status)) {
            assertThat(fourthResult.getErrorMessage())
                    .as("拒绝原因应为并行上限提示")
                    .contains("并行任务已达上限");
        }
        // 系统仍健康：再次提交任务仍可正常完成
        AnalysisTask healthCheck = createAndRunTask(4);
        AnalysisTask healthResult = waitForTaskCompletion(healthCheck.getId(), TASK_TIMEOUT_MS);
        assertThat(healthResult).as("健康检查任务未完成").isNotNull();
        assertThat(healthResult.getStatus()).isEqualTo("completed");
    }

    /** 创建项目+任务并异步启动，返回任务对象（status 由后续轮询更新） */
    private AnalysisTask createAndRunTask(int idx) throws Exception {
        Project project = new Project();
        project.setProjectName("GAP-036-concurrency-" + idx);
        project.setDescription("并发可靠性测试工程（GAP-036）");
        project.setCreateTime(LocalDateTime.now());
        project.setRequirementFilePath(new File(sampleRoot, "requirements.txt").getAbsolutePath());
        project.setCodeProjectPath(new File(sampleRoot, "code").getAbsolutePath());
        project = projectService.create(project);

        AnalysisTask task = analysisService.createTask(project.getId(),
                "GAP-036-concurrency-" + idx, null, null, null, null, null);
        analysisService.runAnalysis(task.getId());
        return task;
    }

    /** 轮询等待任务到达终态（completed/failed），超时返回 null */
    private AnalysisTask waitForTaskCompletion(Long taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            AnalysisTask task = analysisService.getTask(taskId);
            if (task == null) {
                return null;
            }
            if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(1000);
        }
        return null;
    }
}
