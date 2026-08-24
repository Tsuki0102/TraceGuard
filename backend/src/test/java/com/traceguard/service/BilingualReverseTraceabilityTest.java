package com.traceguard.service;

import com.traceguard.core.ConsistencyChecker;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.mapper.CodeUnitMapper;
import com.traceguard.mapper.ConsistencyResultMapper;
import com.traceguard.mapper.DefectMapper;
import com.traceguard.mapper.RequirementMapper;
import com.traceguard.util.JavaCodeParserUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 中英文混合需求端到端测试：真实Java代码解析 -> 语义向量 -> 跨语言一致性匹配 -> 反向追溯矩阵
 * 覆盖三种需求形态：纯中文 / 中英混排 / 纯英文，验证语义向量与反向追溯矩阵的实际效果
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("中英文混合需求下语义向量与反向追溯矩阵端到端测试")
class BilingualReverseTraceabilityTest {

    private final ConsistencyChecker checker = new ConsistencyChecker();
    private final JavaCodeParserUtil parserUtil = new JavaCodeParserUtil();

    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private CodeUnitMapper codeUnitMapper;
    @Mock
    private ConsistencyResultMapper consistencyMapper;
    @Mock
    private DefectMapper defectMapper;
    @InjectMocks
    private ResultService resultService;

    @TempDir
    Path tempDir;

    private static final double ALPHA = 0.4, BETA = 0.35, GAMMA = 0.25;
    private static final double T1 = 0.8, T2 = 0.5;

    // ==================== 数据构造 ====================

    /** 真实解析四份Java代码，生成带语义向量的代码单元（id从10起） */
    private List<CodeUnit> parseAll() throws Exception {
        List<CodeUnit> units = new ArrayList<>();
        units.addAll(parserUtil.parseFile(writeJavaFile("UserService.java",
                "public class UserService {\n" +
                "    public void saveUser(User user) {\n" +
                "        if (user.getPassword() == null) {\n" +
                "            throw new IllegalArgumentException(\"密码不能为空\");\n" +
                "        }\n" +
                "        userMapper.insert(user);\n" +
                "    }\n" +
                "}\n"), tempDir.toString()));
        units.addAll(parserUtil.parseFile(writeJavaFile("OrderQueryService.java",
                "public class OrderQueryService {\n" +
                "    public List<String> queryOrders(String keyword) {\n" +
                "        List<String> orders = new ArrayList<>();\n" +
                "        for (Order o : orderMapper.selectList(keyword)) {\n" +
                "            if (o.getStatus().equals(\"PAID\")) {\n" +
                "                orders.add(o.toString());\n" +
                "            }\n" +
                "        }\n" +
                "        orders.sort(Comparator.naturalOrder());\n" +
                "        return orders;\n" +
                "    }\n" +
                "}\n"), tempDir.toString()));
        units.addAll(parserUtil.parseFile(writeJavaFile("ReportService.java",
                "public class ReportService {\n" +
                "    public void exportReport(String month) {\n" +
                "        List<String> rows = reportDao.queryByMonth(month);\n" +
                "        reportExporter.exportExcel(rows);\n" +
                "        MailUtil.send(\"admin@example.com\", \"monthly report\");\n" +
                "    }\n" +
                "}\n"), tempDir.toString()));
        units.addAll(parserUtil.parseFile(writeJavaFile("ImageUtil.java",
                "public class ImageUtil {\n" +
                "    public BufferedImage resizeImage(BufferedImage src, int w, int h) {\n" +
                "        return src;\n" +
                "    }\n" +
                "}\n"), tempDir.toString()));
        long id = 10L;
        for (CodeUnit u : units) {
            u.setId(id++);
        }
        return units;
    }

