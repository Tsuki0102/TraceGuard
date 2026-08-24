package com.traceguard.service;

import com.traceguard.common.BusinessException;
import com.traceguard.entity.AnalysisTask;
import com.traceguard.entity.Project;
import com.traceguard.entity.Requirement;
import com.traceguard.mapper.AnalysisTaskMapper;
import com.traceguard.mapper.FormalSpecificationMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.mapper.RequirementMapper;
import com.traceguard.spi.AlloySpecGeneratorAdapter;
import com.traceguard.spi.AlloySpecVerifierAdapter;
import com.traceguard.spi.SpecRegistry;
import com.traceguard.util.DocumentParserUtil;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.RequirementAnalyzerUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnalysisService 单元测试
 * 覆盖：单文档大小校验（FR-REQ-001）、形式化规约校验失败阻断开关（FR-REQ-004）
 */
@DisplayName("分析服务单元测试")
class AnalysisServiceTest {

    @Test
    @DisplayName("超过50MB的文档被拒绝并提示精确大小")
    void oversizedDocumentRejected() {
        assertThatThrownBy(() -> AnalysisService.validateFileSize(
                AnalysisService.MAX_REQUIREMENT_FILE_SIZE + 1, "big.docx"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50MB")
                .hasMessageContaining("big.docx");
    }

    @Test
    @DisplayName("恰好50MB的文档放行（边界值含等于）")
    void exactLimitAccepted() {
        assertThatCode(() -> AnalysisService.validateFileSize(
                AnalysisService.MAX_REQUIREMENT_FILE_SIZE, "ok.docx"))
                .doesNotThrowAnyException();
    }

    // ==================== FR-REQ-004：规约校验失败阻断开关 ====================

    @Test
    @DisplayName("FR-REQ-004 / GAP-013：strict-spec-verify 默认开启（true）")
    void strictSpecVerifyEnabledByDefault() throws Exception {
        InputStream in = AnalysisServiceTest.class.getResourceAsStream("/application.yml");
        assertThat(in).as("classpath 应包含主配置 application.yml").isNotNull();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        in.close();
        assertThat(sb.toString()).contains("strict-spec-verify: true");
    }

    @Test
    @DisplayName("严格模式：规约校验失败抛异常阻断并提示配置项")
    void strictModeBlocksOnFailedSpec() throws Exception {
        AnalysisService service = buildServiceWithMocks(true);
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(1L);
        Requirement req = new Requirement();
        req.setId(100L);
        req.setRequirementId("REQ-001");

        StringBuilder log = new StringBuilder();
        assertThatThrownBy(() -> service.generateFormalSpecs(task, Collections.singletonList(req), log))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("阻断")
                .hasMessageContaining("SPEC-REQ-001")
                .hasMessageContaining("FR-REQ-004");
    }

    @Test
    @DisplayName("默认模式：规约校验失败不阻断，日志记录失败数后继续")
    void lenientModeContinuesOnFailedSpec() throws Exception {
        AnalysisService service = buildServiceWithMocks(false);
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(1L);
        Requirement req = new Requirement();
        req.setId(100L);
        req.setRequirementId("REQ-001");

        StringBuilder log = new StringBuilder();
        assertThatCode(() -> service.generateFormalSpecs(task, Collections.singletonList(req), log))
                .doesNotThrowAnyException();
        assertThat(log.toString()).contains("失败1");
    }

    // ==================== GAP-014：ParseFailure 异常分类映射 ====================

    @Test
    @DisplayName("GAP-014：加密文档异常归类为'文档已加密'并给出解除密码建议")
    void parseFailureEncryptedDocument() {
        ParseFailure pf = ParseFailure.fromException("secret.docx",
                new RuntimeException("该文档设置了密码保护"), 1024);
        assertThat(pf.fileName()).isEqualTo("secret.docx");
        assertThat(pf.reason()).contains("加密");
        assertThat(pf.suggestion()).contains("密码");
        assertThat(pf.fileSize()).isEqualTo(1024);
    }

    @Test
    @DisplayName("GAP-014：超限文档归类为'超出50MB上限'")
    void parseFailureOversizedDocument() {
        ParseFailure pf = ParseFailure.fromException("big.pdf",
                new RuntimeException("文件大小超出限制"), 60L * 1024 * 1024);
        assertThat(pf.reason()).contains("50MB");
    }

    @Test
    @DisplayName("GAP-014：空内容文档归类为'未解析到需求条目'")
    void parseFailureEmptyContent() {
        ParseFailure pf = ParseFailure.fromException("empty.docx",
                new RuntimeException("未解析到任何内容"), 2048);
        assertThat(pf.reason()).contains("未解析到");
    }

    @Test
    @DisplayName("GAP-014：损坏文档归类为'文件损坏'")
    void parseFailureCorruptDocument() {
        ParseFailure pf = ParseFailure.fromException("corrupt.docx",
                new RuntimeException("Invalid file format or corrupt"), 4096);
        assertThat(pf.reason()).contains("损坏");
    }

    @Test
    @DisplayName("GAP-014：未知异常归入'解析异常'并截断原始message")
    void parseFailureUnknownException() {
        String longMsg = "A".repeat(200);
        ParseFailure pf = ParseFailure.fromException("unknown.bin",
                new RuntimeException(longMsg), 512);
        assertThat(pf.reason()).startsWith("解析异常");
        assertThat(pf.reason().length()).isLessThanOrEqualTo("解析异常：".length() + 100);
    }

    @Test
    @DisplayName("GAP-014：3文档批量（1坏+2好）单文件失败隔离，任务完成且失败清单恰含1条")
    void parseRequirementsIsolatesBadFile() throws Exception {
        // 1 个损坏文件 + 2 个正常文件
        Path tmp = Files.createTempDirectory("traceguard-gap014");
        Path bad = tmp.resolve("bad.txt");
        Files.writeString(bad, "corrupt content");
        Path good1 = tmp.resolve("good1.txt");
        Files.writeString(good1, "REQ-001: 系统应支持用户登录。\n");
        Path good2 = tmp.resolve("good2.txt");
        Files.writeString(good2, "REQ-002: 系统应支持订单查询。\n");

        Project project = new Project();
        project.setId(1L);
        project.setRequirementFilePath(bad + "," + good1 + "," + good2);

        AnalysisService service = new AnalysisService();
        FileStorageUtil fsUtil = mock(FileStorageUtil.class);
        when(fsUtil.ensurePlainFile(anyString())).thenAnswer(inv -> new File((String) inv.getArgument(0)));
        DocumentParserUtil docParser = mock(DocumentParserUtil.class);
        when(docParser.parseDocument(bad.toString()))
                .thenThrow(new RuntimeException("Invalid file format or corrupt"));
        when(docParser.parseDocument(good1.toString())).thenReturn("REQ-001: 系统应支持用户登录。\n");
        when(docParser.parseDocument(good2.toString())).thenReturn("REQ-002: 系统应支持订单查询。\n");
        when(docParser.splitRequirements(anyString())).thenAnswer(inv -> {
            String content = inv.getArgument(0);
            return Arrays.stream(content.split("\n")).filter(l -> l.trim().startsWith("REQ-"))
                    .map(String::trim).toList();
        });
        RequirementMapper reqMapper = mock(RequirementMapper.class);
        ProjectMapper projMapper = mock(ProjectMapper.class);
        AnalysisTaskMapper taskMapper = mock(AnalysisTaskMapper.class);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);
        setField(service, "fileStorageUtil", fsUtil);
        setField(service, "documentParserUtil", docParser);
        setField(service, "requirementMapper", reqMapper);
        setField(service, "projectMapper", projMapper);
        setField(service, "taskMapper", taskMapper);
        setField(service, "requirementAnalyzerUtil", new RequirementAnalyzerUtil());
        setField(service, "llmService", llmService);

        StringBuilder log = new StringBuilder();
        AnalysisTask task = new AnalysisTask();
        List<Requirement> reqs = invokeParseRequirements(service, task, project, log);

        // 正常文档条目全部解析成功，任务未中断
        assertThat(reqs).hasSize(2);
        assertThat(reqs.get(0).getOriginalText()).contains("登录");
        assertThat(reqs.get(1).getOriginalText()).contains("订单查询");
        // parse_failures 恰含 1 条（损坏文件），reason/suggestion 正确
        assertThat(project.getParseFailures())
                .contains("bad.txt")
                .contains("损坏")
                .contains("重新导出");
        assertThat(log.toString()).contains("解析失败文件数: 1");
        verify(reqMapper, atLeast(2)).insert(any(Requirement.class));
    }

    /** 反射调用私有 parseRequirements（同包测试无法直接访问 private 方法） */
    @SuppressWarnings("unchecked")
    private static List<Requirement> invokeParseRequirements(AnalysisService service, AnalysisTask task,
                                                            Project project, StringBuilder log) throws Exception {
        Method m = AnalysisService.class.getDeclaredMethod("parseRequirements",
                AnalysisTask.class, Project.class, StringBuilder.class, AnalysisService.LlmUsage.class);
        m.setAccessible(true);
        return (List<Requirement>) m.invoke(service, task, project, log, new AnalysisService.LlmUsage());
    }

    /**
     * 构造注入了 mock 依赖的 AnalysisService（generateFormalSpecs 为包私有方法，同包直调）
     * 规约生成被 mock 为空串 -> AlloySpecVerifierUtil 判定 failed，用于构造校验失败场景
     */
    private AnalysisService buildServiceWithMocks(boolean strict) throws Exception {
        AnalysisService service = new AnalysisService();
        FormalSpecificationMapper specMapper = mock(FormalSpecificationMapper.class);
        RequirementMapper requirementMapper = mock(RequirementMapper.class);
        AnalysisTaskMapper taskMapper = mock(AnalysisTaskMapper.class);
        RequirementAnalyzerUtil analyzerUtil = mock(RequirementAnalyzerUtil.class);
        // GAP-001：generateFormalSpecs 接入 llmService.isEnabled()，测试注入关闭态 mock 避免 NPE
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);
        setField(service, "specMapper", specMapper);
        setField(service, "requirementMapper", requirementMapper);
        setField(service, "taskMapper", taskMapper);
        setField(service, "requirementAnalyzerUtil", analyzerUtil);
        setField(service, "llmService", llmService);
        setField(service, "strictSpecVerify", strict);

        // 规约数量为 0：不触发断点续跑分支
        when(specMapper.selectCount(any())).thenReturn(0L);
        // 空规约 -> AlloySpecVerifierUtil.verify 返回 failed（规约内容为空）
        when(analyzerUtil.generateAlloySpec(any(Requirement.class))).thenReturn("");
        // AUD-10：注入 SPI 注册表（默认 alloy），生成器包装 mock 的 analyzerUtil，验证器走真实结构校验
        SpecRegistry specRegistry = new SpecRegistry(
                java.util.List.of(new AlloySpecGeneratorAdapter(analyzerUtil)),
                java.util.List.of(new AlloySpecVerifierAdapter()));
        setField(service, "specRegistry", specRegistry);
        setField(service, "specLanguage", "alloy");
        return service;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
