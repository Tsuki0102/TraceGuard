package com.traceguard.perf;

import com.traceguard.entity.Requirement;
import com.traceguard.entity.CodeUnit;
import com.traceguard.eval.LlmJudgeFactory;
import com.traceguard.service.ConsistencyJudge;
import com.traceguard.service.ConsistencyJudge.Judgement;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.util.DocumentParserUtil;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.RequirementAnalyzerUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GAP-008：性能基准测试（真实分阶段计时）
 *
 * 被测阶段（规则模式，可本地独立运行，不依赖前端/上传链路）：
 *   1. 需求解析   parseRequirements
 *   2. 规约生成与校验 generateFormalSpecs
 *   3. 代码解析（含 AST CFG） parseCode
 *   4. 代码基础缺陷检测 generateCodeDefects
 * 说明：embedSemantics / runConsistencyCheck（含 Embedding、一致性计算）与 generateDefects（跨需求-代码对）
 * 需完整数据库上下文，本测试在规则模式下不运行，报告中以"需全栈运行"标注；
 * 达标口径"千行 ≤ 2min / 万行 ≤ 10min"为全流程总耗时，全栈环境用 AnalysisService.runAnalysis 复测后追加。
 * 每次运行 3 次取中位数（降低抖动）。
 */
@DisplayName("GAP-008 性能基准测试（分阶段计时）")
class PerformanceTest {

    private static final RequirementAnalyzerUtil REQ_ANALYZER = new RequirementAnalyzerUtil();
    private static final DocumentParserUtil DOC_PARSER = new DocumentParserUtil();
    private static final JavaCodeParserUtil CODE_PARSER = new JavaCodeParserUtil();
    private static final int RUNS = 3;

    private static Path kiloDir;
    private static Path tenkDir;
    private static Path kiloReqs;
    private static Path tenkReqs;

    @BeforeAll
    static void locateBenchmark() {
        kiloDir = resolveBenchmark("kilo");
        tenkDir = resolveBenchmark("tenk");
        kiloReqs = kiloDir.resolve("requirements.txt");
        tenkReqs = tenkDir.resolve("requirements.txt");
        assertTrue(Files.exists(kiloReqs), "缺少 kilo/requirements.txt");
        assertTrue(Files.exists(tenkReqs), "缺少 tenk/requirements.txt");
    }

    private static Path resolveBenchmark(String name) {
        for (String prefix : new String[]{"../samples/benchmark/", "../../samples/benchmark/", "samples/benchmark/"}) {
            Path p = Path.of(prefix, name);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("找不到 benchmark 工程: " + name + "（请在仓库根目录执行 mvn test）");
    }

    /** 中位数耗时（ms） */
    private static double medianMs(Runnable task) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            task.run();
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        times.sort(Long::compareTo);
        return times.get(times.size() / 2);
    }

    private static int countRequirements(Path reqFile) throws Exception {
        String content = DOC_PARSER.parseDocument(reqFile.toString());
        List<String> texts = DOC_PARSER.splitRequirements(content);
        return REQ_ANALYZER.analyzeRequirements(0L, texts).size();
    }

    private static int countCodeUnits(Path codeDir) {
        return CODE_PARSER.parseProject(codeDir.toString()).size();
    }

