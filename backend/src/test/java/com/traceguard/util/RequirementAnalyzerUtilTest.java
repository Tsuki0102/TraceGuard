package com.traceguard.util;

import com.traceguard.entity.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequirementAnalyzerUtil 单元测试：需求去重过滤与质量检测（矛盾/边界）
 */
@DisplayName("需求分析工具单元测试")
class RequirementAnalyzerUtilTest {

    private final RequirementAnalyzerUtil analyzer = new RequirementAnalyzerUtil();

    @Test
    @DisplayName("编号不同但内容相同的需求被去重（FR-REQ-001 业务规则2）")
    void duplicateRequirementWithDifferentNumberingFiltered() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L, Arrays.asList(
                "1. 系统应支持用户登录功能",
                "2、系统应支持用户登录功能",
                "系统应支持数据导出功能"));
        assertThat(requirements).hasSize(2);
        assertThat(requirements.get(0).getRequirementId()).isEqualTo("REQ-0001");
        assertThat(requirements.get(1).getOriginalText()).isEqualTo("系统应支持数据导出功能");
    }

    @Test
    @DisplayName("高度相似的冗余需求被去重，仅保留首条")
    void highlySimilarRequirementFiltered() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L, Arrays.asList(
                "用户登录后可修改密码",
                "用户登录后可以修改密码"));
        assertThat(requirements).hasSize(1);
    }

    @Test
    @DisplayName("同条需求内互斥词对被标记为潜在矛盾（FR-REQ-002）")
    void contradictionPairReported() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统必须记录操作日志且禁止任何外部导出与复制"));
        assertThat(requirements.get(0).getAmbiguityReport()).contains("潜在矛盾");
    }

    @Test
    @DisplayName("敏感操作无权限边界描述被提示（FR-REQ-002）")
    void sensitiveActionWithoutPermissionBoundaryReported() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统应支持删除历史记录"));
        assertThat(requirements.get(0).getAmbiguityReport()).contains("权限或角色边界");
    }

    @Test
    @DisplayName("含数值但无范围界定的需求被提示边界缺失")
    void numericValueWithoutRangeReported() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("密码长度为8位字符"));
        assertThat(requirements.get(0).getAmbiguityReport()).contains("数量边界");
    }

    @Test
    @DisplayName("已界定数值范围的需求不误报边界缺失")
    void numericValueWithRangeNotReported() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("密码长度不少于8位字符"));
        assertThat(requirements.get(0).getAmbiguityReport()).doesNotContain("数量边界");
    }

    private static <T> List<T> singletonList(T item) {
        return Arrays.asList(item);
    }

    @Test
    @DisplayName("GAP-019：标题提取——去编号前缀取首句，超长截断")
    void titleExtractedFromFirstSentence() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("1. 系统应支持用户登录功能。登录成功后进入工作台"));
        assertThat(requirements.get(0).getTitle()).isEqualTo("系统应支持用户登录功能");
    }

    @Test
    @DisplayName("GAP-019：优先级关键词映射（必须→must / 重要→important / 可选→optional / 默认normal）")
    void priorityMappedByKeyword() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L, Arrays.asList(
                "系统必须记录所有操作日志",
                "系统应当提供数据导出功能",
                "系统可选支持短信通知",
                "系统提供基础搜索功能"));
        assertThat(requirements.get(0).getPriority()).isEqualTo("must");
        assertThat(requirements.get(1).getPriority()).isEqualTo("important");
        assertThat(requirements.get(2).getPriority()).isEqualTo("optional");
        assertThat(requirements.get(3).getPriority()).isEqualTo("normal");
    }

    @Test
    @DisplayName("GAP-019：来源文件名与起始序号——多文档逐文件解析保持 REQ 全局连续")
    void sourceFileAndStartIndex() {
        List<Requirement> doc1 = analyzer.analyzeRequirements(1L,
                Arrays.asList("系统支持用户登录", "系统支持数据导出"), "requirements1.md", 1);
        List<Requirement> doc2 = analyzer.analyzeRequirements(1L,
                Arrays.asList("系统支持报表统计"), "requirements2.md", 3);
        assertThat(doc1).hasSize(2);
        assertThat(doc1.get(0).getSourceFile()).isEqualTo("requirements1.md");
        assertThat(doc1.get(1).getSourceFile()).isEqualTo("requirements1.md");
        assertThat(doc1.get(1).getRequirementId()).isEqualTo("REQ-0002");
        assertThat(doc2.get(0).getSourceFile()).isEqualTo("requirements2.md");
        assertThat(doc2.get(0).getRequirementId()).isEqualTo("REQ-0003");
    }

    @Test
    @DisplayName("GAP-045：含数值边界的需求提取原子命题(AP)并构造状态标签函数(L)")
    void gap045AtomicPropositionsAndLabelingExtracted() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统必须记录所有操作日志，且密码长度不少于8位字符"));
        Requirement req = requirements.get(0);
        assertThat(req.getAtomicPropositions()).isNotNull();
        assertThat(req.getAtomicPropositions()).contains("密码长度≥8");
        assertThat(req.getAtomicPropositions()).contains("必须记录所有操作日志");
        // 状态标签函数 L(state)={为真的AP}
        assertThat(req.getStateLabeling()).contains("\"state\":\"initial\"");
        assertThat(req.getStateLabeling()).contains("\"labels\"");
        assertThat(req.getStateLabeling()).contains("密码长度≥8");
    }

    @Test
    @DisplayName("GAP-045：无约束的需求 AP 为空列表、L 不含标签")
    void gap045NoConstraintYieldsEmptyAp() {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统提供基础搜索功能"));
        Requirement req = requirements.get(0);
        assertThat(req.getAtomicPropositions()).isEqualTo("[]");
        assertThat(req.getStateLabeling()).isEqualTo("[{\"state\":\"initial\",\"labels\":[]}]");
    }

    // ==================== 2.2 整改项：歧义检测报告结构化 JSON ====================

    @Test
    @DisplayName("2.2 质量报告为结构化 JSON：包含类型/严重度/原文片段/建议")
    void qualityReportIsStructuredJson() throws Exception {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统应当适当允许删除历史记录"));
        Requirement req = requirements.get(0);
        com.fasterxml.jackson.databind.JsonNode report =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(req.getAmbiguityReport());
        assertThat(report.get("hasIssue").asBoolean()).isTrue();
        assertThat(report.get("issueCount").asInt()).isGreaterThan(0);
        assertThat(report.has("summary")).isTrue();
        com.fasterxml.jackson.databind.JsonNode issues = report.get("issues");
        assertThat(issues.isArray()).isTrue();
        assertThat(issues.size()).isGreaterThan(0);
        for (com.fasterxml.jackson.databind.JsonNode issue : issues) {
            assertThat(issue.has("type")).isTrue();
            assertThat(issue.has("severity")).isTrue();
            assertThat(issue.has("keyword")).isTrue();
            assertThat(issue.has("excerpt")).isTrue();
            assertThat(issue.has("suggestion")).isTrue();
            assertThat(issue.has("message")).isTrue();
        }
    }

    @Test
    @DisplayName("2.2 结构化报告保留旧文本关键词（潜在矛盾/权限边界/数量边界）")
    void qualityReportKeepsLegacyKeywords() throws Exception {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L, Arrays.asList(
                "系统必须记录操作日志且禁止任何外部导出与复制",
                "系统应支持删除历史记录",
                "密码长度为8位字符"));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode r1 = om.readTree(requirements.get(0).getAmbiguityReport());
        assertThat(r1.get("summary").asText()).contains("潜在矛盾");
        assertThat(r1.get("issues").get(0).get("type").asText()).isEqualTo("contradiction");
        assertThat(r1.get("issues").get(0).get("severity").asText()).isEqualTo("high");
        assertThat(r1.get("issues").get(0).has("suggestion")).isTrue();

        com.fasterxml.jackson.databind.JsonNode r2 = om.readTree(requirements.get(1).getAmbiguityReport());
        assertThat(r2.get("summary").asText()).contains("权限或角色边界");
        assertThat(r2.get("issues").get(0).get("type").asText()).isEqualTo("missing_boundary");

        com.fasterxml.jackson.databind.JsonNode r3 = om.readTree(requirements.get(2).getAmbiguityReport());
        assertThat(r3.get("summary").asText()).contains("数量边界");
        assertThat(r3.get("issues").get(0).get("type").asText()).isEqualTo("missing_boundary");
    }

    @Test
    @DisplayName("2.2 无问题需求输出 hasIssue=false 结构化 JSON")
    void qualityReportNoIssueYieldsEmptyStructure() throws Exception {
        List<Requirement> requirements = analyzer.analyzeRequirements(1L,
                singletonList("系统应支持用户登录，密码长度不少于8位字符"));
        Requirement req = requirements.get(0);
        com.fasterxml.jackson.databind.JsonNode report =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(req.getAmbiguityReport());
        assertThat(report.get("hasIssue").asBoolean()).isFalse();
        assertThat(report.get("issueCount").asInt()).isZero();
        assertThat(report.get("issues")).isEmpty();
        assertThat(report.get("summary").asText()).contains("未检测到明显歧义");
    }
}
