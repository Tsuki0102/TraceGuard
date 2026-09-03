package com.traceguard.service;

import cn.hutool.core.util.IdUtil;
import com.traceguard.util.SecureZipUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.traceguard.common.BusinessException;
import com.traceguard.core.ConsistencyChecker;
import com.traceguard.entity.*;
import com.traceguard.spi.*;
import com.traceguard.mapper.*;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.util.CodeLogicDescriber;
import com.traceguard.util.DocumentParserUtil;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.IncrementalDiff;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.RequirementAnalyzerUtil;
import com.traceguard.util.SemanticVectorUtil;
import com.traceguard.util.SootCfgBuilderUtil;
import com.traceguard.util.TaskBreakerHolder;
import com.traceguard.util.TaskCircuitBreaker;
import org.apache.ibatis.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisService.class);

    /** GAP-002：verification_detail JSON 序列化器（DB 文本，数值保持数值型） */
    private static final com.fasterxml.jackson.databind.ObjectMapper VERIFICATION_DETAIL_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 任务运行状态：0=运行中 1=暂停请求 2=终止请求 */
    private static final int CTRL_RUNNING = 0;
    private static final int CTRL_PAUSE = 1;
    private static final int CTRL_TERMINATE = 2;

    /** 最大并行分析任务数（FR-STAT-003：并行分析任务不超过3个） */
    private static final int MAX_PARALLEL_TASKS = 3;

    /** 单个需求文档大小上限（FR-REQ-001：单个文档不超过50MB，覆盖直传/批量/分片合并全部路径） */
    static final long MAX_REQUIREMENT_FILE_SIZE = 50L * 1024 * 1024;

    /** 单个 Java 源文件大小上限（FR-CODE-001 2.4：单文件批量导入，5MB 足够覆盖大文件类） */
    static final long MAX_CODE_FILE_SIZE = 5L * 1024 * 1024;

    /** 运行中任务的协作式控制标记（暂停/终止），任务结束后移除 */
    private final Map<Long, Integer> taskControls = new ConcurrentHashMap<>();

    /** 并行任务限流信号量：同一时刻最多3个分析任务真正执行，超限任务立即拒绝 */
    private final Semaphore analysisSlots = new Semaphore(MAX_PARALLEL_TASKS);

    /** FUN-10：任务是否持有并行执行槽——暂停时释放、恢复时重新申请，避免暂停任务占满额度阻塞新任务 */
    private final Map<Long, Boolean> slotHeld = new ConcurrentHashMap<>();

    /** GAP-025：批量写库每批行数（traceguard.perf.batch-size，默认500） */
    @Value("${traceguard.perf.batch-size:500}")
    private int batchSize;

    /** 任务被用户终止的内部信号 */
    private static class TaskTerminatedException extends RuntimeException {
        TaskTerminatedException() {
            super("任务已被用户终止");
        }
    }

    @Autowired
    private AnalysisTaskMapper taskMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private RequirementMapper requirementMapper;

    @Autowired
    private FormalSpecificationMapper specMapper;

    @Autowired
    private CodeUnitMapper codeUnitMapper;

    @Autowired
    private CodeDefectMapper codeDefectMapper;

    @Autowired
    private ConsistencyResultMapper consistencyMapper;

    @Autowired
    private DefectMapper defectMapper;

    @Autowired
    private FileStorageUtil fileStorageUtil;

    @Autowired
    private DocumentParserUtil documentParserUtil;

    @Autowired
    private RequirementAnalyzerUtil requirementAnalyzerUtil;

    @Autowired
    private JavaCodeParserUtil javaCodeParserUtil;

    @Autowired
    private ConsistencyChecker consistencyChecker;

    /** AUD-10：可扩展代码/规约实现注册表（默认 java/alloy 即现有链路） */
    @Autowired
    private ParserRegistry parserRegistry;
    @Autowired
    private SpecRegistry specRegistry;
    /** AUD-10：可配置的语言选择（默认 java/alloy，与改造前行为一致） */
    @Value("${traceguard.analysis.code-language:java}")
    private String codeLanguage;
    @Value("${traceguard.analysis.spec-language:alloy}")
    private String specLanguage;

    @Autowired
    private com.traceguard.websocket.ProgressWebSocketHandler progressWsHandler;

    @Autowired
    private ResultService resultService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private LlmService llmService;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    @Autowired
    private CodeLogicDescriber codeLogicDescriber;

    @Value("${traceguard.storage.upload-path:./uploads/}")
    private String uploadPath;

    /** FR-REQ-004：形式化规约校验失败是否阻断主分析流程（GAP-013：默认开启） */
    @Value("${traceguard.analysis.strict-spec-verify:true}")
    private boolean strictSpecVerify;

    /** P2-5：代码增量解析开关（默认开启；关闭时回退"已有结果即断点续跑复用"历史行为） */
    @Value("${traceguard.analysis.incremental-code:true}")
    private boolean incrementalCodeEnabled = true;

    public AnalysisTask createTask(Long projectId, String taskName,
                                    Double alpha, Double beta, Double gamma,
                                    Double t1, Double t2) {
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(projectId);
        task.setTaskName(taskName);
        task.setStatus("pending");
        task.setProgress(0);
        task.setStartTime(LocalDateTime.now());
        // B2 标定统一（2026-09-03）：α/β/γ=0.5/0.2/0.3、t1=0.52（A2 去共线性 + 风险门控双通道标定，
        // 规则链 acc 80.0%/fpr 6.9%）；t2 仅影响一般/严重分级
        task.setWeightAlpha(alpha != null ? alpha : 0.5);
        task.setWeightBeta(beta != null ? beta : 0.2);
        task.setWeightGamma(gamma != null ? gamma : 0.3);
        task.setThresholdT1(t1 != null ? t1 : 0.52);
        task.setThresholdT2(t2 != null ? t2 : 0.5);
        validateWeights(task);
        taskMapper.insert(task);
        return task;
    }

    /**
     * 校验任务参数（FR-CHECK-002）：权重 α+β+γ=1，阈值 T1>T2，各值均在 [0,1] 区间
     */
    private void validateWeights(AnalysisTask task) {
        Double a = task.getWeightAlpha();
        Double b = task.getWeightBeta();
        Double g = task.getWeightGamma();
        Double t1 = task.getThresholdT1();
        Double t2 = task.getThresholdT2();
        for (Double v : new Double[]{a, b, g, t1, t2}) {
            if (v == null || v < 0 || v > 1) {
                throw new BusinessException(400, "权重与阈值必须在 0~1 范围内");
            }
        }
        if (Math.abs(a + b + g - 1.0) > 0.01) {
            throw new BusinessException(400, "权重之和必须等于 1（当前 α+β+γ=" +
                    String.format("%.2f", a + b + g) + "）");
        }
        if (t1 <= t2) {
            throw new BusinessException(400, "阈值 T1（完全一致）必须大于 T2（严重不一致）");
        }
    }

    /** 重新分析：复用原任务的权重/阈值配置创建新任务并立即执行（FR-PLAT-002 结果复现/重新分析） */
    public AnalysisTask rerunTask(Long taskId) {
        AnalysisTask old = taskMapper.selectById(taskId);
        if (old == null) {
            throw new BusinessException(404, "原任务不存在");
        }
        Project project = projectMapper.selectById(old.getProjectId());
        if (project == null || project.getRequirementFilePath() == null || project.getCodeProjectPath() == null) {
            throw new BusinessException(400, "项目需求文档或代码工程缺失，无法重新分析");
        }
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(old.getProjectId());
        task.setTaskName((old.getTaskName() != null ? old.getTaskName() : "分析任务") + "-重新分析");
        task.setStatus("pending");
        task.setProgress(0);
        task.setStartTime(LocalDateTime.now());
        task.setWeightAlpha(old.getWeightAlpha() != null ? old.getWeightAlpha() : 0.4);
        task.setWeightBeta(old.getWeightBeta() != null ? old.getWeightBeta() : 0.35);
        task.setWeightGamma(old.getWeightGamma() != null ? old.getWeightGamma() : 0.25);
        task.setThresholdT1(old.getThresholdT1() != null ? old.getThresholdT1() : 0.8);
        task.setThresholdT2(old.getThresholdT2() != null ? old.getThresholdT2() : 0.5);
        validateWeights(task);
        taskMapper.insert(task);
        return task;
    }

    /** 校验单文档大小上限，超限抛业务异常（分片合并产物同样校验，防绕过全局multipart限制） */
    static void validateFileSize(long size, String filename) {
        if (size > MAX_REQUIREMENT_FILE_SIZE) {
            throw new BusinessException(400, "需求文档[" + filename + "]超过50MB上限（当前"
                    + String.format("%.1f", size / 1024.0 / 1024.0) + "MB），请拆分文档后重新上传");
        }
    }

    /** FR-CODE-001 2.4：单 Java 源文件大小上限校验 */
    static void validateCodeFileSize(long size, String filename) {
        if (size > MAX_CODE_FILE_SIZE) {
            throw new BusinessException(400, "Java 文件[" + filename + "]超过5MB上限（当前"
                    + String.format("%.1f", size / 1024.0 / 1024.0) + "MB）");
        }
    }

    public String uploadRequirement(MultipartFile file, Long projectId) throws Exception {
        validateFileSize(file.getSize(), file.getOriginalFilename());
        // GAP-031：上传前按魔数校验真实类型（防止伪扩展名）
        FileStorageUtil.validateFileType(file);
        invalidateRequirementArtifacts(projectId);
        String filePath = fileStorageUtil.saveFile(file, "requirements/" + projectId);
        projectService.checkStorageQuota(projectId);
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setRequirementFilePath(filePath);
            projectMapper.updateById(project);
        }
        return filePath;
    }

    /** 批量导入需求文档（FR-REQ-001 多文档批量导入），路径以逗号分隔存储 */
    public String uploadRequirements(MultipartFile[] files, Long projectId) {
        if (files == null || files.length == 0) {
            throw new BusinessException(400, "请至少选择一个需求文档");
        }
        // 先整体校验再作废旧产物，避免部分文件已保存后才因超限失败
        for (MultipartFile file : files) {
            validateFileSize(file.getSize(), file.getOriginalFilename());
            // GAP-031：上传前按魔数校验真实类型（防止伪扩展名）
            try {
                FileStorageUtil.validateFileType(file);
            } catch (IOException e) {
                throw new BusinessException(400, "需求文档校验失败：" + file.getOriginalFilename() + " - " + e.getMessage());
            }
        }
        invalidateRequirementArtifacts(projectId);
        StringBuilder paths = new StringBuilder();
        for (MultipartFile file : files) {
            String filePath;
            try {
                filePath = fileStorageUtil.saveFile(file, "requirements/" + projectId);
            } catch (IOException e) {
                LOGGER.error("保存需求文档失败: projectId={}, file={}, error={}", projectId, file.getOriginalFilename(), e.getMessage(), e);
                throw new BusinessException(500, "保存需求文档失败：" + file.getOriginalFilename() + " - " + e.getMessage());
            }
            if (paths.length() > 0) paths.append(",");
            paths.append(filePath);
        }
        projectService.checkStorageQuota(projectId);
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setRequirementFilePath(paths.toString());
            projectMapper.updateById(project);
        }
        return paths.toString();
    }

    public String uploadCodeProject(MultipartFile file, Long projectId) {
        // GAP-031：上传前按魔数校验真实类型（代码工程须为 ZIP）
        try {
            FileStorageUtil.validateFileType(file);
        } catch (IOException e) {
            throw new BusinessException(400, "代码工程校验失败：" + file.getOriginalFilename() + " - " + e.getMessage());
        }
        invalidateCodeArtifacts(projectId);
        String zipPath;
        try {
            zipPath = fileStorageUtil.saveFile(file, "code/" + projectId);
        } catch (IOException e) {
            LOGGER.error("保存代码工程文件失败: projectId={}, fileName={}, error={}", projectId, file.getOriginalFilename(), e.getMessage(), e);
            throw new BusinessException(500, "保存代码工程文件失败: " + e.getMessage());
        }
        projectService.checkStorageQuota(projectId);
        return unzipCodeProject(zipPath, projectId);
    }

    /** 解压已加密落盘的代码压缩包到独立目录并回写项目（解密临时文件用完即删，不留明文） */
    private String unzipCodeProject(String zipPath, Long projectId) {
        String extractDir = uploadPath + "code/" + projectId + "/extracted_" + IdUtil.simpleUUID();
        File dir = new File(extractDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BusinessException(500, "无法创建解压目录: " + extractDir);
        }
        File encrypted = new File(zipPath);
        if (!encrypted.exists()) {
            throw new BusinessException(500, "加密 ZIP 文件不存在: " + zipPath);
        }
        File plainZip = null;
        try {
            plainZip = fileStorageUtil.ensurePlainFile(zipPath);
            LOGGER.info("代码工程解密成功, encrypted={}, plain={}, size={}",
                    encrypted.getAbsolutePath(), plainZip.getAbsolutePath(), plainZip.length());
            // GAP-031: 使用安全的 ZIP 解压工具（路径穿越防护、压缩炸弹防护、魔数校验）
            SecureZipUtil.safeUnzip(plainZip, dir);
        } catch (IOException e) {
            LOGGER.error("ZIP 解压失败: encryptedPath={}, plainPath={}, destDir={}, error={}",
                    encrypted.getAbsolutePath(),
                    plainZip == null ? "null" : plainZip.getAbsolutePath(),
                    dir.getAbsolutePath(), e.getMessage(), e);
            throw new BusinessException(500, "代码工程 ZIP 解压失败: " + e.getMessage());
        } finally {
            if (plainZip != null) {
                fileStorageUtil.cleanupPlainFile(plainZip, encrypted);
            }
        }
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setCodeProjectPath(extractDir);
            projectMapper.updateById(project);
        }
        return extractDir;
    }

    /**
     * 需求文档变更时失效旧解析产物：需求条目与形式化规约将随下次分析重新生成
     * （断点续跑的前提是产物可信，文件一变旧产物即作废）
     */
    private void invalidateRequirementArtifacts(Long projectId) {
        requirementMapper.delete(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, projectId));
        specMapper.delete(new LambdaQueryWrapper<FormalSpecification>()
                .eq(FormalSpecification::getProjectId, projectId));
    }

    /**
     * 代码工程变更时失效下游结果（基础缺陷/一致性/缺陷）。
     * P2-5（2026-09-02）：不再删除 code_unit 行——保留旧解析结果作为"增量解析哈希快照"，
     * 由 parseCode 在下次分析时按文件内容哈希 diff，仅重解析变更/新增文件、清理已移除文件；
     * 这样重复上传小改动代码时二次分析只重算受影响文件，避免全量 Soot/AST/向量化重跑。
     */
    private void invalidateCodeArtifacts(Long projectId) {
        codeDefectMapper.delete(new LambdaQueryWrapper<CodeDefect>()
                .eq(CodeDefect::getProjectId, projectId));
        consistencyMapper.delete(new LambdaQueryWrapper<ConsistencyResult>()
                .eq(ConsistencyResult::getProjectId, projectId));
        defectMapper.delete(new LambdaQueryWrapper<Defect>()
                .eq(Defect::getProjectId, projectId));
    }

    /** 分片合并后的需求文档落库（与 uploadRequirement 等价，接收服务器本地已合并文件） */
    public String saveMergedRequirement(File merged, String originalName, Long projectId) throws Exception {
        // 分片上传可绕过全局multipart限制，合并产物必须补单文档50MB校验
        validateFileSize(merged.length(), originalName);
        // GAP-031：分片合并产物同样补魔数校验
        FileStorageUtil.validateFileType(merged, originalName);
        invalidateRequirementArtifacts(projectId);
        String filePath = fileStorageUtil.saveFile(merged, originalName, "requirements/" + projectId);
        projectService.checkStorageQuota(projectId);
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setRequirementFilePath(filePath);
            projectMapper.updateById(project);
        }
        return filePath;
    }

    /** 分片合并后的代码工程落库（与 uploadCodeProject 等价，接收服务器本地已合并文件） */
    public String saveMergedCodeProject(File merged, String originalName, Long projectId) throws Exception {
        // GAP-031：分片合并代码压缩包补魔数校验
        FileStorageUtil.validateFileType(merged, originalName);
        invalidateCodeArtifacts(projectId);
        String zipPath = fileStorageUtil.saveFile(merged, originalName, "code/" + projectId);
        projectService.checkStorageQuota(projectId);
        return unzipCodeProject(zipPath, projectId);
    }

    /**
     * FR-CODE-001 规则4（2.4 整改项）：单 Java 文件批量导入入口。
     * 适用于零散 .java 源文件场景：多选批量上传，保存到项目独立代码目录（明文，供解析），
     * 更新 project.codeProjectPath 并失效旧代码产物。与 ZIP 代码工程上传互为补充。
     */
    public String uploadCodeFiles(MultipartFile[] files, Long projectId) {
        if (files == null || files.length == 0) {
            throw new BusinessException(400, "请至少选择一个 Java 文件");
        }
        List<MultipartFile> validFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String cleanName = new File(name).getName();
            if (!cleanName.toLowerCase().endsWith(".java")) {
                throw new BusinessException(400, "仅支持 .java 源文件：" + name);
            }
            if (file.isEmpty()) {
                continue;
            }
            // SEC-09：与 ZIP/需求文档链路统一魔数校验（文本检测），防伪造 .java 扩展名上传二进制文件
            try {
                FileStorageUtil.validateFileType(file);
            } catch (IOException e) {
                throw new BusinessException(400, "Java 源文件校验失败：" + name + " - " + e.getMessage());
            }
            validateCodeFileSize(file.getSize(), cleanName);
            validFiles.add(file);
        }
        if (validFiles.isEmpty()) {
            throw new BusinessException(400, "未选择有效的 Java 文件");
        }
        invalidateCodeArtifacts(projectId);
        String dir = uploadPath + "code/" + projectId + "/single_" + IdUtil.simpleUUID();
        File targetDir = new File(dir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new BusinessException(500, "代码目录创建失败：" + dir);
        }
        for (MultipartFile file : validFiles) {
            String cleanName = new File(file.getOriginalFilename()).getName();
            File target = uniqueFile(targetDir, cleanName);
            try (InputStream in = file.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            } catch (IOException e) {
                LOGGER.error("保存 Java 源文件失败: projectId={}, file={}, error={}", projectId, cleanName, e.getMessage(), e);
                throw new BusinessException(500, "保存 Java 源文件失败：" + cleanName + " - " + e.getMessage());
            }
        }
        projectService.checkStorageQuota(projectId);
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setCodeProjectPath(targetDir.getAbsolutePath());
            projectMapper.updateById(project);
        }
        return targetDir.getAbsolutePath();
    }

    /** 目标文件已存在时追加 _1/_2 序号避免覆盖（同名多目录文件场景） */
    private File uniqueFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            File candidate = new File(dir, base + "_" + i + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    public AnalysisTask getTask(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    @Async
    public void runAnalysis(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        StringBuilder log = new StringBuilder();
        taskControls.put(taskId, CTRL_RUNNING);
        slotHeld.put(taskId, false);
        // GAP-008：阶段计时埋点（六阶段 + 总计）
        Map<String, Long> phaseTimings = new LinkedHashMap<>();
        long overallStart = System.currentTimeMillis();
        try {
            // 并行限制（FR-STAT-003）：非阻塞抢占执行槽，最多3个任务同时分析
            boolean acquired;
            try {
                acquired = analysisSlots.tryAcquire(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                acquired = analysisSlots.tryAcquire();
            }
            if (!acquired) {
                task.setStatus("failed");
                task.setCurrentStep("并行任务已达上限");
                task.setErrorMessage("当前并行分析任务已达上限（" + MAX_PARALLEL_TASKS + " 个），请等待现有任务完成后再试");
                log.append("[").append(LocalDateTime.now()).append("] 任务未启动: 当前并行分析任务已达上限（")
                        .append(MAX_PARALLEL_TASKS).append(" 个）\n");
                task.setExecutionLog(log.toString());
                updateAndPush(task);
                LOGGER.warn("分析任务[{}]被拒绝：并行任务已达上限", taskId);
                return;
            }
            slotHeld.put(taskId, true); // 已持有执行槽（暂停时释放/恢复时重申请，FUN-10）
            task.setStatus("running");
            task.setProgress(5);
            task.setCurrentStep("初始化分析任务");
            task.setExecutionLog("开始执行分析任务...\n");
            updateAndPush(task);
            Project project = projectMapper.selectById(task.getProjectId());
            if (project == null) {
                throw new Exception("项目不存在");
            }
            log.append("[").append(LocalDateTime.now()).append("] 项目: ").append(project.getProjectName()).append("\n");
            task.setProgress(15);
            task.setCurrentStep("解析需求文档");
            task.setExecutionLog(log.toString());
            updateAndPush(task);
            // GAP-001：任务级 LLM 用量统计（阶段 -> calls/success/failed/degraded）
            LlmUsage llmUsage = new LlmUsage();
            long phaseStart = System.currentTimeMillis();
            List<Requirement> requirements = parseRequirements(task, project, log, llmUsage);
            phaseTimings.put("parseRequirements", System.currentTimeMillis() - phaseStart);
            task.setProgress(30);
            task.setCurrentStep("生成形式化规约");
            updateAndPush(task);
            phaseStart = System.currentTimeMillis();
            generateFormalSpecs(task, requirements, log, llmUsage);
            phaseTimings.put("generateFormalSpecs", System.currentTimeMillis() - phaseStart);
            task.setProgress(45);
            task.setCurrentStep("解析Java代码");
            updateAndPush(task);
            phaseStart = System.currentTimeMillis();
            List<CodeUnit> codeUnits = parseCode(task, project, log, llmUsage);
            phaseTimings.put("parseCode", System.currentTimeMillis() - phaseStart);
            // GAP-004：语义向量化阶段（parseCode 之后、runConsistencyCheck 之前，进度 60%）
            task.setProgress(60);
            task.setCurrentStep("语义向量化");
            updateAndPush(task);
            phaseStart = System.currentTimeMillis();
            embedSemantics(task, requirements, codeUnits, log);
            phaseTimings.put("embedSemantics", System.currentTimeMillis() - phaseStart);
            task.setProgress(70);
            task.setCurrentStep("执行一致性校验");
            updateAndPush(task);
            phaseStart = System.currentTimeMillis();
            List<ConsistencyResult> results = runConsistencyCheck(task, project, requirements, codeUnits, log);
            phaseTimings.put("runConsistencyCheck", System.currentTimeMillis() - phaseStart);
            task.setProgress(80);
            task.setCurrentStep("生成缺陷报告");
            updateAndPush(task);
            phaseStart = System.currentTimeMillis();
            generateDefects(task, project, results, requirements, codeUnits, log, llmUsage);
            phaseTimings.put("generateDefects", System.currentTimeMillis() - phaseStart);
            // GAP-001 步骤 6：用量统计汇总写入 execution_log（追加 llm-usage 记录）
            if (!llmUsage.isEmpty()) {
                log.append("[").append(LocalDateTime.now()).append("] [llm-usage] ").append(llmUsage.toJson()).append("\n");
                task.setExecutionLog(log.toString());
                taskMapper.updateById(task);
            }
            // 统计汇总
            task.setProgress(90);
            task.setCurrentStep("统计汇总");
            updateAndPush(task);
            // GAP-008：记录总耗时
            phaseTimings.put("total", System.currentTimeMillis() - overallStart);
            // 收尾统计须在任务标记completed并推送之前完成落库与缓存清除，
            // 避免前端收到completed后立即查询仍读到旧的项目统计
            updateProjectStats(project.getId(), task.getId(), requirements, results);
            // 分析数据已更新，清除该项目的统计与追溯矩阵缓存
            resultService.evictProjectCache(project.getId());
            task.setProgress(100);
            task.setStatus("completed");
            task.setCurrentStep("分析完成");
            task.setEndTime(LocalDateTime.now());
            log.append("[").append(LocalDateTime.now()).append("] 分析任务完成！\n");
            // GAP-008：将阶段耗时写入日志
            log.append("[").append(LocalDateTime.now()).append("] [performance] ").append(phaseTimings).append("\n");
            task.setExecutionLog(log.toString());
            updateAndPush(task);
        } catch (TaskTerminatedException te) {
            task.setStatus("terminated");
            task.setCurrentStep("任务已终止");
            task.setEndTime(LocalDateTime.now());
            log.append("[").append(LocalDateTime.now()).append("] 任务被用户终止\n");
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            pushDirect(task);
            LOGGER.info("分析任务[{}]已被用户终止", taskId);
        } catch (Exception e) {
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
            log.append("[").append(LocalDateTime.now()).append("] 错误: ").append(e.getMessage()).append("\n");
            task.setExecutionLog(log.toString());
            // 失败收尾不能走updateAndPush：其中的checkControl可能再次抛出终止异常，
            // 导致failed状态既不落库也不推送
            taskMapper.updateById(task);
            pushDirect(task);
            LOGGER.error("分析任务[{}]执行失败: {}", taskId, e.getMessage(), e);
        } finally {
            if (Boolean.TRUE.equals(slotHeld.get(taskId))) {
                analysisSlots.release();
            }
            slotHeld.remove(taskId);
            taskControls.remove(taskId);
        }
    }

    /** GAP-004：语义向量化阶段（parseCode 之后、runConsistencyCheck 之前，进度 60%）
     * 批量向量化需求与代码单元，写入 semantic_vector 字段；Embedding 不可用时全量跳过。 */
    private void embedSemantics(AnalysisTask task, List<Requirement> requirements, List<CodeUnit> codeUnits,
                                StringBuilder log) {
        if (embeddingService == null || !embeddingService.available()) {
            log.append("[").append(LocalDateTime.now()).append("] [embedding] 不可用，跳过语义向量化（保留 terms-only）\n");
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return;
        }
        log.append("[").append(LocalDateTime.now()).append("] [embedding] 开始批量向量化...\n");
        // B3（2026-09-03）：跳过已有稠密向量的单元（增量复用行），向量化只重算新增/变更单元。
        // 修复前：增量任务里 embedSemantics 每次全量重算（tenk 220s，占端到端 90%+），
        // 把增量解析的收益完全淹没。
        java.util.List<Requirement> pendingReqs = new java.util.ArrayList<>();
        int reqVecReused = 0;
        for (Requirement req : requirements) {
            if (com.traceguard.util.SemanticVectorUtil.hasDenseVector(req.getSemanticVector())) {
                reqVecReused++;
            } else {
                pendingReqs.add(req);
            }
        }
        java.util.List<CodeUnit> pendingUnits = new java.util.ArrayList<>();
        int codeVecReused = 0;
        for (CodeUnit u : codeUnits) {
            if (com.traceguard.util.SemanticVectorUtil.hasDenseVector(u.getSemanticVector())) {
                codeVecReused++;
            } else {
                pendingUnits.add(u);
            }
        }
        log.append("[").append(LocalDateTime.now()).append("] [embedding] 增量复用：需求 ")
                .append(reqVecReused).append("/").append(requirements.size())
                .append("，代码单元 ").append(codeVecReused).append("/").append(codeUnits.size())
                .append("（已有稠密向量，跳过重算）' + NL + '");
        int reqBatches = 0, reqSuccess = 0, reqFail = 0;
        int codeBatches = 0, codeSuccess = 0, codeFail = 0;
        int circuitFailCount = 0;
        int batchSize = 16;
        // 需求侧向量化（仅无稠密向量单元）
        for (int start = 0; start < pendingReqs.size(); start += batchSize) {
            int end = Math.min(pendingReqs.size(), start + batchSize);
            List<Requirement> batch = pendingReqs.subList(start, end);
            reqBatches++;
            try {
                List<String> texts = new java.util.ArrayList<>();
                for (Requirement req : batch) {
                    texts.add(buildReqEmbeddingText(req));
                }
                List<float[]> vectors = embeddingService.embedBatch(texts);
                for (int i = 0; i < batch.size(); i++) {
                    Requirement req = batch.get(i);
                    float[] v = (vectors != null && i < vectors.size()) ? vectors.get(i) : null;
                    String terms = buildReqTerms(req);
                    req.setSemanticVector(SemanticVectorUtil.toJson(v, terms));
                    requirementMapper.updateById(req);
                    if (v != null) {
                        reqSuccess++;
                    } else {
                        reqFail++;
                    }
                }
                circuitFailCount = 0;
            } catch (Exception e) {
                reqFail += batch.size();
                circuitFailCount++;
                log.append("[").append(LocalDateTime.now()).append("] [embedding] 需求批次 ").append(reqBatches)
                        .append(" 失败：").append(e.getMessage()).append("\n");
                if (circuitFailCount >= 3) {
                    log.append("[").append(LocalDateTime.now()).append("] [embedding-circuit-open] 连续 3 批失败，"
                            + "后续全部跳过向量化\n");
                    break;
                }
            }
        }
        // 代码侧向量化（仅当需求侧未熔断）
        if (circuitFailCount < 3) {
            circuitFailCount = 0;
            // FR-CODE-001 规则3（2.4 整改项）：字段清单单元仅展示，不参与语义向量化
            codeUnits = pendingUnits.stream()
                    .filter(u -> !com.traceguard.util.JavaCodeParserUtil.isFieldListUnit(u))
                    .collect(java.util.stream.Collectors.toList());
            for (int start = 0; start < codeUnits.size(); start += batchSize) {
                int end = Math.min(codeUnits.size(), start + batchSize);
                List<CodeUnit> batch = codeUnits.subList(start, end);
                codeBatches++;
                try {
                    List<String> texts = new java.util.ArrayList<>();
                    for (CodeUnit unit : batch) {
                        texts.add(buildCodeEmbeddingText(unit));
                    }
                    List<float[]> vectors = embeddingService.embedBatch(texts);
                    for (int i = 0; i < batch.size(); i++) {
                        CodeUnit unit = batch.get(i);
                        float[] v = (vectors != null && i < vectors.size()) ? vectors.get(i) : null;
                        String terms = extractCodeTerms(unit.getSemanticVector());
                        unit.setSemanticVector(SemanticVectorUtil.toJson(v, terms));
                        codeUnitMapper.updateById(unit);
                        if (v != null) {
                            codeSuccess++;
                        } else {
                            codeFail++;
                        }
                    }
                    circuitFailCount = 0;
                } catch (Exception e) {
                    codeFail += batch.size();
                    circuitFailCount++;
                    log.append("[").append(LocalDateTime.now()).append("] [embedding] 代码批次 ").append(codeBatches)
                            .append(" 失败：").append(e.getMessage()).append("\n");
                    if (circuitFailCount >= 3) {
                        log.append("[").append(LocalDateTime.now()).append("] [embedding-circuit-open] 连续 3 批失败，"
                                + "后续全部跳过向量化\n");
                        break;
                    }
                }
            }
        }
        log.append("[").append(LocalDateTime.now()).append("] [embedding] 完成：需求 ")
                .append(reqBatches).append(" 批（成功 ").append(reqSuccess).append("，失败 ").append(reqFail)
                .append("），代码 ").append(codeBatches).append(" 批（成功 ").append(codeSuccess)
                .append("，失败 ").append(codeFail).append("）\n");
        task.setExecutionLog(log.toString());
        taskMapper.updateById(task);
    }

    /** 构建需求向量化输入文本：需求原文 + Kripke 约束/不变量关键词 */
    private String buildReqEmbeddingText(Requirement req) {
        StringBuilder sb = new StringBuilder();
        if (req.getOriginalText() != null) sb.append(req.getOriginalText());
        if (req.getConstraintRules() != null) sb.append(" ").append(req.getConstraintRules());
        if (req.getAtomicConstraints() != null) {
            sb.append(" ").append(req.getAtomicConstraints());
        }
        if (req.getInvariants() != null) {
            sb.append(" ").append(req.getInvariants());
        }
        return sb.toString();
    }

    /** 构建需求 terms 降级字段（从语义向量中保留已有 terms 或空串） */
    private String buildReqTerms(Requirement req) {
        return ""; // 需求无现有 terms，GAP-024 降级时会从需求原文分词补充
    }

    /** 构建代码单元向量化输入文本：类名 + 方法签名 + logicDescription + 特征词 */
    private String buildCodeEmbeddingText(CodeUnit unit) {
        StringBuilder sb = new StringBuilder();
        if (unit.getClassName() != null) sb.append(unit.getClassName());
        if (unit.getMethodName() != null) sb.append(" ").append(unit.getMethodName());
        if (unit.getLogicDescription() != null) sb.append(" ").append(unit.getLogicDescription());
        String terms = extractCodeTerms(unit.getSemanticVector());
        if (!terms.isEmpty()) sb.append(" ").append(terms);
        return sb.toString();
    }

    /** 从语义向量JSON提取 terms 字段 */
    private String extractCodeTerms(String semanticVector) {
        if (semanticVector == null || semanticVector.trim().isEmpty()) return "";
        try {
            return SemanticVectorUtil.parse(semanticVector).getTerms();
        } catch (Exception e) {
            return "";
        }
    }

    /** 持久化任务状态并通过WebSocket推送进度 */
    private void updateAndPush(AnalysisTask task) {
        checkControl(task);
        taskMapper.updateById(task);
        pushDirect(task);
    }

    /** 直接推送进度到WebSocket客户端（不再触发控制检查） */
    private void pushDirect(AnalysisTask task) {
        try {
            progressWsHandler.pushProgress(task.getId(), task.getProgress(), task.getStatus(), task.getCurrentStep());
        } catch (Exception e) {
            LOGGER.warn("任务[{}]进度推送失败: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 任务控制检查点：在每个分析阶段之间调用。
     * 暂停请求 → 释放并行执行槽并阻塞等待直到恢复或终止（FUN-10）；终止请求 → 抛出内部终止信号。
     * 恢复时重新申请执行槽；无可用槽时保持暂停等待，避免暂停任务占满并行额度阻塞新任务（FR-PLAT-002 语义达成）。
     */
    private void checkControl(AnalysisTask task) {
        Long taskId = task.getId();
        Integer control = taskControls.get(taskId);
        if (control == null) {
            return;
        }
        boolean pausedAnnounced = false;
        while (control == CTRL_PAUSE) {
            if (!pausedAnnounced) {
                // FUN-10：暂停任务释放并行执行槽（暂停中的任务不算"真正执行"）
                if (Boolean.TRUE.equals(slotHeld.get(taskId))) {
                    analysisSlots.release();
                    slotHeld.put(taskId, false);
                    LOGGER.info("分析任务[{}]暂停，已释放并行执行槽（当前可用槽={}）",
                            taskId, analysisSlots.availablePermits());
                }
                task.setStatus("paused");
                task.setCurrentStep("任务已暂停");
                taskMapper.updateById(task);
                pushDirect(task);
                pausedAnnounced = true;
                LOGGER.info("分析任务[{}]已暂停，等待恢复", taskId);
            }
            try {
                // 4.5 整改：缩短暂停轮询间隔，降低从点击暂停到任务实际挂起的协作式延迟
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            control = taskControls.get(taskId);
            if (control == null) {
                return;
            }
        }
        if (control == CTRL_TERMINATE) {
            // 消费终止标记，避免后续catch块内再次触发；终止时释放持有的执行槽
            if (Boolean.TRUE.equals(slotHeld.get(taskId))) {
                analysisSlots.release();
                slotHeld.put(taskId, false);
            }
            taskControls.put(taskId, CTRL_RUNNING);
            throw new TaskTerminatedException();
        }
        // 恢复路径：重新申请执行槽（FUN-10）；无可用槽时保持暂停等待
        while (!Boolean.TRUE.equals(slotHeld.get(taskId))) {
            boolean got;
            try {
                got = analysisSlots.tryAcquire(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                got = analysisSlots.tryAcquire();
            }
            if (got) {
                slotHeld.put(taskId, true);
                task.setStatus("running");
                task.setCurrentStep("任务已恢复");
                taskMapper.updateById(task);
                pushDirect(task);
                LOGGER.info("分析任务[{}]已恢复并重新获取执行槽（当前可用槽={}）", taskId, analysisSlots.availablePermits());
                return;
            }
            // 无可用执行槽：继续保持暂停，等待新任务让出额度
            if (!pausedAnnounced) {
                task.setStatus("paused");
                task.setCurrentStep("任务已暂停（等待可用执行槽）");
                taskMapper.updateById(task);
                pushDirect(task);
                pausedAnnounced = true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            control = taskControls.get(taskId);
            if (control == null) {
                return;
            }
            if (control == CTRL_TERMINATE) {
                taskControls.put(taskId, CTRL_RUNNING);
                throw new TaskTerminatedException();
            }
        }
    }

    /** 历史任务分页查询（FR-PLAT-002） */
    public IPage<AnalysisTask> listTasks(Long projectId, int pageNum, int pageSize) {
        return taskMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getProjectId, projectId)
                        .orderByDesc(AnalysisTask::getCreateTime));
    }

    /** 暂停运行中的分析任务 */
    public void pauseTask(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (taskControls.containsKey(taskId)) {
            taskControls.put(taskId, CTRL_PAUSE);
        } else {
            throw new BusinessException("任务当前未在运行，无法暂停");
        }
    }

    /** 恢复已暂停的分析任务 */
    public void resumeTask(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (taskControls.containsKey(taskId)) {
            taskControls.put(taskId, CTRL_RUNNING);
            task.setStatus("running");
            taskMapper.updateById(task);
            pushDirect(task);
        } else {
            throw new BusinessException("任务当前未在运行，无法恢复");
        }
    }

    /** 终止分析任务（运行中协作终止；未运行直接置为terminated） */
    public void terminateTask(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (taskControls.containsKey(taskId)) {
            taskControls.put(taskId, CTRL_TERMINATE);
        } else {
            task.setStatus("terminated");
            task.setCurrentStep("任务已终止");
            task.setEndTime(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    /** 任务级数据隔离校验：通过任务所属项目校验访问权限 */
    public void checkTaskAccess(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        projectService.checkOwnership(task.getProjectId());
    }

    private List<Requirement> parseRequirements(AnalysisTask task, Project project, StringBuilder log,
                                                LlmUsage usage) throws Exception {
        // 断点续跑：需求条目已入库则直接复用（服务重启/中断后重新分析时跳过解析阶段）
        List<Requirement> existing = requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>().eq(Requirement::getProjectId, project.getId()));
        if (!existing.isEmpty()) {
            log.append("[").append(LocalDateTime.now()).append("] 检测到已有需求解析结果（").append(existing.size())
                    .append("条），断点续跑：跳过需求解析\n");
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return existing;
        }
        String reqPath = project.getRequirementFilePath();
        if (reqPath == null) {
            throw new Exception("需求文件不存在，请先上传需求文档");
        }
        // 支持多文档批量导入：路径以逗号分隔，逐个解析后合并
        // GAP-014：逐文件 try/catch 隔离，单文件解析失败不中断任务
        // GAP-019：逐文件切分并携带来源文件名（sourceFile），REQ 序号全局连续
        String[] paths = reqPath.split(",");
        List<Requirement> requirements = new java.util.ArrayList<>();
        int reqSeq = 1;
        int parsed = 0;
        java.util.List<ParseFailure> failures = new java.util.ArrayList<>();
        for (String p : paths) {
            File f = new File(p.trim());
            if (!f.exists()) {
                log.append("[").append(LocalDateTime.now()).append("] 警告：需求文件不存在，已跳过: ").append(p).append("\n");
                continue;
            }
            try {
                // 需求文档已加密落盘（5.2.1）：解析前解密到临时文件，用完即删
                File plain = fileStorageUtil.ensurePlainFile(p.trim());
                String fileContent;
                try {
                    fileContent = documentParserUtil.parseDocument(plain.getAbsolutePath());
                } finally {
                    fileStorageUtil.cleanupPlainFile(plain, f);
                }
                List<String> fileReqTexts = documentParserUtil.splitRequirements(fileContent);
                List<Requirement> fileReqs = requirementAnalyzerUtil.analyzeRequirements(
                        project.getId(), fileReqTexts, f.getName(), reqSeq);
                reqSeq += fileReqs.size();
                requirements.addAll(fileReqs);
                parsed++;
            } catch (Exception e) {
                // GAP-014：单文件失败隔离，记录失败信息后继续下一文件
                failures.add(ParseFailure.fromException(f.getName(), e, f.length()));
                log.append("[").append(LocalDateTime.now()).append("] 警告：需求文件解析失败，已隔离: ")
                        .append(f.getName()).append(" - ").append(e.getMessage()).append("\n");
            }
        }
        // GAP-014：失败清单落库
        if (!failures.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                project.setParseFailures(om.writeValueAsString(failures));
                projectMapper.updateById(project);
            } catch (Exception jsonEx) {
                log.append("[").append(LocalDateTime.now()).append("] 警告：parseFailures 序列化失败\n");
            }
            log.append("[").append(LocalDateTime.now()).append("] 解析失败文件数: ").append(failures.size()).append("\n");
        }
        if (parsed == 0) {
            throw new Exception("需求文件不存在，请先上传需求文档");
        }
        log.append("[").append(LocalDateTime.now()).append("] 开始解析需求文档（共").append(parsed).append("个文件）...\n");
        for (Requirement req : requirements) {
            requirementMapper.insert(req);
        }
        // GAP-001：LLM 增强需求语义提取（Kripke 结构），失败则忽略（保留规则提取结果）
        if (llmService.isEnabled()) {
            log.append("[").append(LocalDateTime.now()).append("] [llm] 开始增强需求语义提取...\n");
            for (Requirement req : requirements) {
                // 4.5 整改：在每个 LLM 批次项之间插入控制检查点，缩短暂停/终止的协作式响应延迟
                checkControl(task);
                if (usage.isQuotaReached("requirement", llmService.getMaxCallsPerStage())) {
                    log.append("[").append(LocalDateTime.now()).append("] [llm-stage-quota] 需求语义提取环节已达调用上限，剩余条目走规则实现\n");
                    break;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode kripke = llmService.analyzeRequirement(req.getOriginalText());
                    if (kripke != null) {
                        // 覆盖规则提取结果：states/transitions/constraints/invariants
                        if (kripke.has("states")) {
                            req.setStateSet(kripke.get("states").toString());
                        }
                        if (kripke.has("transitions")) {
                            req.setStateTransitions(kripke.get("transitions").toString());
                        }
                        if (kripke.has("constraints")) {
                            java.util.List<String> cons = new java.util.ArrayList<>();
                            kripke.get("constraints").forEach(n -> cons.add(n.asText()));
                            req.setAtomicConstraints(cons.toString());
                        }
                        if (kripke.has("invariants")) {
                            java.util.List<String> invs = new java.util.ArrayList<>();
                            kripke.get("invariants").forEach(n -> invs.add(n.asText()));
                            req.setInvariants(invs.toString());
                        }
                        requirementMapper.updateById(req);
                        usage.record("requirement", true);
                        log.append("[").append(LocalDateTime.now()).append("] [llm] 需求 ").append(req.getRequirementId())
                                .append(" 语义增强成功\n");
                    } else {
                        usage.record("requirement", false);
                    }
                } catch (Exception e) {
                    usage.record("requirement", false);
                    log.append("[").append(LocalDateTime.now()).append("] [llm] 需求 ").append(req.getRequirementId())
                            .append(" 语义增强失败：").append(e.getMessage()).append("，保留规则提取结果\n");
                }
            }
        }
        log.append("[").append(LocalDateTime.now()).append("] 需求解析完成，共提取").append(requirements.size()).append("条需求\n");
        task.setExecutionLog(log.toString());
        taskMapper.updateById(task);
        return requirements;
    }

    void generateFormalSpecs(AnalysisTask task, List<Requirement> requirements, StringBuilder log) throws Exception {
        generateFormalSpecs(task, requirements, log, new LlmUsage());
    }

    void generateFormalSpecs(AnalysisTask task, List<Requirement> requirements, StringBuilder log,
                             LlmUsage usage) throws Exception {
        // GAP-013：断点续跑一致性——存量 failed 规约在 strict 模式下同样阻断
        Long specCount = specMapper.selectCount(new LambdaQueryWrapper<FormalSpecification>()
                .eq(FormalSpecification::getProjectId, task.getProjectId()));
        if (specCount != null && specCount > 0) {
            log.append("[").append(LocalDateTime.now()).append("] 检测到已有形式化规约（").append(specCount)
                    .append("条），断点续跑：跳过规约生成\n");
            if (strictSpecVerify) {
                List<FormalSpecification> existingFailed = specMapper.selectList(new LambdaQueryWrapper<FormalSpecification>()
                        .eq(FormalSpecification::getProjectId, task.getProjectId())
                        .eq(FormalSpecification::getVerificationStatus, "failed"));
                if (!existingFailed.isEmpty()) {
                    StringBuilder failList = new StringBuilder();
                    for (FormalSpecification fs : existingFailed) {
                        String detail = fs.getVerificationResult() != null ? fs.getVerificationResult() : "未知原因";
                        if (detail.length() > 100) detail = detail.substring(0, 100);
                        failList.append("  规约校验失败：").append(fs.getSpecId())
                                .append("：").append(detail).append("\n");
                        log.append("[").append(LocalDateTime.now()).append("] 规约校验失败：")
                                .append(fs.getSpecId()).append("：").append(detail).append("\n");
                    }
                    task.setExecutionLog(log.toString());
                    taskMapper.updateById(task);
                    throw new Exception("共 " + existingFailed.size()
                            + " 条形式化规约校验失败（断点续跑存量），任务已按配置阻断（FR-REQ-004），完整清单见执行日志");
                }
            }
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return;
        }
        log.append("[").append(LocalDateTime.now()).append("] 开始生成Alloy形式化规约...\n");
        // GAP-002：任务级熔断器（单任务内连续 3 次求解异常/超时 -> 本任务后续直接结构校验）
        TaskCircuitBreaker alloyBreaker = new TaskCircuitBreaker(AlloySpecVerifierUtil.CIRCUIT_BREAK_THRESHOLD);
        TaskBreakerHolder.set(alloyBreaker);
        // GAP-013：清单化阻断——收集全部失败规约，完成后一次性阻断
        List<String[]> failures = new java.util.ArrayList<>(); // [specId, reqId, reqTitle, summary]
        try {
            int passed = 0, warning = 0, failed = 0;
            for (Requirement req : requirements) {
                FormalSpecification spec = new FormalSpecification();
                spec.setProjectId(task.getProjectId());
                spec.setRequirementId(req.getId());
                spec.setSpecId("SPEC-" + req.getRequirementId());
                // GAP-001：优先 LLM 生成 Alloy 规约，失败回退规则模板
                String alloyCode = null;
                if (llmService.isEnabled() && !usage.isQuotaReached("alloy", llmService.getMaxCallsPerStage())) {
                    try {
                        String llmAlloy = llmService.generateAlloy(req.getOriginalText(), parseKripke(req));
                        if (llmAlloy != null && !llmAlloy.trim().isEmpty()) {
                            alloyCode = llmAlloy;
                            usage.record("alloy", true);
                            log.append("[").append(LocalDateTime.now()).append("] [llm] 规约 ").append(spec.getSpecId())
                                    .append(" LLM 生成成功\n");
                        } else {
                            usage.record("alloy", false);
                        }
                    } catch (Exception e) {
                        usage.record("alloy", false);
                        log.append("[").append(LocalDateTime.now()).append("] [llm] 规约 ").append(spec.getSpecId())
                                .append(" LLM 生成失败：").append(e.getMessage()).append("，回退规则模板\n");
                    }
                } else if (llmService.isEnabled()) {
                    log.append("[").append(LocalDateTime.now()).append("] [llm-stage-quota] Alloy 生成环节已达调用上限，后续规约走规则模板\n");
                }
                if (alloyCode == null || alloyCode.trim().isEmpty()) {
                    alloyCode = specRegistry.getSpecGenerator(specLanguage).generate(req);
                }
                spec.setAlloyCode(alloyCode);
                // GAP-002：优先真实 Alloy 语义求解；异常/超时/熔断/开关关闭时回退结构校验（FR-REQ-004）
                AlloySpecVerifierUtil.VerifyResult vr = specRegistry.getSpecVerifier(specLanguage).verify(alloyCode);
                spec.setVerificationStatus(vr.status());
                spec.setVerificationResult(vr.summary());
                spec.setOptimizationSuggestion(vr.suggestion());
                spec.setVerificationDetail(buildVerificationDetail(vr));
                specMapper.insert(spec);
                req.setStatus(2);
                requirementMapper.updateById(req);
                if ("passed".equals(vr.status())) passed++;
                else if ("warning".equals(vr.status())) warning++;
                else {
                    failed++;
                    // GAP-013：不再首败即断，收集失败明细
                    String summary = vr.summary() != null ? vr.summary() : "未知原因";
                    if (summary.length() > 100) summary = summary.substring(0, 100);
                    String reqTitle = req.getOriginalText() != null && req.getOriginalText().length() > 30
                            ? req.getOriginalText().substring(0, 30) + "..." : req.getOriginalText();
                    failures.add(new String[]{spec.getSpecId(), req.getRequirementId(), reqTitle, summary});
                    log.append("[").append(LocalDateTime.now()).append("] 规约校验失败：")
                            .append(spec.getSpecId()).append("（需求 ").append(req.getRequirementId())
                            .append("《").append(reqTitle).append("》）：").append(summary).append("\n");
                }
            }
            if (alloyBreaker.isOpen()) {
                log.append("[").append(LocalDateTime.now()).append("] [alloy-circuit-open] 连续")
                        .append(AlloySpecVerifierUtil.CIRCUIT_BREAK_THRESHOLD)
                        .append(" 次求解异常/超时，本任务后续规约已切换为结构校验\n");
            }
            log.append("[").append(LocalDateTime.now()).append("] 形式化规约生成完成，共").append(requirements.size())
                    .append("条：校验通过").append(passed).append("，告警").append(warning).append("，失败").append(failed).append("\n");
            // GAP-013：清单化阻断——全量校验完成后，若 strict 且有失败，一次性抛出完整清单
            if (strictSpecVerify && !failures.isEmpty()) {
                int displayCount = Math.min(failures.size(), 20);
                StringBuilder blockMsg = new StringBuilder("共 " + failures.size()
                        + " 条形式化规约校验失败，任务已按配置阻断（FR-REQ-004），完整清单见执行日志。前 "
                        + displayCount + " 条：");
                for (int i = 0; i < displayCount; i++) {
                    String[] f = failures.get(i);
                    blockMsg.append("\n  ").append(f[0]).append("（需求 ").append(f[1])
                            .append("《").append(f[2]).append("》）：").append(f[3]);
                }
                if (failures.size() > 20) {
                    blockMsg.append("\n  ...其余 ").append(failures.size() - 20).append(" 条见执行日志");
                }
                task.setExecutionLog(log.toString());
                taskMapper.updateById(task);
                throw new Exception(blockMsg.toString());
            }
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
        } finally {
            TaskBreakerHolder.clear();
        }
    }

    /** GAP-002：组装 verification_detail JSON（engine/satStatus/instanceCount/counterexample/message/elapsedMs） */
    private String buildVerificationDetail(AlloySpecVerifierUtil.VerifyResult vr) {
        try {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("engine", vr.getEngine());
            detail.put("satStatus", vr.getSatStatus());
            detail.put("instanceCount", vr.getInstanceCount());
            detail.put("counterexample", vr.getCounterexample());
            detail.put("message", vr.getMessage() != null ? vr.getMessage() : vr.summary());
            detail.put("elapsedMs", vr.getElapsedMs());
            return VERIFICATION_DETAIL_MAPPER.writeValueAsString(detail);
        } catch (Exception e) {
            LOGGER.warn("verification_detail 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** GAP-001：从 Requirement 提取 Kripke 结构 JSON（供 LLM 生成 Alloy 使用） */
    private Object parseKripke(Requirement req) {
        Map<String, Object> kripke = new java.util.LinkedHashMap<>();
        // states: 从 stateSet 解析
        if (req.getStateSet() != null) {
            kripke.put("states", parseListField(req.getStateSet()));
        }
        // transitions: 从 stateTransitions 解析
        if (req.getStateTransitions() != null) {
            kripke.put("transitions", parseTransitionList(req.getStateTransitions()));
        }
        // constraints: 从 atomicConstraints 解析
        if (req.getAtomicConstraints() != null) {
            kripke.put("constraints", parseListField(req.getAtomicConstraints()));
        }
        // invariants: 从 invariants 解析
        if (req.getInvariants() != null) {
            kripke.put("invariants", parseListField(req.getInvariants()));
        }
        return kripke;
    }

    /** 解析 [a, b, c] 格式字符串为 List<String> */
    private java.util.List<String> parseListField(String listText) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (listText == null || listText.trim().isEmpty() || "[]".equals(listText.trim())) {
            return result;
        }
        String body = listText.replace("[", "").replace("]", "");
        for (String item : body.split(",")) {
            String t = item.replace("\"", "").trim();
            if (!t.isEmpty() && !result.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }

    /** 解析 [a->b, ...] 格式字符串为 List<Map> */
    private java.util.List<Map<String, String>> parseTransitionList(String transitionsText) {
        java.util.List<Map<String, String>> result = new java.util.ArrayList<>();
        if (transitionsText == null || transitionsText.trim().isEmpty() || "[]".equals(transitionsText.trim())) {
            return result;
        }
        String body = transitionsText.replace("[", "").replace("]", "");
        for (String item : body.split(",")) {
            int idx = item.indexOf("->");
            if (idx <= 0) continue;
            String from = item.substring(0, idx).trim();
            String to = item.substring(idx + 2).trim();
            if (!from.isEmpty() && !to.isEmpty()) {
                Map<String, String> tr = new java.util.LinkedHashMap<>();
                tr.put("from", from);
                tr.put("to", to);
                result.add(tr);
            }
        }
        return result;
    }

    private List<CodeUnit> parseCode(AnalysisTask task, Project project, StringBuilder log,
                                     LlmUsage usage) throws Exception {
        String codePath = project.getCodeProjectPath();
        if (codePath == null || !new File(codePath).exists()) {
            throw new Exception("代码项目不存在，请先上传代码工程");
        }
        // P2-5：已有解析结果（含上传新代码后保留的旧行）时，按文件内容哈希做增量解析；
        // 关闭增量开关时回退历史"断点续跑：直接复用"行为
        List<CodeUnit> existing = codeUnitMapper.selectList(
                new LambdaQueryWrapper<CodeUnit>().eq(CodeUnit::getProjectId, project.getId()));
        if (!existing.isEmpty()) {
            if (!incrementalCodeEnabled) {
                log.append("[").append(LocalDateTime.now()).append("] 检测到已有代码解析结果（").append(existing.size())
                        .append("个方法单元），断点续跑：跳过代码解析（incremental-code=off）\n");
                refreshCodeDefects(task, project, codePath, log);
                task.setExecutionLog(log.toString());
                taskMapper.updateById(task);
                return existing;
            }
            List<CodeUnit> merged = incrementalParseCode(task, project, existing, codePath, log, usage);
            if (merged != null) {
                return merged;
            }
            // 存量行缺 contentHash（老数据）无法增量 -> 清理旧行后走全量重解析
            log.append("[").append(LocalDateTime.now())
                    .append("] 存量代码单元缺少内容哈希，改为全量重解析\n");
            codeUnitMapper.delete(new LambdaQueryWrapper<CodeUnit>()
                    .eq(CodeUnit::getProjectId, project.getId()));
            codeDefectMapper.delete(new LambdaQueryWrapper<CodeDefect>()
                    .eq(CodeDefect::getProjectId, project.getId()));
        }
        log.append("[").append(LocalDateTime.now()).append("] 开始解析Java代码...\n");
        // GAP-003：任务级 Soot 源码编译一次并缓存，供 parseFile 判定（避免每个文件重复编译）
        SootCfgBuilderUtil.CompileResult sootCompile = prepareSootCompile(task, codePath, log);
        try {
            // GAP-014：单文件隔离解析——任一文件异常仅跳过并计入 failures（key 为源码相对路径），不中断整体解析
            JavaCodeParserUtil.ProjectParseResult parseResult = parserRegistry.getCodeParser(codeLanguage)
                    .parseProject(codePath, sootCompile != null && sootCompile.isSuccess() ? sootCompile : null);
            List<CodeUnit> codeUnits = parseResult.codeUnits;
            if (!parseResult.failures.isEmpty()) {
                appendCodeParseFailures(project, parseResult.failures, log);
            }
            // GAP-001 + GAP-006：LLM 逻辑描述增强 + 结构化代码逻辑分析（P2-5：抽为可复用 helper，增量解析只作用于变更单元）
            applyLogicEnrichment(task, codeUnits, log, usage);
            for (CodeUnit unit : codeUnits) {
                unit.setProjectId(project.getId());
                codeUnitMapper.insert(unit);
            }
            log.append("[").append(LocalDateTime.now()).append("] 代码解析完成，共提取").append(codeUnits.size()).append("个方法单元\n");
            detectCodeDefects(task, project, codePath, log);
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return codeUnits;
        } finally {
            // GAP-003：编译产物与临时目录在任务结束（finally）递归删除
            if (sootCompile != null && sootCompile.getTempRoot() != null) {
                SootCfgBuilderUtil.deleteRecursively(sootCompile.getTempRoot());
            }
        }
    }

    /**
     * P2-5：增量解析代码——按文件内容哈希比对（code_unit.content_hash vs 当前 .java 文件全文），
     * 仅重解析变更/新增文件（含 Soot/AST/向量化/LLM 描述），未变更文件整体复用存量行，清理已删除文件的行。
     *
     * @return 合并后的全部代码单元；返回 null 表示存在缺 contentHash 的存量行（老数据），需回退全量重解析
     */
    private List<CodeUnit> incrementalParseCode(AnalysisTask task, Project project,
                                                List<CodeUnit> existing, String codePath,
                                                StringBuilder log, LlmUsage usage) throws Exception {
        // 老数据兼容：任一存量行无 contentHash -> 全量重解析（回退由调用方处理）
        for (CodeUnit u : existing) {
            if (u.getFilePath() == null || u.getContentHash() == null || u.getContentHash().isEmpty()) {
                return null;
            }
        }
        // filePath -> 内容哈希（同文件各方法单元 hash 一致，取首个即可）
        Map<String, String> oldHashByFile = new HashMap<>();
        for (CodeUnit u : existing) {
            if (!oldHashByFile.containsKey(u.getFilePath())) {
                oldHashByFile.put(u.getFilePath(), u.getContentHash());
            }
        }
        Map<String, String> curHashByFile = javaCodeParserUtil.scanContentHashes(codePath);
        // P2-5：diff 决策抽为可单测纯函数（IncrementalDiff），此处仅消费其计划
        IncrementalDiff.Plan plan = IncrementalDiff.plan(oldHashByFile, curHashByFile);
        List<String> changed = plan.changed;   // 变更/新增文件
        List<String> removed = plan.removed;   // 本地已删除文件
        if (plan.isEmpty()) {
            log.append("[").append(LocalDateTime.now()).append("] 增量比对：全部 ")
                    .append(existing.size()).append(" 个方法单元内容未变更，跳过代码解析（复用存量结果）\n");
            refreshCodeDefects(task, project, codePath, log);
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return existing;
        }
        log.append("[").append(LocalDateTime.now()).append("] 开始增量代码解析：变更/新增文件 ")
                .append(changed.size()).append(" 个，移除 ").append(removed.size())
                .append(" 个，未变更复用 ").append(plan.unchangedCount(curHashByFile.size())).append(" 个文件\n");
        // GAP-003：任务级 Soot 编译一次，供变更文件构建字节码级 CFG
        SootCfgBuilderUtil.CompileResult sootCompile = prepareSootCompile(task, codePath, log);
        try {
            Map<String, File> fileByRel = indexCodeFiles(codePath);
            List<CodeUnit> parsed = new ArrayList<>();
            for (String rel : changed) {
                File f = fileByRel.get(rel);
                if (f == null) {
                    continue;
                }
                parsed.addAll(javaCodeParserUtil.parseFileForAnalysis(f, codePath, sootCompile));
            }
            if (!parsed.isEmpty()) {
                applyLogicEnrichment(task, parsed, log, usage);
            }
            Set<String> invalid = new HashSet<>(changed);
            invalid.addAll(removed);
            if (!invalid.isEmpty()) {
                codeUnitMapper.delete(new LambdaQueryWrapper<CodeUnit>()
                        .eq(CodeUnit::getProjectId, project.getId())
                        .in(CodeUnit::getFilePath, invalid));
            }
            long deletedOld = existing.stream()
                    .filter(u -> u.getFilePath() != null && invalid.contains(u.getFilePath())).count();
            List<CodeUnit> merged = new ArrayList<>(existing.size() - (int) deletedOld + parsed.size());
            for (CodeUnit u : existing) {
                if (u.getFilePath() == null || !invalid.contains(u.getFilePath())) {
                    merged.add(u);
                }
            }
            for (CodeUnit unit : parsed) {
                unit.setProjectId(project.getId());
                codeUnitMapper.insert(unit);
                merged.add(unit);
            }
            log.append("[").append(LocalDateTime.now()).append("] 增量解析完成：复用 ")
                    .append(existing.size() - deletedOld).append(" 个、新解析/替换 ").append(parsed.size())
                    .append(" 个方法单元，共 ").append(merged.size()).append(" 个\n");
            refreshCodeDefects(task, project, codePath, log);
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return merged;
        } finally {
            if (sootCompile != null && sootCompile.getTempRoot() != null) {
                SootCfgBuilderUtil.deleteRecursively(sootCompile.getTempRoot());
            }
        }
    }

    /** P2-5：按相对工程路径索引当前代码文件（与 JavaCodeParserUtil.relativePath 同口径） */
    private Map<String, File> indexCodeFiles(String codePath) {
        Map<String, File> map = new HashMap<>();
        List<File> files = new ArrayList<>();
        collectJavaFiles(new File(codePath), files);
        String base = codePath.replace("\\", "/");
        if (!base.endsWith("/")) base = base + "/";
        for (File f : files) {
            String abs = f.getAbsolutePath().replace("\\", "/");
            String rel = abs.startsWith(base) ? abs.substring(base.length()) : f.getName();
            map.put(rel, f);
        }
        return map;
    }

    /** P2-5：清理项目旧基础缺陷并按本次任务重新检测（与一致性/缺陷同 taskId 口径，避免统计错乱） */
    private void refreshCodeDefects(AnalysisTask task, Project project, String codePath, StringBuilder log) {
        codeDefectMapper.delete(new LambdaQueryWrapper<CodeDefect>()
                .eq(CodeDefect::getProjectId, project.getId()));
        detectCodeDefects(task, project, codePath, log);
    }

    /** GAP-001 + GAP-006：对方法单元执行 LLM 逻辑描述增强 + CodeLogicDescriber 结构化分析（全量/增量共用） */
    private void applyLogicEnrichment(AnalysisTask task, List<CodeUnit> codeUnits, StringBuilder log,
                                      LlmUsage usage) {
        if (llmService.isEnabled()) {
            log.append("[").append(LocalDateTime.now()).append("] [llm] 开始增强代码逻辑描述...\n");
            for (CodeUnit unit : codeUnits) {
                // 4.5 整改：在每个 LLM 批次项之间插入控制检查点，缩短暂停/终止的协作式响应延迟
                checkControl(task);
                if (usage.isQuotaReached("code-explain", llmService.getMaxCallsPerStage())) {
                    log.append("[").append(LocalDateTime.now()).append("] [llm-stage-quota] 代码逻辑描述环节已达调用上限，剩余方法走规则实现\n");
                    break;
                }
                try {
                    String cfgSummary = buildCfgSummary(unit.getCfgData());
                    // GAP-006：CodeLogicDescriber.describe（LLM 中文逻辑还原），失败返回 null 保留规则结果
                    String llmDesc = codeLogicDescriber.describe(unit.getCodeContent(), cfgSummary);
                    if (llmDesc != null && !llmDesc.trim().isEmpty()) {
                        unit.setLogicDescription(llmDesc);
                        usage.record("code-explain", true);
                        log.append("[").append(LocalDateTime.now()).append("] [llm] 方法 ")
                                .append(unit.getClassName()).append(".").append(unit.getMethodName())
                                .append(" 逻辑描述增强成功\n");
                    } else {
                        usage.record("code-explain", false);
                    }
                } catch (Exception e) {
                    usage.record("code-explain", false);
                    log.append("[").append(LocalDateTime.now()).append("] [llm] 方法 ")
                            .append(unit.getClassName()).append(".").append(unit.getMethodName())
                            .append(" 逻辑描述增强失败：").append(e.getMessage()).append("，保留规则提取结果\n");
                }
            }
        }
        // GAP-006：CodeLogicDescriber 结构化分析
        log.append("[").append(LocalDateTime.now()).append("] [logic-describer] 开始结构化代码逻辑分析...\n");
        for (CodeUnit unit : codeUnits) {
            try {
                CodeLogicDescriber.LogicAnalysis logicAnalysis = codeLogicDescriber.analyzeCodeLogic(
                        unit.getClassName(), unit.getMethodName(), unit.getCodeContent());
                if (logicAnalysis.isSuccess()) {
                    // 将分析结果存储到数据库
                    String logicAnalysisJson = codeLogicDescriber.toJson(logicAnalysis);
                    unit.setLogicAnalysis(logicAnalysisJson);
                    // 如果 LLM 描述为空或置信度较低，使用结构化描述作为补充
                    if (unit.getLogicDescription() == null || unit.getLogicDescription().trim().isEmpty()
                            || logicAnalysis.getConfidence() > 0.7) {
                        String structuredDesc = buildStructuredDescription(logicAnalysis);
                        if (!structuredDesc.isEmpty()) {
                            if (unit.getLogicDescription() == null || unit.getLogicDescription().trim().isEmpty()) {
                                unit.setLogicDescription(structuredDesc);
                            } else {
                                // 结合 LLM 描述和结构化描述
                                unit.setLogicDescription(unit.getLogicDescription() + "\n\n" + structuredDesc);
                            }
                        }
                    }
                    log.append("[").append(LocalDateTime.now()).append("] [logic-describer] 方法 ")
                            .append(unit.getClassName()).append(".").append(unit.getMethodName())
                            .append(" 逻辑分析成功（置信度: ").append(String.format("%.2f", logicAnalysis.getConfidence()))
                            .append("）\n");
                } else {
                    log.append("[").append(LocalDateTime.now()).append("] [logic-describer] 方法 ")
                            .append(unit.getClassName()).append(".").append(unit.getMethodName())
                            .append(" 逻辑分析失败：").append(logicAnalysis.getError()).append("\n");
                }
            } catch (Exception e) {
                log.append("[").append(LocalDateTime.now()).append("] [logic-describer] 方法 ")
                        .append(unit.getClassName()).append(".").append(unit.getMethodName())
                        .append(" 逻辑分析异常：").append(e.getMessage()).append("\n");
            }
        }
    }

    /**
     * GAP-014：代码侧解析失败清单合并到 project.parse_failures（key 为源码相对路径），
     * 与需求侧失败清单共存，落库并在结果页统一展示。
     */
    private void appendCodeParseFailures(Project project, List<ParseFailure> codeFailures, StringBuilder log) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<ParseFailure> all = new java.util.ArrayList<>();
            if (project.getParseFailures() != null && !project.getParseFailures().isEmpty()) {
                try {
                    all.addAll(om.readValue(project.getParseFailures(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<ParseFailure>>() {}));
                } catch (Exception ignore) {
                    // 存量 parse_failures 非本格式时忽略，仅保留本次新增
                }
            }
            all.addAll(codeFailures);
            project.setParseFailures(om.writeValueAsString(all));
            projectMapper.updateById(project);
        } catch (Exception jsonEx) {
            log.append("[").append(LocalDateTime.now()).append("] 警告：代码解析失败清单序列化失败\n");
        }
        log.append("[").append(LocalDateTime.now()).append("] 代码解析失败文件数: ").append(codeFailures.size()).append("\n");
    }

    /** GAP-001：从 CFG JSON 提取简要摘要（节点数/边数/循环数），供 LLM 生成逻辑描述使用 */
    private String buildCfgSummary(String cfgData) {
        if (cfgData == null || cfgData.trim().isEmpty()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode cfg = VERIFICATION_DETAIL_MAPPER.readTree(cfgData);
            int nodes = cfg.path("nodes").size();
            int edges = cfg.path("edges").size();
            int loops = 0;
            for (com.fasterxml.jackson.databind.JsonNode node : cfg.path("nodes")) {
                if (node.path("type").asText("").contains("loop") || node.path("type").asText("").contains("while")) {
                    loops++;
                }
            }
            return String.format("CFG: %d 节点，%d 边，%d 循环", nodes, edges, loops);
        } catch (Exception e) {
            return "";
        }
    }

    /** GAP-006：从逻辑分析结果构建结构化描述 */
    private String buildStructuredDescription(CodeLogicDescriber.LogicAnalysis logicAnalysis) {
        if (!logicAnalysis.isSuccess()) {
            return "";
        }
        
        StringBuilder desc = new StringBuilder();
        desc.append("业务目的: ").append(logicAnalysis.getBusinessPurpose()).append("\n");
        
        if (!logicAnalysis.getInputSemantics().isEmpty()) {
            desc.append("输入参数: ");
            List<String> inputDescs = new ArrayList<>();
            for (Map.Entry<String, String> entry : logicAnalysis.getInputSemantics().entrySet()) {
                inputDescs.add(entry.getKey() + "(" + entry.getValue() + ")");
            }
            desc.append(String.join(", ", inputDescs)).append("\n");
        }
        
        if (!logicAnalysis.getOutputSemantics().isEmpty()) {
            desc.append("输出结果: ").append(logicAnalysis.getOutputSemantics()).append("\n");
        }
        
        if (!logicAnalysis.getBusinessLogicSteps().isEmpty()) {
            desc.append("核心步骤:\n");
            for (int i = 0; i < logicAnalysis.getBusinessLogicSteps().size(); i++) {
                desc.append("  ").append(i + 1).append(". ").append(logicAnalysis.getBusinessLogicSteps().get(i)).append("\n");
            }
        }
        
        if (!logicAnalysis.getBusinessRules().isEmpty()) {
            desc.append("业务规则:\n");
            for (String rule : logicAnalysis.getBusinessRules()) {
                desc.append("  • ").append(rule).append("\n");
            }
        }
        
        if (!logicAnalysis.getExceptionHandling().isEmpty()) {
            desc.append("异常处理:\n");
            for (String exception : logicAnalysis.getExceptionHandling()) {
                desc.append("  • ").append(exception).append("\n");
            }
        }
        
        if (!logicAnalysis.getDataOperations().isEmpty()) {
            desc.append("数据操作: ");
            desc.append(String.join(", ", logicAnalysis.getDataOperations())).append("\n");
        }
        
        if (!logicAnalysis.getDomainConcepts().isEmpty()) {
            desc.append("领域概念: ");
            desc.append(String.join(", ", logicAnalysis.getDomainConcepts())).append("\n");
        }
        
        return desc.toString();
    }

    /**
     * GAP-003：任务级 Soot 源码编译（编译失败/异常 -> 整体降级 AST，execution_log 记录编译诊断摘要）。
     * 编译结果由 parseCode 缓存，供 parseFile 判定是否走 Soot 字节码 CFG。
     */
    private SootCfgBuilderUtil.CompileResult prepareSootCompile(AnalysisTask task, String codePath, StringBuilder log) {
        try {
            List<File> javaFiles = new java.util.ArrayList<>();
            collectJavaFiles(new File(codePath), javaFiles);
            if (javaFiles.isEmpty()) {
                return null;
            }
            List<java.nio.file.Path> paths = javaFiles.stream().map(File::toPath).collect(java.util.stream.Collectors.toList());
            log.append("[").append(LocalDateTime.now()).append("] [soot] 尝试编译 ").append(paths.size()).append(" 个源文件...\n");
            // GAP-048：传入项目根目录 codePath，使 Soot 能探测 pom.xml/build.gradle 的 Java 版本并按 8/11/17 分版本编译
            SootCfgBuilderUtil.CompileResult result = SootCfgBuilderUtil.compileSources(paths, java.nio.file.Paths.get(codePath));
            if (result.isSuccess()) {
                log.append("[").append(LocalDateTime.now()).append("] [soot] 源码编译成功，启用字节码级 CFG\n");
            } else {
                List<String> errors = result.getErrors() != null ? result.getErrors() : java.util.Collections.emptyList();
                String diag = errors.isEmpty() ? ""
                        : "（" + String.join("; ", errors.subList(0, Math.min(3, errors.size()))) + "）";
                log.append("[").append(LocalDateTime.now()).append("] [soot] 源码编译失败，整体降级 AST 级 CFG").append(diag).append("\n");
            }
            task.setExecutionLog(log.toString());
            taskMapper.updateById(task);
            return result;
        } catch (Exception e) {
            log.append("[").append(LocalDateTime.now()).append("] [soot] 源码编译异常: ").append(e.getMessage())
                    .append("，整体降级 AST 级 CFG\n");
            return null;
        }
    }

    private void detectCodeDefects(AnalysisTask task, Project project, String codePath, StringBuilder log) {
        log.append("[").append(LocalDateTime.now()).append("] 开始代码基础缺陷检测...\n");
        List<File> javaFiles = new java.util.ArrayList<>();
        collectJavaFiles(new File(codePath), javaFiles);
        int defectCount = 0;
        for (File file : javaFiles) {
            List<CodeDefect> defects = javaCodeParserUtil.detectBasicDefects(file, codePath, project.getId(), task.getId());
            for (CodeDefect d : defects) {
                codeDefectMapper.insert(d);
                defectCount++;
            }
        }
        log.append("[").append(LocalDateTime.now()).append("] 基础缺陷检测完成，发现").append(defectCount).append("个潜在缺陷\n");
    }

    private void collectJavaFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("target") && !f.getName().equals("build")) {
                    collectJavaFiles(f, files);
                }
            } else if (f.getName().endsWith(".java")) {
                files.add(f);
            }
        }
    }

    private List<ConsistencyResult> runConsistencyCheck(AnalysisTask task, Project project,
                                                         List<Requirement> requirements, List<CodeUnit> codeUnits,
                                                         StringBuilder log) {
        // 每次执行前清理旧的一致性/缺陷结果，避免重复分析时数据叠加
        consistencyMapper.delete(new LambdaQueryWrapper<ConsistencyResult>()
                .eq(ConsistencyResult::getProjectId, project.getId()));
        defectMapper.delete(new LambdaQueryWrapper<Defect>()
                .eq(Defect::getProjectId, project.getId()));
        log.append("[").append(LocalDateTime.now()).append("] 开始执行需求-代码一致性校验...\n");
        log.append("[").append(LocalDateTime.now()).append("] 权重配置: α=").append(task.getWeightAlpha())
                .append(", β=").append(task.getWeightBeta())
                .append(", γ=").append(task.getWeightGamma()).append("\n");
        log.append("[").append(LocalDateTime.now()).append("] 阈值配置: T1=").append(task.getThresholdT1())
                .append(", T2=").append(task.getThresholdT2()).append("\n");
        // GAP-005：加载需求关联的形式化规约（alloy_code），供 Con/Inv 维度规约驱动计算
        Map<Long, String> specAlloyByReqId = new HashMap<>();
        List<FormalSpecification> specs = specMapper.selectList(
                new LambdaQueryWrapper<FormalSpecification>()
                        .eq(FormalSpecification::getProjectId, project.getId()));
        for (FormalSpecification s : specs) {
            if (s.getRequirementId() != null && s.getAlloyCode() != null) {
                specAlloyByReqId.put(s.getRequirementId(), s.getAlloyCode());
            }
        }
        // FUN-04b：扫描类级判定证据（字段/常量声明），供量化边界核对规则与 LLM 判定的分工上下文使用；
        // 扫描失败时为空 map，主链路行为不变
        String evidenceCodePath = project.getCodeProjectPath();
        Map<String, List<String>> classEvidenceByClass = (evidenceCodePath != null && new File(evidenceCodePath).exists())
                ? com.traceguard.util.ClassEvidenceScanner.scanConstants(evidenceCodePath)
                : Collections.emptyMap();
        List<ConsistencyResult> results = consistencyChecker.checkConsistency(
                task.getId(), project.getId(), requirements, codeUnits, specAlloyByReqId,
                classEvidenceByClass,
                task.getWeightAlpha(), task.getWeightBeta(), task.getWeightGamma(),
                task.getThresholdT1(), task.getThresholdT2()
        );
        // AUD-02 + GAP-046：LLM 语义判定二审（候选复核架构——仅评审规则可疑/灰色带/高风险/一致池探针对，
        // 硬上限 candidate-review.max-candidates（万行 × ≈2.1s/对 ≈ 7min 二审预算）；探针缺陷率超阈值
        // 自动升级补审（小数据集趋近全量保指标，大数据集恒定有界）。未入选/失败对保留规则判定）
        if (llmService.isEnabled()) {
            Map<Long, Requirement> reqById = new HashMap<>();
            for (Requirement r : requirements) reqById.put(r.getId(), r);
            Map<Long, CodeUnit> codeById = new HashMap<>();
            for (CodeUnit c : codeUnits) codeById.put(c.getId(), c);
            Map<Integer, ConsistencyResult> byIndex = new HashMap<>();
            for (int i = 0; i < results.size(); i++) byIndex.put(i, results.get(i));
            com.traceguard.config.LlmProperties.CandidateReview cr = llmService.getCandidateReview();

            // 预计算规则风险分（纯 CPU 无 LLM 调用），候选规划与分歧仲裁共用同一份
            Map<Integer, Double> riskByIndex = new HashMap<>();
            List<com.traceguard.core.CandidateReviewPlanner.PairInput> pairInputs = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                ConsistencyResult r = results.get(i);
                double risk = computePairRisk(reqById, codeById, classEvidenceByClass, r);
                riskByIndex.put(i, risk);
                pairInputs.add(new com.traceguard.core.CandidateReviewPlanner.PairInput(
                        (long) i, r.getConsistencyStatus(),
                        r.getTotalSimilarity() == null ? 0.0 : r.getTotalSimilarity(), risk));
            }
            // GAP-046：候选规划（enabled=false 退回全量逐条，仅评测对比使用；键=results 下标，入库前实体无 ID）
            com.traceguard.core.CandidateReviewPlanner.Plan plan = cr.isEnabled()
                    ? com.traceguard.core.CandidateReviewPlanner.plan(pairInputs, task.getThresholdT1(),
                            task.getThresholdT2(), cr.getHighRiskThreshold(),
                            cr.getConsistentSampleRate(), cr.getMaxCandidates())
                    : null;
            List<Integer> reviewOrder = new ArrayList<>();
            if (plan != null) {
                for (Long id : plan.getReviewOrder()) {
                    reviewOrder.add(id.intValue());
                }
            } else {
                for (int i = 0; i < results.size(); i++) {
                    reviewOrder.add(i);
                }
            }
            // 未入选候选的对：显式标记"候选模式未复核"（区别于规则模式的 NULL），判定溯源面板可见
            if (plan != null) {
                java.util.Set<Integer> selected = new java.util.HashSet<>(reviewOrder);
                for (int i = 0; i < results.size(); i++) {
                    if (!selected.contains(i)) {
                        results.get(i).setJudgePath("NOT_REVIEWED");
                    }
                }
            }

            int llmOk = 0, llmFail = 0;
            int probeJudged = 0, probeDefects = 0, escalatedCount = 0;
            int quota = llmService.getMaxCallsPerStage();
            for (Integer idx : reviewOrder) {
                // 4.5 整改：在每个 LLM 批次项之间插入控制检查点，缩短暂停/终止的协作式响应延迟
                checkControl(task);
                if (llmOk + llmFail >= quota) {
                    log.append("[").append(LocalDateTime.now())
                            .append("] [llm-stage-quota] 一致性判定环节已达调用上限，剩余候选保留规则判定\n");
                    break;
                }
                ConsistencyResult r = byIndex.get(idx);
                Requirement req = r == null ? null : reqById.get(r.getRequirementId());
                CodeUnit code = r == null ? null : codeById.get(r.getCodeUnitId());
                if (r == null || req == null || code == null) {
                    llmFail++;
                    continue;
                }
                String selectedReason = plan != null
                        ? plan.getSelectedReasons().getOrDefault((long) idx, "CANDIDATE")
                        : "FULL_REVIEW";
                Boolean verdict = reviewOnePair(task, r, req, code, classEvidenceByClass,
                        riskByIndex.getOrDefault(idx, 0.0), selectedReason, log);
                if (verdict == null) {
                    llmFail++;
                    continue;
                }
                llmOk++;
                if (com.traceguard.core.CandidateReviewPlanner.REASON_PROBE_SAMPLE.equals(selectedReason)) {
                    probeJudged++;
                    if (!verdict) {
                        probeDefects++;
                    }
                }
            }
            // GAP-046 升级补审：探针缺陷率超阈值 -> "明确一致池"并不干净，按确定性顺序补审剩余（受上限与配额约束）
            if (plan != null && com.traceguard.core.CandidateReviewPlanner.shouldEscalate(
                    probeDefects, probeJudged, cr.getEscalateDefectRate())) {
                int budget = Math.min(Math.max(cr.getMaxCandidates() - plan.getReviewOrder().size(), 0),
                        Math.max(quota - llmOk - llmFail, 0));
                for (Long id : com.traceguard.core.CandidateReviewPlanner.escalate(plan, budget)) {
                    if (llmOk + llmFail >= quota) {
                        break;
                    }
                    Integer idx = id.intValue();
                    ConsistencyResult r = byIndex.get(idx);
                    Requirement req = r == null ? null : reqById.get(r.getRequirementId());
                    CodeUnit code = r == null ? null : codeById.get(r.getCodeUnitId());
                    if (r == null || req == null || code == null) {
                        llmFail++;
                        continue;
                    }
                    Boolean verdict = reviewOnePair(task, r, req, code, classEvidenceByClass,
                            riskByIndex.getOrDefault(idx, 0.0),
                            com.traceguard.core.CandidateReviewPlanner.REASON_ESCALATED, log);
                    if (verdict == null) {
                        llmFail++;
                        continue;
                    }
                    llmOk++;
                    escalatedCount++;
                }
                log.append("[").append(LocalDateTime.now()).append("] [llm-candidate-escalate] 探针缺陷率 ")
                        .append(probeJudged > 0
                                ? String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * probeDefects / probeJudged)
                                : "N/A")
                        .append(" 超阈值，升级补审一致池 ").append(escalatedCount).append(" 对\n");
            }
            log.append("[").append(LocalDateTime.now()).append("] [llm] 语义判定二审完成（GAP-046 候选复核")
                    .append(plan != null ? "开启" : "关闭=全量逐条").append("）：全量 ")
                    .append(results.size()).append(" 对，LLM 复核 ").append(llmOk)
                    .append("（探针 ").append(probeJudged).append("），失败 ").append(llmFail)
                    .append("，规则判定保留 ").append(results.size() - llmOk).append(" 对\n");
        }
        // GAP-025：分批写库（默认每批500行），避免 R×C 结果逐条单写
        batchInsertConsistency(results);
        long consistent = results.stream().filter(r -> "consistent".equals(r.getConsistencyStatus())).count();
        log.append("[").append(LocalDateTime.now()).append("] 一致性校验完成，共").append(results.size())
                .append("个匹配对，其中一致").append(consistent).append("个\n");
        task.setExecutionLog(log.toString());
        taskMapper.updateById(task);
        return results;
    }

    /** GAP-046：单对规则风险分（CodeDefectPatternDetector，纯 CPU），需求/代码缺失时 0.0 */
    private double computePairRisk(Map<Long, Requirement> reqById, Map<Long, CodeUnit> codeById,
                                   Map<String, List<String>> classEvidenceByClass, ConsistencyResult r) {
        Requirement req = r.getRequirementId() == null ? null : reqById.get(r.getRequirementId());
        CodeUnit code = r.getCodeUnitId() == null ? null : codeById.get(r.getCodeUnitId());
        if (req == null || code == null) {
            return 0.0;
        }
        String cls = code.getClassName() == null ? "" : code.getClassName();
        String simpleCls = cls.substring(cls.lastIndexOf('.') + 1);
        return com.traceguard.util.CodeDefectPatternDetector.detectDefectRisk(
                req.getOriginalText(), code.getCodeContent(),
                classEvidenceByClass.getOrDefault(simpleCls, java.util.Collections.emptyList()));
    }

    /**
     * GAP-046：复核单个匹配对（FUN-04b 双判定管线 + A1 判定溯源落库）。
     *
     * @return Boolean.TRUE=判定一致 / FALSE=判定缺陷 / null=LLM 失败（保留规则判定）
     */
    private Boolean reviewOnePair(AnalysisTask task, ConsistencyResult r, Requirement req, CodeUnit code,
                                  Map<String, List<String>> classEvidenceByClass, double pairRisk,
                                  String selectedReason, StringBuilder log) {
        try {
            String cls = code.getClassName() == null ? "" : code.getClassName();
            String simpleCls = cls.substring(cls.lastIndexOf('.') + 1);
            ConsistencyJudge.JudgeContext ctx = ConsistencyJudge.JudgeContext.fromEvidence(
                    classEvidenceByClass.get(simpleCls), code.getMethodName());
            ConsistencyJudge.Judgement j = llmService.judgeConsistency(
                    req.getOriginalText(), code.getCodeContent(),
                    r.getSemanticSimilarity(), r.getConstraintMatchDegree(),
                    r.getInvariantSatisfaction(), r.getTotalSimilarity(), r.getDefectType(),
                    ctx, pairRisk);
            if (j == null) {
                r.setJudgePath("RULE_FALLBACK");
                r.setJudgeDetail(judgeDetailJson(null, pairRisk, selectedReason, "LLM调用失败，保留规则判定"));
                return null;
            }
            if (j.isConsistent()) {
                r.setConsistencyStatus("consistent");
                r.setDefectType(null);
                r.setDefectSubType(null);
            } else {
                r.setConsistencyStatus(r.getTotalSimilarity() != null
                                && r.getTotalSimilarity() < task.getThresholdT2()
                        ? "serious_inconsistent" : "general_inconsistent");
                r.setDefectType(j.getDefectType());
                r.setDefectSubType(j.getDefectType());
            }
            r.setJudgePath(j.getDecisionPath() == null ? "LLM" : j.getDecisionPath());
            r.setJudgeDetail(judgeDetailJson(j, pairRisk, selectedReason, j.getReason()));
            return j.isConsistent();
        } catch (Exception e) {
            log.append("[").append(LocalDateTime.now()).append("] [llm] 对 ")
                    .append(req.getRequirementId()).append(" 一致性判定失败：").append(e.getMessage())
                    .append("，保留规则判定\n");
            r.setJudgePath("RULE_FALLBACK");
            r.setJudgeDetail(judgeDetailJson(null, pairRisk, selectedReason, "LLM调用异常：" + e.getMessage()));
            return null;
        }
    }

    /** A1：判定溯源明细 JSON（变体A/B 结论、规则风险分、候选选取原因、判定摘要） */
    private String judgeDetailJson(ConsistencyJudge.Judgement j, double pairRisk,
                                   String selectedReason, String detail) {
        com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
        o.put("pairRisk", pairRisk);
        o.put("selectedReason", selectedReason);
        o.put("detail", detail == null ? "" : detail);
        if (j != null) {
            o.put("variantA", j.getVariantA());
            o.put("variantB", j.getVariantB());
            if (!Double.isNaN(j.getRuleRisk())) {
                o.put("ruleRisk", j.getRuleRisk());
            }
        }
        return o.toJSONString();
    }

    private void generateDefects(AnalysisTask task, Project project, List<ConsistencyResult> results,
                                  List<Requirement> requirements, List<CodeUnit> codeUnits, StringBuilder log,
                                  LlmUsage usage) {
        log.append("[").append(LocalDateTime.now()).append("] 开始生成缺陷报告...\n");
        List<Defect> defects = consistencyChecker.generateDefects(
                task.getId(), project.getId(), results, requirements, codeUnits
        );
        // GAP-001：LLM 增强缺陷解释（reason/suggestion），失败则保留规则生成结果
        if (llmService.isEnabled()) {
            log.append("[").append(LocalDateTime.now()).append("] [llm] 开始增强缺陷解释...\n");
            for (Defect d : defects) {
                // 4.5 整改：在每个 LLM 批次项之间插入控制检查点，缩短暂停/终止的协作式响应延迟
                checkControl(task);
                if (usage.isQuotaReached("defect-explain", llmService.getMaxCallsPerStage())) {
                    log.append("[").append(LocalDateTime.now()).append("] [llm-stage-quota] 缺陷解释环节已达调用上限，剩余缺陷保留规则文案\n");
                    break;
                }
                try {
                    String reqText = null;
                    if (d.getRequirementId() != null) {
                        for (Requirement r : requirements) {
                            if (r.getId().equals(d.getRequirementId())) {
                                reqText = r.getOriginalText();
                                break;
                            }
                        }
                    }
                    String codeSnippet = d.getCodeSnippet();
                    if (codeSnippet == null && d.getCodeUnitId() != null) {
                        for (CodeUnit u : codeUnits) {
                            if (u.getId().equals(d.getCodeUnitId())) {
                                codeSnippet = u.getCodeContent();
                                break;
                            }
                        }
                    }
                    Map<String, String> explanation = llmService.explainDefect(reqText, codeSnippet, d.getDefectType());
                    if (explanation != null && !explanation.isEmpty()) {
                        String reason = explanation.get("reason");
                        String suggestion = explanation.get("suggestion");
                        if (reason != null && !reason.trim().isEmpty()) {
                            d.setDefectReason(reason);
                        }
                        if (suggestion != null && !suggestion.trim().isEmpty()) {
                            d.setRepairSuggestion(suggestion);
                        }
                        usage.record("defect-explain", true);
                        log.append("[").append(LocalDateTime.now()).append("] [llm] 缺陷 ").append(d.getDefectId())
                                .append(" 解释增强成功\n");
                    } else {
                        usage.record("defect-explain", false);
                    }
                } catch (Exception e) {
                    usage.record("defect-explain", false);
                    log.append("[").append(LocalDateTime.now()).append("] [llm] 缺陷 ").append(d.getDefectId())
                            .append(" 解释增强失败：").append(e.getMessage()).append("，保留规则生成结果\n");
                }
            }
        }
        // P1-4：为缺陷写入规则信号分解（explainSignals）——供 Defects.vue「命中规则/信号贡献」面板展示
        try {
            com.fasterxml.jackson.databind.ObjectMapper signalMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<Long, Requirement> reqById = new HashMap<>();
            for (Requirement r : requirements) reqById.put(r.getId(), r);
            Map<Long, CodeUnit> codeById = new HashMap<>();
            for (CodeUnit u : codeUnits) codeById.put(u.getId(), u);
            String codePath = project.getCodeProjectPath();
            Map<String, List<String>> classEvidenceByClass = (codePath != null && new File(codePath).exists())
                    ? com.traceguard.util.ClassEvidenceScanner.scanConstants(codePath) : Collections.emptyMap();
            int withSignals = 0;
            for (Defect d : defects) {
                try {
                    if (d.getRequirementId() == null || d.getCodeUnitId() == null) continue;
                    Requirement req = reqById.get(d.getRequirementId());
                    CodeUnit code = codeById.get(d.getCodeUnitId());
                    if (req == null || code == null) continue;
                    // 需求文本与一致性打分同口径（原文+类型+约束规则），保证面板数值与 adjustedSim 扣分可对账
                    String reqText = com.traceguard.core.SimilarityScorer.reqDoc(req);
                    String codeText = code.getCodeContent();
                    String cls = code.getClassName() == null ? "" : code.getClassName();
                    String simpleCls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
                    List<String> classEvidence = classEvidenceByClass == null ? null : classEvidenceByClass.get(simpleCls);
                    Map<String, Double> signals = com.traceguard.util.CodeDefectPatternDetector
                            .explainSignals(reqText, codeText, classEvidence);
                    double risk = com.traceguard.util.CodeDefectPatternDetector
                            .detectDefectRisk(reqText, codeText, classEvidence);
                    Map<String, Object> box = new LinkedHashMap<>();
                    box.put("risk", risk);
                    box.put("signals", signals);
                    d.setRiskSignals(signalMapper.writeValueAsString(box));
                    withSignals++;
                } catch (Exception ignored) {
                    // 信号分解失败不影响缺陷主数据落库
                }
            }
            if (withSignals > 0) {
                log.append("[").append(LocalDateTime.now()).append("] [risk-signals] ").append(withSignals)
                        .append(" 个缺陷已写入规则信号分解（供前端信号面板）\n");
            }
        } catch (Exception e) {
            log.append("[").append(LocalDateTime.now()).append("] [risk-signals] 信号分解失败：")
                    .append(e.getMessage()).append("\n");
        }
        // GAP-025：分批写库（默认每批500行），避免逐条单写
        batchInsertDefects(defects);
        log.append("[").append(LocalDateTime.now()).append("] 缺陷报告生成完成，共发现").append(defects.size()).append("个缺陷\n");
        task.setExecutionLog(log.toString());
        taskMapper.updateById(task);
    }

    /** GAP-025：一致性结果分批写库（复用 BaseMapper.insert，兼容 id 生成与字段填充） */
    private void batchInsertConsistency(List<ConsistencyResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        int size = batchSize > 0 ? batchSize : 500;
        SqlHelper.executeBatch(ConsistencyResult.class, LogFactory.getLog(AnalysisService.class),
                results, size, (sqlSession, r) ->
                        SqlHelper.getMapper(ConsistencyResult.class, sqlSession).insert(r));
    }

    /** GAP-025：缺陷分批写库（复用 BaseMapper.insert） */
    private void batchInsertDefects(List<Defect> defects) {
        if (defects == null || defects.isEmpty()) {
            return;
        }
        int size = batchSize > 0 ? batchSize : 500;
        SqlHelper.executeBatch(Defect.class, LogFactory.getLog(AnalysisService.class),
                defects, size, (sqlSession, d) ->
                        SqlHelper.getMapper(Defect.class, sqlSession).insert(d));
    }

    /** 分析完成后回写项目统计数据（FR-STAT-001）：需求数/覆盖率/缺陷数 */
    private void updateProjectStats(Long projectId, Long taskId, List<Requirement> requirements, List<ConsistencyResult> results) {
        long totalReqs = requirements.size();
        long matchedReqs = results.stream()
                .filter(r -> "consistent".equals(r.getConsistencyStatus()))
                .map(ConsistencyResult::getRequirementId)
                .distinct()
                .count();
        double coverage = totalReqs > 0 ? (double) matchedReqs / totalReqs : 0;
        // 统计本次任务产生的缺陷总数（追溯缺陷 + 静态代码缺陷）
        Long traceDefects = defectMapper.selectCount(
                new LambdaQueryWrapper<Defect>().eq(Defect::getTaskId, taskId));
        Long codeDefects = codeDefectMapper.selectCount(
                new LambdaQueryWrapper<CodeDefect>().eq(CodeDefect::getTaskId, taskId));
        int defectTotal = (traceDefects == null ? 0 : traceDefects.intValue())
                + (codeDefects == null ? 0 : codeDefects.intValue());
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setStatus("analyzed");
            project.setRequirementCount((int) totalReqs);
            project.setCoverageRate(Math.round(coverage * 10000) / 10000.0);
            project.setDefectCount(defectTotal);
            projectMapper.updateById(project);
        }
    }

    /**
     * GAP-001：任务级 LLM 用量统计（阶段 -> calls/success/failed/degraded）与单环节配额。
     * 任务结束时由 runAnalysis 汇总写入 execution_log（[llm-usage] JSON 记录）。
     */
    static class LlmUsage {
        private final Map<String, int[]> perStage = new ConcurrentHashMap<>();

        /** 记录一次调用：成功 true / 失败 false（失败即降级规则实现） */
        void record(String stage, boolean success) {
            int[] c = perStage.computeIfAbsent(stage, k -> new int[3]);
            c[0]++;
            if (success) {
                c[1]++;
            } else {
                c[2]++;
            }
        }

        /** 单环节调用数是否已达上限（性能保护配额，GAP-001 接入点配额） */
        boolean isQuotaReached(String stage, int maxCallsPerStage) {
            int[] c = perStage.get(stage);
            return c != null && maxCallsPerStage > 0 && c[0] >= maxCallsPerStage;
        }

        boolean isEmpty() {
            return perStage.isEmpty();
        }

        /** 汇总为 JSON：{stage: {calls, success, failed, degraded}}；degraded=failed（失败即降级规则实现） */
        String toJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, int[]> e : perStage.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                int[] c = e.getValue();
                sb.append("\"").append(e.getKey()).append("\":{\"calls\":").append(c[0])
                        .append(",\"success\":").append(c[1])
                        .append(",\"failed\":").append(c[2])
                        .append(",\"degraded\":").append(c[2]).append("}");
            }
            sb.append("}");
            return sb.toString();
        }
    }
}