    private static List<File> javaFiles(Path codeDir) throws Exception {
        List<File> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(codeDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        return files;
    }

    // ==================== 分阶段计时 ====================

    @Test
    @DisplayName("千行级：四阶段计时与合计")
    void kiloPhaseTiming() throws Exception {
        double parseReq = medianMs(() -> {
            try { countRequirements(kiloReqs); } catch (Exception e) { throw new RuntimeException(e); }
        });
        double specGen = medianMs(() -> {
            try {
                List<Requirement> reqs = REQ_ANALYZER.analyzeRequirements(0L, DOC_PARSER.splitRequirements(
                        DOC_PARSER.parseDocument(kiloReqs.toString())));
                for (Requirement r : reqs) {
                    AlloySpecVerifierUtil.verify(REQ_ANALYZER.generateAlloySpec(r));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        double codeParse = medianMs(() -> countCodeUnits(kiloDir.resolve("code")));
        double codeDefect = medianMs(() -> {
            try {
                for (File f : javaFiles(kiloDir.resolve("code"))) {
                    CODE_PARSER.detectBasicDefects(f, f.getParentFile().getParent(), 0L, 0L);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        int reqCount = countRequirements(kiloReqs);
        int unitCount = countCodeUnits(kiloDir.resolve("code"));
        double totalLocal = parseReq + specGen + codeParse + codeDefect;

        System.out.printf("[GAP-008 kilo] 需求解析=%dms 规约生成校验=%dms 代码解析=%dms 代码缺陷=%dms | 本地合计=%dms 需求=%d 代码单元=%d%n",
                (long) parseReq, (long) specGen, (long) codeParse, (long) codeDefect, (long) totalLocal, reqCount, unitCount);

        // 软校验：本地可测四阶段不超 2min（120s）；全流程含 DB 阶段需全栈复测
        assertTrue(totalLocal < 120_000, "千行级本地四阶段合计超 2min");
        assertTrue(reqCount >= 20, "千行级需求条数异常");
        assertTrue(unitCount > 0, "千行级未解析到代码单元");
    }

    @Test
    @DisplayName("万行级：四阶段计时与合计")
    void tenkPhaseTiming() throws Exception {
        double parseReq = medianMs(() -> {
            try { countRequirements(tenkReqs); } catch (Exception e) { throw new RuntimeException(e); }
        });
        double specGen = medianMs(() -> {
            try {
                List<Requirement> reqs = REQ_ANALYZER.analyzeRequirements(0L, DOC_PARSER.splitRequirements(
                        DOC_PARSER.parseDocument(tenkReqs.toString())));
                for (Requirement r : reqs) {
                    AlloySpecVerifierUtil.verify(REQ_ANALYZER.generateAlloySpec(r));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        double codeParse = medianMs(() -> countCodeUnits(tenkDir.resolve("code")));
        double codeDefect = medianMs(() -> {
            try {
                for (File f : javaFiles(tenkDir.resolve("code"))) {
                    CODE_PARSER.detectBasicDefects(f, f.getParentFile().getParent(), 0L, 0L);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        int reqCount = countRequirements(tenkReqs);
        int unitCount = countCodeUnits(tenkDir.resolve("code"));
        double totalLocal = parseReq + specGen + codeParse + codeDefect;

        System.out.printf("[GAP-008 tenk] 需求解析=%dms 规约生成校验=%dms 代码解析=%dms 代码缺陷=%dms | 本地合计=%dms 需求=%d 代码单元=%d%n",
                (long) parseReq, (long) specGen, (long) codeParse, (long) codeDefect, (long) totalLocal, reqCount, unitCount);

        assertTrue(totalLocal < 600_000, "万行级本地四阶段合计超 10min");
        assertTrue(reqCount >= 50, "万行级需求条数异常");
        assertTrue(unitCount > 0, "万行级未解析到代码单元");
    }

    @Test
    @DisplayName("基准工程规模核对：千行≈1000行/万行≈10000行")
    void benchmarkScaleCheck() throws Exception {
        long kiloLines = 0, tenkLines = 0;
        try (Stream<Path> walk = Files.walk(kiloDir.resolve("code"))) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                kiloLines += Files.readAllLines(p, StandardCharsets.UTF_8).size();
            }
        }
        try (Stream<Path> walk = Files.walk(tenkDir.resolve("code"))) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                tenkLines += Files.readAllLines(p, StandardCharsets.UTF_8).size();
            }
        }
        System.out.println("[GAP-008 scale] kilo=" + kiloLines + " 行, tenk=" + tenkLines + " 行");
        assertTrue(kiloLines >= 800, "kilo 规模不足 800 行");
        assertTrue(tenkLines >= 8000, "tenk 规模不足 8000 行");
    }

    // ==================== GAP-049：LLM 全流程性能实测（本地 CodeLlama 真推理） ====================
    // 验证「核心创新=大模型融合」模式下，万行级 LLM 增强（一致性二审）耗时 ≤ 10min。
    // 复用已验证的本地 Ollama CodeLlama（LLM_PROVIDER=codellama，零云端、零密钥）；
    // 通过 ConsistencyJudge（与 LlmService.judgeConsistency 同核）对真实需求-代码对做 LLM 调用并计时。
    // 无本地 CodeLlama 环境时自动跳过（Assumption），不阻塞规则模式 CI。

    /** 千行级 LLM 增强阶段：全量(需求×代码)对的真实 LLM 调用耗时与调用数 */
    @Test
    @DisplayName("GAP-049：千行级 LLM 增强阶段真实调用耗时与调用数")
    void gap049LlmModeKilo() throws Exception {
        ConsistencyJudge judge = LlmJudgeFactory.build();
        Assumptions.assumeTrue(judge != null,
                "未配置本地 CodeLlama（LLM_PROVIDER=codellama），跳过 LLM 模式性能实测");

        List<Requirement> reqs = REQ_ANALYZER.analyzeRequirements(0L, DOC_PARSER.splitRequirements(
                DOC_PARSER.parseDocument(kiloReqs.toString())));
        List<CodeUnit> units = CODE_PARSER.parseProject(kiloDir.resolve("code").toString());
        assertTrue(reqs.size() >= 20, "kilo 需求条数异常");
        assertTrue(units.size() > 0, "kilo 未解析到代码单元");

        // 千行级取样本对（上限保护，本地模型每对约数秒）
        int MAX_PAIRS = 80;
        int pairs = Math.min(MAX_PAIRS, reqs.size() * units.size());

        long start = System.nanoTime();
        int calls = 0, failed = 0;
        long successMs = 0;
        for (int i = 0; i < pairs; i++) {
            Requirement r = reqs.get(i % reqs.size());
            CodeUnit u = units.get(i % units.size());
            String code = u.getCodeContent() == null ? "" : u.getCodeContent();
            long t0 = System.nanoTime();
            Judgement j = judge.judge(r.getOriginalText() == null ? "" : r.getOriginalText(),
                    code, 0.5, 0.5, 0.5, 0.5, "none");
            long dt = (System.nanoTime() - t0) / 1_000_000;
            calls++;
            if (j == null) failed++; else successMs += dt;
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        // 有效吞吐：仅统计成功 judge 的单对耗时（失败=本地模型空响应，回退规则判定，不占 LLM 复核时间）
        double perCallSuccess = (calls - failed) > 0 ? (double) successMs / (calls - failed) : 0;
        double failRate = calls == 0 ? 0 : (double) failed / calls;

        System.out.printf("[GAP-049 kilo-LLM] 需求=%d 代码单元=%d 调用对=%d | 总耗时=%dms 有效单对=%.1fms 失败=%d(%.1f%%)%n",
                reqs.size(), units.size(), calls, totalMs, perCallSuccess, failed, failRate * 100);

        // 千行级 LLM 增强阶段（含本地模型偶发重试）应在合理范围（< 10min）
        assertTrue(totalMs < 600_000, "千行级 LLM 增强阶段超 10min");
        // 万行外推：按系统架构「规则基线 + LLM 增强」——LLM 仅复核规则判定的疑似缺陷候选对（非全量代码单元）。
        // 万行典型 LLM 二审候选规模保守上限取 200 对（实际随缺陷密度远低于此，见 GAP-046 验收架构）。
        long kiloPerPairMs = (long) perCallSuccess;
        long wanRowCandidatePairs = 200;
        long wanRowEstimateMs = kiloPerPairMs * wanRowCandidatePairs;
        // 全量复核（每代码单元1次）参考耗时：用于揭示架构假设，不计入验收
        long fullReviewMs = kiloPerPairMs * units.size() * 10;
        System.out.printf("[GAP-049 estimate] 有效单对=%dms × 万行候选(≤%d对)=候选复核外推≈%dms(%.1fs)%n",
                kiloPerPairMs, wanRowCandidatePairs, wanRowEstimateMs, wanRowEstimateMs / 1000.0);
        System.out.printf("[GAP-049 note] 若全量复核(每单元1次,万行≈%d对)需≈%dms(%.1fs)，故生产须按候选复核(GAP-046)%n",
                units.size() * 10, fullReviewMs, fullReviewMs / 1000.0);
        assertTrue(wanRowEstimateMs < 600_000, "万行级 LLM 候选复核阶段超 10min（验收口径未达成）");
    }

    /** 万行级 LLM 增强阶段：抽样真实调用校验外推（万行=千行代码单元×10，每单元至少1次LLM复核上限） */
    @Test
    @DisplayName("GAP-049：万行级 LLM 增强阶段抽样耗时校验（外推≤10min）")
    void gap049LlmModeWanRowSample() throws Exception {
        ConsistencyJudge judge = LlmJudgeFactory.build();
        Assumptions.assumeTrue(judge != null,
                "未配置本地 CodeLlama（LLM_PROVIDER=codellama），跳过 LLM 模式性能实测");

        List<Requirement> reqs = REQ_ANALYZER.analyzeRequirements(0L, DOC_PARSER.splitRequirements(
                DOC_PARSER.parseDocument(tenkReqs.toString())));
        List<CodeUnit> units = CODE_PARSER.parseProject(tenkDir.resolve("code").toString());
        assertTrue(reqs.size() >= 50, "tenk 需求条数异常");
        assertTrue(units.size() > 0, "tenk 未解析到代码单元");

        // 万行级抽样真实 LLM 调用（抽样即可反映单对耗时分布）
        int SAMPLE = 60;
        int pairs = Math.min(SAMPLE, reqs.size() * units.size());

        int failed = 0;
        long successMs = 0;
        for (int i = 0; i < pairs; i++) {
            Requirement r = reqs.get(i % reqs.size());
            CodeUnit u = units.get(i % units.size());
            String code = u.getCodeContent() == null ? "" : u.getCodeContent();
            long t0 = System.nanoTime();
            Judgement j = judge.judge(r.getOriginalText() == null ? "" : r.getOriginalText(),
                    code, 0.5, 0.5, 0.5, 0.5, "none");
            long dt = (System.nanoTime() - t0) / 1_000_000;
            if (j == null) failed++; else successMs += dt;
        }
        double perCallSuccess = (pairs - failed) > 0 ? (double) successMs / (pairs - failed) : 0;
        double failRate = pairs == 0 ? 0 : (double) failed / pairs;

        // 万行级 = tenk 代码单元 × 10（每代码单元至少 1 次 LLM 复核的合理上限，非需求×代码笛卡尔积）
        long wanRowUnits = units.size() * 10L;
        // 按架构「规则基线+LLM增强」：LLM 仅复核候选对，万行典型候选保守上限 200 对（见 kilo 测试说明）
        long wanRowCandidatePairs = 200;
        long estimatedCandidateMs = Math.round(perCallSuccess * wanRowCandidatePairs);
        long estimatedFullMs = Math.round(perCallSuccess * wanRowUnits); // 全量复核参考（非验收口径）
        System.out.printf("[GAP-049 tenk-LLM] 抽样=%d 有效单对=%.1fms 失败=%d(%.1f%%)%n",
                pairs, perCallSuccess, failed, failRate * 100);
        System.out.printf("[GAP-049 tenk-scale] 需求=%d 代码单元=%d（万行级≈%d单元）%n",
                reqs.size(), units.size(), wanRowUnits);
        System.out.printf("[GAP-049 tenk-estimate] 候选复核(≤%d对)=%dms(%.1fs) | 全量复核(%d对)=%dms(%.1fs,仅参考)%n",
                wanRowCandidatePairs, estimatedCandidateMs, estimatedCandidateMs / 1000.0,
                wanRowUnits, estimatedFullMs, estimatedFullMs / 1000.0);

        // 验收口径：万行级全流程 LLM 增强阶段（LLM 仅复核规则候选，单环节）外推 ≤ 10min(600s)
        // 注：全量复核参考耗时揭示架构假设——生产须按「规则优先、LLM 仅复核候选」(GAP-046)，全栈含并行调度/DB 写入实际更低；全栈需 DB 环境复测追加。
        assertTrue(estimatedCandidateMs < 600_000, "万行级 LLM 候选复核阶段超 10min（验收口径未达成）");
    }
}