    private File writeJavaFile(String name, String content) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /** 中英文混合需求：REQ-001纯中文 / REQ-002中英混排 / REQ-003纯英文 */
    private List<Requirement> bilingualRequirements() {
        return List.of(
                req(1L, "REQ-001", "系统应当支持保存用户信息并校验登录密码"),
                req(2L, "REQ-002", "系统支持根据关键词 query 查询订单 order 列表并 sort 排序展示"),
                req(3L, "REQ-003", "System shall export monthly report to excel and send email notification to admin"));
    }

    private Requirement req(Long id, String reqId, String text) {
        Requirement r = new Requirement();
        r.setId(id);
        r.setRequirementId(reqId);
        r.setOriginalText(text);
        r.setRequirementType("功能需求");
        return r;
    }

    private Map<String, CodeUnit> byMethod(List<CodeUnit> units) {
        return units.stream().collect(Collectors.toMap(CodeUnit::getMethodName, Function.identity()));
    }

    /** 每个代码单元相似度最高的需求的业务编号（如 REQ-001），实际计算值 */
    private String bestReqId(List<Requirement> reqs, List<CodeUnit> units,
                             List<ConsistencyResult> results, String methodName) {
        Map<Long, String> reqNoById = reqs.stream()
                .collect(Collectors.toMap(Requirement::getId, Requirement::getRequirementId));
        CodeUnit unit = byMethod(units).get(methodName);
        ConsistencyResult best = results.stream()
                .filter(r -> r.getCodeUnitId().equals(unit.getId()))
                .max(Comparator.comparing(ConsistencyResult::getTotalSimilarity))
                .orElseThrow();
        return reqNoById.get(best.getRequirementId());
    }

