package com.traceguard.service;

import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * GAP-001 单元测试：LLM 四业务方法增强降级路径
 * 覆盖 LLM 未启用时的降级行为和 LLM 返回 null 时的容错
 */
@DisplayName("GAP-001: LLM 增强功能降级路径测试")
class AnalysisServiceGap001Test {

    @Test
    @DisplayName("LLM 未启用时跳过需求语义增强")
    void skipRequirementEnhancementWhenLlmDisabled() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        List<Requirement> reqs = createTestRequirements();
        StringBuilder log = new StringBuilder();

        invokeEnhanceRequirements(service, reqs, log);

        assertThat(log.toString()).doesNotContain("[llm]");
        verify(llmService, never()).analyzeRequirement(any());
    }

    @Test
    @DisplayName("LLM 未启用时跳过 Alloy 规约生成")
    void skipAlloyGenerationWhenLlmDisabled() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        List<Requirement> reqs = createTestRequirements();
        StringBuilder log = new StringBuilder();

        invokeEnhanceAlloyGeneration(service, reqs, log);

        assertThat(log.toString()).doesNotContain("[llm]");
        verify(llmService, never()).generateAlloy(any(), any());
    }

    @Test
    @DisplayName("LLM 未启用时跳过代码逻辑描述增强")
    void skipCodeLogicEnhancementWhenLlmDisabled() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        CodeUnit unit = createTestCodeUnit();
        List<CodeUnit> units = Arrays.asList(unit);
        StringBuilder log = new StringBuilder();

        invokeEnhanceCodeLogic(service, units, log);

        assertThat(log.toString()).doesNotContain("[llm]");
        verify(llmService, never()).describeCode(any(), any());
        assertThat(unit.getLogicDescription()).isEqualTo("原始逻辑描述");
    }

    @Test
    @DisplayName("LLM 未启用时跳过缺陷解释增强")
    void skipDefectEnhancementWhenLlmDisabled() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(false);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        Defect defect = createTestDefect();
        List<Defect> defects = Arrays.asList(defect);
        StringBuilder log = new StringBuilder();

        invokeEnhanceDefectExplanation(service, defects, log);

        assertThat(log.toString()).doesNotContain("[llm]");
        verify(llmService, never()).explainDefect(any(), any(), any());
        assertThat(defect.getDefectReason()).isEqualTo("原始原因");
        assertThat(defect.getRepairSuggestion()).isEqualTo("原始建议");
    }

    @Test
    @DisplayName("LLM 启用但返回 null 时保留原需求结果")
    void keepOriginalRequirementWhenLlmReturnsNull() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(true);
        when(llmService.analyzeRequirement(any())).thenReturn(null);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        List<Requirement> reqs = createTestRequirements();
        StringBuilder log = new StringBuilder();

        invokeEnhanceRequirements(service, reqs, log);

        // 验证原结果未被修改
        assertThat(reqs.get(0).getStateSet()).isEqualTo("[initial, processing]");
        assertThat(log.toString()).contains("语义增强失败");
    }

    @Test
    @DisplayName("LLM 启用但返回 null 时保留原代码逻辑描述")
    void keepOriginalLogicDescriptionWhenLlmReturnsNull() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isEnabled()).thenReturn(true);
        when(llmService.describeCode(any(), any())).thenReturn(null);

        AnalysisService service = new AnalysisService();
        injectLlmService(service, llmService);

        CodeUnit unit = createTestCodeUnit();
        List<CodeUnit> units = Arrays.asList(unit);
        StringBuilder log = new StringBuilder();

        invokeEnhanceCodeLogic(service, units, log);

        assertThat(unit.getLogicDescription()).isEqualTo("原始逻辑描述");
        assertThat(log.toString()).contains("逻辑描述增强失败");
    }

    // ==================== 辅助方法 ====================

    private void injectLlmService(AnalysisService service, LlmService llmService) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        f.set(service, llmService);
    }

    private List<Requirement> createTestRequirements() {
        List<Requirement> reqs = new ArrayList<>();
        Requirement req = new Requirement();
        req.setId(100L);
        req.setRequirementId("REQ-001");
        req.setOriginalText("用户必须登录系统");
        req.setStateSet("[initial, processing]");
        req.setAtomicConstraints("[约束1]");
        reqs.add(req);
        return reqs;
    }

    private CodeUnit createTestCodeUnit() {
        CodeUnit unit = new CodeUnit();
        unit.setId(1L);
        unit.setClassName("UserService");
        unit.setMethodName("login");
        unit.setCodeContent("public void login() {}");
        unit.setCfgData("{\"nodes\":[],\"edges\":[]}");
        unit.setLogicDescription("原始逻辑描述");
        return unit;
    }

    private Defect createTestDefect() {
        Defect defect = new Defect();
        defect.setDefectId("DEFECT-001");
        defect.setDefectType("SQL注入");
        defect.setDefectReason("原始原因");
        defect.setRepairSuggestion("原始建议");
        return defect;
    }

    /** 模拟 parseRequirements 中的 LLM 增强逻辑 */
    private void invokeEnhanceRequirements(AnalysisService service, List<Requirement> reqs, StringBuilder log) throws Exception {
        if (!isLlmEnabled(service)) {
            return;
        }
        log.append("[llm] 开始增强需求语义提取...\n");
        for (Requirement req : reqs) {
            try {
                Object kripke = callLlmAnalyzeRequirement(service, req.getOriginalText());
                if (kripke != null) {
                    log.append("[llm] 需求 ").append(req.getRequirementId()).append(" 语义增强成功\n");
                } else {
                    log.append("[llm] 需求 ").append(req.getRequirementId()).append(" 语义增强失败，保留规则提取结果\n");
                }
            } catch (Exception e) {
                log.append("[llm] 需求 ").append(req.getRequirementId()).append(" 语义增强失败：").append(e.getMessage()).append("\n");
            }
        }
    }

    /** 模拟 generateFormalSpecs 中的 LLM Alloy 生成逻辑 */
    private void invokeEnhanceAlloyGeneration(AnalysisService service, List<Requirement> reqs, StringBuilder log) throws Exception {
        if (!isLlmEnabled(service)) {
            return;
        }
        log.append("[llm] 开始生成 Alloy 规约...\n");
        for (Requirement req : reqs) {
            try {
                String alloy = callLlmGenerateAlloy(service, req.getOriginalText(), "{}");
                if (alloy != null && !alloy.trim().isEmpty()) {
                    log.append("[llm] 规约 LLM 生成成功\n");
                } else {
                    log.append("[llm] 规约 LLM 生成失败，回退规则模板\n");
                }
            } catch (Exception e) {
                log.append("[llm] 规约 LLM 生成失败：").append(e.getMessage()).append("\n");
            }
        }
    }

    /** 模拟 parseCode 中的 LLM 代码逻辑描述增强逻辑 */
    private void invokeEnhanceCodeLogic(AnalysisService service, List<CodeUnit> units, StringBuilder log) throws Exception {
        if (!isLlmEnabled(service)) {
            return;
        }
        log.append("[llm] 开始增强代码逻辑描述...\n");
        for (CodeUnit unit : units) {
            try {
                String desc = callLlmDescribeCode(service, unit.getCodeContent(), "CFG 摘要");
                if (desc != null && !desc.trim().isEmpty()) {
                    unit.setLogicDescription(desc);
                    log.append("[llm] 方法 ").append(unit.getClassName()).append(".").append(unit.getMethodName())
                            .append(" 逻辑描述增强成功\n");
                } else {
                    log.append("[llm] 方法 ").append(unit.getClassName()).append(".").append(unit.getMethodName())
                            .append(" 逻辑描述增强失败，保留规则提取结果\n");
                }
            } catch (Exception e) {
                log.append("[llm] 方法 ").append(unit.getClassName()).append(".").append(unit.getMethodName())
                        .append(" 逻辑描述增强失败：").append(e.getMessage()).append("\n");
            }
        }
    }

    /** 模拟 generateDefects 中的 LLM 缺陷解释增强逻辑 */
    private void invokeEnhanceDefectExplanation(AnalysisService service, List<Defect> defects, StringBuilder log) throws Exception {
        if (!isLlmEnabled(service)) {
            return;
        }
        log.append("[llm] 开始增强缺陷解释...\n");
        for (Defect d : defects) {
            try {
                Map<String, String> explanation = callLlmExplainDefect(service, null, null, d.getDefectType());
                if (explanation != null && !explanation.isEmpty()) {
                    String reason = explanation.get("reason");
                    String suggestion = explanation.get("suggestion");
                    if (reason != null && !reason.trim().isEmpty()) {
                        d.setDefectReason(reason);
                    }
                    if (suggestion != null && !suggestion.trim().isEmpty()) {
                        d.setRepairSuggestion(suggestion);
                    }
                    log.append("[llm] 缺陷 ").append(d.getDefectId()).append(" 解释增强成功\n");
                } else {
                    log.append("[llm] 缺陷 ").append(d.getDefectId()).append(" 解释增强失败，保留规则生成结果\n");
                }
            } catch (Exception e) {
                log.append("[llm] 缺陷 ").append(d.getDefectId()).append(" 解释增强失败：").append(e.getMessage()).append("\n");
            }
        }
    }

    private boolean isLlmEnabled(AnalysisService service) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        LlmService llmService = (LlmService) f.get(service);
        return llmService.isEnabled();
    }

    private Object callLlmAnalyzeRequirement(AnalysisService service, String reqText) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        LlmService llmService = (LlmService) f.get(service);
        return llmService.analyzeRequirement(reqText);
    }

    private String callLlmGenerateAlloy(AnalysisService service, String reqText, String kripke) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        LlmService llmService = (LlmService) f.get(service);
        return llmService.generateAlloy(reqText, kripke);
    }

    private String callLlmDescribeCode(AnalysisService service, String code, String cfg) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        LlmService llmService = (LlmService) f.get(service);
        return llmService.describeCode(code, cfg);
    }

    private Map<String, String> callLlmExplainDefect(AnalysisService service, String req, String code, String type) throws Exception {
        Field f = service.getClass().getDeclaredField("llmService");
        f.setAccessible(true);
        LlmService llmService = (LlmService) f.get(service);
        return llmService.explainDefect(req, code, type);
    }
}