    /** 打印相似度矩阵便于观察（仅诊断输出，不影响断言） */
    private void dumpSimilarities(List<Requirement> reqs, List<ConsistencyResult> results, List<CodeUnit> units) {
        Map<Long, String> reqName = reqs.stream().collect(Collectors.toMap(Requirement::getId, Requirement::getRequirementId));
        for (CodeUnit u : units) {
            StringBuilder sb = new StringBuilder("  ").append(u.getMethodName()).append(" -> ");
            results.stream()
                    .filter(r -> r.getCodeUnitId().equals(u.getId()))
                    .sorted(Comparator.comparing(ConsistencyResult::getTotalSimilarity).reversed())
                    .forEach(r -> sb.append(reqName.get(r.getRequirementId()))
                            .append("=").append(String.format("%.3f", r.getTotalSimilarity()))
                            .append("(sem=").append(String.format("%.2f", r.getSemanticSimilarity()))
                            .append(",con=").append(String.format("%.2f", r.getConstraintMatchDegree()))
                            .append(",inv=").append(String.format("%.2f", r.getInvariantSatisfaction())).append(") "));
            System.out.println(sb);
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("语义向量从真实代码提取行为动词与结构特征")
    void semanticVectorFromRealCode() throws Exception {
        Map<String, CodeUnit> units = byMethod(parseAll());
        // saveUser: 方法调用getPassword/insert + if分支 + throw
        assertThat(units.get("saveUser").getSemanticVector())
                .contains("insert").contains("password").contains("branch").contains("throw");
        // queryOrders: selectList/getStatus/add/sort + for循环 + if分支 + return
        assertThat(units.get("queryOrders").getSemanticVector())
                .contains("select").contains("loop").contains("branch").contains("return");
        // exportReport: exportReport/reportDao/ExcelUtil/MailUtil -> export/send 等
        assertThat(units.get("exportReport").getSemanticVector())
                .contains("export").contains("send");
        // resizeImage: 仅return结构特征，无业务行为动词
        assertThat(units.get("resizeImage").getSemanticVector())
                .contains("return").doesNotContain("insert").doesNotContain("query");
    }

    @Test
    @DisplayName("中英文混合需求下每个代码单元最佳匹配需求正确（词典扩展生效）")
    void bestMatchAcrossLanguages() throws Exception {
        List<CodeUnit> units = parseAll();
        List<Requirement> reqs = bilingualRequirements();
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 900L, reqs, units, ALPHA, BETA, GAMMA, T1, T2);
        System.out.println("相似度矩阵（需求×代码配对）:");
        dumpSimilarities(reqs, results, units);
        // 中文需求 -> 英文代码（保存/校验/密码经词典扩展命中 save/insert/password/user）
        assertThat(bestReqId(reqs, units, results, "saveUser")).isEqualTo("REQ-001");
        // 中英混排需求 -> 查询/订单/排序命中 query/order/select/sort
        assertThat(bestReqId(reqs, units, results, "queryOrders")).isEqualTo("REQ-002");
        // 纯英文需求 -> 英文代码直配（export/report/excel/send/email）
        assertThat(bestReqId(reqs, units, results, "exportReport")).isEqualTo("REQ-003");
        // 无需求对应的代码：任何配对相似度都应显著低于被覆盖的方法
        CodeUnit extra = byMethod(units).get("resizeImage");
        double extraBest = results.stream().filter(r -> r.getCodeUnitId().equals(extra.getId()))
                .mapToDouble(ConsistencyResult::getTotalSimilarity).max().orElse(0);
        double coveredLowest = units.stream()
                .filter(u -> !"resizeImage".equals(u.getMethodName()))
                .flatMap(u -> results.stream().filter(r -> r.getCodeUnitId().equals(u.getId())))
                .mapToDouble(ConsistencyResult::getTotalSimilarity).max().orElse(1);
        assertThat(extraBest).isLessThan(coveredLowest - 0.05);
    }

    @Test
    @DisplayName("反向追溯矩阵：覆盖/超范围状态与中英文需求正确关联")
    void reverseTraceabilityMatrixOutput() throws Exception {
        List<CodeUnit> units = parseAll();
        List<Requirement> reqs = bilingualRequirements();
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 900L, reqs, units, ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 900L, results, reqs, units);

        // 桩住mapper，驱动 ResultService.getReverseTraceabilityMatrix
        when(requirementMapper.selectList(any())).thenReturn(reqs);
        when(codeUnitMapper.selectList(any())).thenReturn(units);
        when(consistencyMapper.selectList(any())).thenReturn(results);
        when(defectMapper.selectList(any())).thenReturn(defects);

        List<Map<String, Object>> matrix = resultService.getReverseTraceabilityMatrix(900L);
        Map<String, Map<String, Object>> byMethod = matrix.stream()
                .collect(Collectors.toMap(m -> (String) m.get("methodName"), Function.identity()));
        assertThat(byMethod).hasSize(4);

        // saveUser -> REQ-001（纯中文需求）
        assertThat(byMethod.get("saveUser").get("status")).isEqualTo("covered");
        assertThat(byMethod.get("saveUser").get("requirementId")).isEqualTo("REQ-001");
        assertThat((String) byMethod.get("saveUser").get("requirementText")).contains("保存用户");
        assertThat((Double) byMethod.get("saveUser").get("similarity")).isGreaterThan(0.0);
        assertThat(byMethod.get("saveUser").get("className")).isEqualTo("UserService");

        // queryOrders -> REQ-002（中英混排需求）
        assertThat(byMethod.get("queryOrders").get("status")).isEqualTo("covered");
        assertThat(byMethod.get("queryOrders").get("requirementId")).isEqualTo("REQ-002");

        // exportReport -> REQ-003（纯英文需求）
        assertThat(byMethod.get("exportReport").get("status")).isEqualTo("covered");
        assertThat(byMethod.get("exportReport").get("requirementId")).isEqualTo("REQ-003");
        assertThat((String) byMethod.get("exportReport").get("requirementText")).contains("export");

        // resizeImage 无需求对应 -> 超范围实现
        assertThat(byMethod.get("resizeImage").get("status")).isEqualTo("extra");
        assertThat(byMethod.get("resizeImage").get("requirementId")).isNull();
        assertThat((String) byMethod.get("resizeImage").get("repairSuggestion")).isNotBlank();
    }
}
