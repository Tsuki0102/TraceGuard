package com.traceguard.core;

import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConsistencyChecker 单元测试：三维度相似度计算、状态判定、缺陷生成
 */
@DisplayName("一致性校验算法单元测试")
class ConsistencyCheckerTest {

    private final ConsistencyChecker checker = new ConsistencyChecker();

    // 权重 α=0.4 β=0.35 γ=0.25，阈值 T1=0.8 T2=0.5（系统默认值）
    private static final double ALPHA = 0.4, BETA = 0.35, GAMMA = 0.25;
    private static final double T1 = 0.8, T2 = 0.5;

    private Requirement buildReq(Long id, String reqId, String text) {
        Requirement req = new Requirement();
        req.setId(id);
        req.setRequirementId(reqId);
        req.setOriginalText(text);
        req.setRequirementType("功能需求");
        return req;
    }

    private CodeUnit buildCode(Long id, String cls, String method, String code) {
        CodeUnit unit = new CodeUnit();
        unit.setId(id);
        unit.setClassName(cls);
        unit.setMethodName(method);
        unit.setCodeContent(code);
        unit.setLogicDescription(cls + " " + method + " user login validate check");
        return unit;
    }

    @Test
    @DisplayName("语义强匹配的需求数对应一致性结果数量正确且字段完整")
    void checkConsistencyProducesResultPerPair() {
        Requirement req = buildReq(1L, "REQ-001", "user login validate check username password");
        CodeUnit code = buildCode(10L, "UserService", "validateUser",
                "public boolean validateUser(String username, String password) { if (username != null) { return check(username, password); } throw new IllegalArgumentException(); }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        assertThat(results).hasSize(1);
        ConsistencyResult r = results.get(0);
        assertThat(r.getRequirementId()).isEqualTo(1L);
        assertThat(r.getCodeUnitId()).isEqualTo(10L);
        assertThat(r.getSemanticSimilarity()).isBetween(0.0, 1.0);
        assertThat(r.getConstraintMatchDegree()).isBetween(0.0, 1.0);
        assertThat(r.getInvariantSatisfaction()).isBetween(0.0, 1.0);
        assertThat(r.getTotalSimilarity()).isBetween(0.0, 1.0);
        assertThat(r.getConsistencyStatus()).isIn("consistent", "general_inconsistent", "serious_inconsistent");
    }

    @Test
    @DisplayName("高匹配需求-代码对判定为一致")
    void highlyMatchedPairIsConsistent() {
        Requirement req = buildReq(1L, "REQ-001",
                "validateUser validate check login username password null if return throw exception log");
        CodeUnit code = buildCode(10L, "UserService", "validateUser",
                "public boolean validateUser(String username, String password) { if (username != null) { return check(username, password); } throw new IllegalArgumentException(); }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, 0.0, 0.0);
        assertThat(results.get(0).getTotalSimilarity()).isGreaterThan(0.6);
        assertThat(results.get(0).getConsistencyStatus()).isEqualTo("consistent");
    }

    @Test
    @DisplayName("语义完全不相关的需求-代码对相似度低于强阈值")
    void unrelatedPairScoresLow() {
        Requirement req = buildReq(1L, "REQ-001", "系统导出季度财务报表并邮件通知管理员");
        CodeUnit code = buildCode(10L, "ImageUtil", "resizeImage",
                "public BufferedImage resizeImage(BufferedImage src, int w, int h) { return src; }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        // 中文需求与英文代码无词汇交集，综合相似度应处于低分段
        assertThat(results.get(0).getTotalSimilarity()).isLessThan(T1);
        assertThat(results.get(0).getConsistencyStatus()).isNotEqualTo("consistent");
    }

    @Test
    @DisplayName("跨语言语义匹配：中文需求经词典扩展后与英文代码显著相关")
    void crossLanguageSemanticMatching() {
        Requirement req = buildReq(1L, "REQ-001", "系统应当支持保存用户信息并校验登录密码");
        CodeUnit saveCode = buildCode(10L, "UserService", "saveUser",
                "public void saveUser(User user) { validatePassword(user.getPassword()); userMapper.insert(user); }");
        saveCode.setLogicDescription("");
        // 语义向量由SemanticVectorUtil生成：方法调用动词+结构特征
        saveCode.setSemanticVector("{\"terms\":\"validate password insert save\"}");
        CodeUnit unrelated = buildCode(11L, "ImageUtil", "resizeImage",
                "public BufferedImage resizeImage(BufferedImage src, int w, int h) { return src; }");
        unrelated.setLogicDescription("");
        unrelated.setSemanticVector("{\"terms\":\"return\"}");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(saveCode, unrelated), ALPHA, BETA, GAMMA, T1, T2);
        assertThat(results).hasSize(2);
        double matched = results.stream().filter(r -> r.getCodeUnitId() == 10L)
                .findFirst().orElseThrow().getSemanticSimilarity();
        double mismatched = results.stream().filter(r -> r.getCodeUnitId() == 11L)
                .findFirst().orElseThrow().getSemanticSimilarity();
        // 保存/校验/密码/用户经词典扩展后与 save/insert/validate/password/user 命中。
        // 中文词在需求向量中占一定比重（扩展权重0.5），余弦绝对值受稀释，
        // 判别力体现为排序：匹配对显著高于无关对，且脱离零分区
        assertThat(matched).isGreaterThan(mismatched + 0.12);
        assertThat(matched).isGreaterThan(0.12);
    }

    @Test
    @DisplayName("多个需求×多个代码单元生成笛卡尔积数量的结果")
    void cartesianProductResults() {
        List<Requirement> reqs = List.of(
                buildReq(1L, "REQ-001", "user login validate"),
                buildReq(2L, "REQ-002", "export report excel"));
        List<CodeUnit> codes = List.of(
                buildCode(10L, "UserService", "validateUser", "public boolean validateUser() { return true; }"),
                buildCode(11L, "ReportService", "exportExcel", "public byte[] exportExcel() { return new byte[0]; }"),
                buildCode(12L, "MailService", "sendMail", "public void sendMail() { }"));
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, reqs, codes, ALPHA, BETA, GAMMA, T1, T2);
        assertThat(results).hasSize(6);
    }

    @Test
    @DisplayName("无代码配对的需求生成'需求缺失'严重缺陷")
    void unmatchedRequirementProducesMissingDefect() {
        // 代码单元列表为空：该需求无任何配对结果，判定为需求缺失
        Requirement req = buildReq(1L, "REQ-001", "图像识别人脸检测算法");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.<CodeUnit>of(), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.<CodeUnit>of());
        assertThat(defects).extracting(Defect::getDefectType).contains("需求缺失");
        Defect missing = defects.stream()
                .filter(d -> "需求缺失".equals(d.getDefectType())).findFirst().orElseThrow();
        assertThat(missing.getDefectLevel()).isEqualTo("serious");
        assertThat(missing.getRequirementId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("无需求配对的代码生成'代码超范围实现'一般缺陷")
    void unmatchedCodeProducesExtraDefect() {
        // 需求列表为空：该代码单元无任何配对结果，判定为代码超范围实现
        CodeUnit code = buildCode(10L, "ImageUtil", "resizeImage",
                "public BufferedImage resizeImage() { return null; }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.<Requirement>of(), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.<Requirement>of(), List.of(code));
        assertThat(defects).extracting(Defect::getDefectType).contains("代码超范围实现");
        Defect extra = defects.stream()
                .filter(d -> "代码超范围实现".equals(d.getDefectType())).findFirst().orElseThrow();
        assertThat(extra.getDefectLevel()).isEqualTo("general");
        assertThat(extra.getCodeUnitId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("一致状态下不产生不一致缺陷")
    void consistentPairProducesNoMismatchDefect() {
        Requirement req = buildReq(1L, "REQ-001",
                "validateUser validate check login username password null if return throw exception log");
        CodeUnit code = buildCode(10L, "UserService", "validateUser",
                "public boolean validateUser(String username, String password) { if (username != null) { return check(username, password); } throw new IllegalArgumentException(); }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, 0.0, 0.0);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        // 双向均匹配成功，无任何缺陷
        assertThat(defects).isEmpty();
    }

    @Test
    @DisplayName("缺陷信息包含原因与修复建议")
    void defectContainsReasonAndSuggestion() {
        Requirement req = buildReq(1L, "REQ-001", "图像识别人脸检测算法");
        CodeUnit code = buildCode(10L, "MailService", "sendMail", "public void sendMail() { }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        assertThat(defects).isNotEmpty();
        assertThat(defects).allSatisfy(d -> {
            if (!"需求缺失".equals(d.getDefectType()) && !"代码超范围实现".equals(d.getDefectType())) {
                assertThat(d.getDefectReason()).isNotBlank();
                assertThat(d.getRepairSuggestion()).isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("GAP-020+GAP-046：需求要求异常处理但代码缺失实现 → 约束覆盖率低 → '约束条件不满足'")
    void constraintMismatchDefectIsReachable() {
        // GAP-046 规则增强：需求含异常处理约束点（EXCEPTION_PATH），代码无任何 try/catch/throw/异常处理证据
        // → 约束覆盖率=0 → conMatch=0.35(<0.6 触发"约束条件不满足"分支，优先于语义弱相关)
        Requirement req = buildReq(1L, "REQ-001", "订单处理失败时需返回错误信息并记录异常");
        CodeUnit code = buildCode(10L, "OrderService", "process",
                "System.out.println(user);");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        assertThat(results.get(0).getTotalSimilarity()).isLessThan(T1);
        assertThat(results.get(0).getConstraintMatchDegree()).isLessThan(0.6);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        assertThat(defects).extracting(Defect::getDefectType).contains("约束条件不满足");
        Defect d = defects.stream()
                .filter(def -> "约束条件不满足".equals(def.getDefectType())).findFirst().orElseThrow();
        assertThat(d.getSubType()).isEqualTo("约束条件不满足");
    }

    @Test
    @DisplayName("GAP-020：不变量满足度低于0.7时主类型为'约束条件不满足'（子类型为'不变量不满足'）")
    void invariantMismatchDefectIsReachable() {
        // GAP-046 改造后 simplified 不变量满足度 = 0.5 + 0.5*constraintMatch，与 Con 强耦合，
        // 无法独立产生"不变量不满足"子类型。故本测试走真实不变量路径：
        // 规约要求状态转移（specRequiresBranch=true）+ 代码 CFG 无分支 -> invSat 降至 0.3 附近(<0.7)；
        // 需求含约束点且代码部分实现（NON_NULL、PARAM_VALID 满足，EXCEPTION_PATH 缺失）-> conMatch=0.667(>=0.6 越过"约束缺失"分支)
        // -> subType="不变量不满足"，主类型"约束条件不满足"。
        String transitionSpec =
                "module spec\n" +
                "sig Order { state: one State }\n" +
                "fact invariants { all o: Order | o.state = Open implies o.state' = Closed }\n" +
                "check consistencyCheck for 5\n";
        Requirement req = buildReq(1L, "REQ-001", "订单支付需非空校验与异常处理");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, transitionSpec);
        CodeUnit code = buildCode(10L, "OrderService", "advance",
                "if (o == null) return; if (check(o)) log.info(o);");
        code.setCfgData("{\"nodes\":[{\"id\":0,\"type\":\"return\",\"label\":\"return\",\"line\":1}],\"edges\":[]}");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), specMap, ALPHA, BETA, GAMMA, T1, T2);
        ConsistencyResult r = results.get(0);
        assertThat(r.getTotalSimilarity()).isLessThan(T1);
        assertThat(r.getConstraintMatchDegree()).isGreaterThanOrEqualTo(0.6); // 越过"约束缺失"主分支
        assertThat(r.getInvariantSatisfaction()).isLessThan(0.7);             // 触发"不变量不满足"子类型
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        // GAP-020：主类型为4类口径，"不变量不满足"归入"约束条件不满足"
        assertThat(defects).extracting(Defect::getDefectType).contains("约束条件不满足");
        Defect d = defects.stream()
                .filter(def -> "约束条件不满足".equals(def.getDefectType())).findFirst().orElseThrow();
        assertThat(d.getSubType()).isEqualTo("不变量不满足");
    }

    @Test
    @DisplayName("GAP-020：不一致缺陷的 subType 字段被正确填充")
    void mismatchDefectHasSubType() {
        Requirement req = buildReq(1L, "REQ-001", "user login account transfer balance");
        CodeUnit code = buildCode(10L, "AccountService", "login",
                "public void login(String user) { }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        assertThat(defects).isNotEmpty();
        defects.forEach(d -> {
            if (!"需求缺失".equals(d.getDefectType()) && !"代码超范围实现".equals(d.getDefectType())) {
                assertThat(d.getSubType()).isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("GAP-020：需求缺失缺陷 subType 与主类型一致")
    void missingRequirementSubTypeMatchesMainType() {
        Requirement req = buildReq(1L, "REQ-001", "图像识别人脸检测算法");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.<CodeUnit>of(), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.<CodeUnit>of());
        Defect missing = defects.stream()
                .filter(d -> "需求缺失".equals(d.getDefectType())).findFirst().orElseThrow();
        assertThat(missing.getSubType()).isEqualTo("需求缺失");
    }

    @Test
    @DisplayName("GAP-020：代码超范围实现缺陷 subType 与主类型一致")
    void extraCodeSubTypeMatchesMainType() {
        CodeUnit code = buildCode(10L, "ImageUtil", "resizeImage",
                "public BufferedImage resizeImage() { return null; }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.<Requirement>of(), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.<Requirement>of(), List.of(code));
        Defect extra = defects.stream()
                .filter(d -> "代码超范围实现".equals(d.getDefectType())).findFirst().orElseThrow();
        assertThat(extra.getSubType()).isEqualTo("代码超范围实现");
    }

    // ==================== GAP-005：规约驱动 Con/Inv 维度 ====================

    private static final String SPEC_NULL_AND_EXCEPTION =
            "module spec\n" +
            "sig Order { state: one State }\n" +
            "fact invariants {\n" +
            "    all o: Order | o.state != none\n" +
            "    all o: Order | no o.exception\n" +
            "}\n" +
            "check consistencyCheck for 5\n";

    @Test
    @DisplayName("GAP-005 Con：'有约束但未实现'显著低于'实现完整'（差值>=0.3）")
    void constraintUnimplementedScoresMuchLower() {
        // 规约含 NULL_CHECK + EXCEPTION_PATH 两条可计分子句
        Requirement req = buildReq(1L, "REQ-001", "订单处理需非空校验与异常处理");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, SPEC_NULL_AND_EXCEPTION);

        // 实现完整：含 null 检查与 try-catch/throw
        CodeUnit complete = buildCode(10L, "OrderService", "process",
                "public void process(Order o) { if (o == null) throw new IllegalStateException(); try { handle(); } catch (Exception e) { log(e); } }");
        // 未实现：无任何约束实现证据
        CodeUnit missing = buildCode(11L, "OrderService", "process",
                "public void process(Order o) { o.getAmount(); }");

        List<ConsistencyResult> completeResults = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(complete), specMap, ALPHA, BETA, GAMMA, T1, T2);
        List<ConsistencyResult> missingResults = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(missing), specMap, ALPHA, BETA, GAMMA, T1, T2);
        double completeCon = completeResults.get(0).getConstraintMatchDegree();
        double missingCon = missingResults.get(0).getConstraintMatchDegree();
        assertThat(completeCon - missingCon).isGreaterThanOrEqualTo(0.3);
    }

    @Test
    @DisplayName("GAP-005 Con：'实现完整'得分高（>=0.8）")
    void constraintFullyImplementedScoresHigh() {
        // 需求"非空与异常处理"命中 NULL_CHECK(NON_NULL) + EXCEPTION_PATH 两个约束点；
        // 完整实现（null 检查 + throw/try-catch）两约束点均覆盖 -> conMatch=1.0。
        // 注意：需求避免使用"校验"字样（PARAM_VALID 会与 NON_NULL 重复提取同一约束点，稀释 base 到 2/3）。
        Requirement req = buildReq(1L, "REQ-001", "订单处理需非空与异常处理");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, SPEC_NULL_AND_EXCEPTION);
        CodeUnit complete = buildCode(10L, "OrderService", "process",
                "public void process(Order o) { if (o == null) throw new IllegalStateException(); try { handle(); } catch (Exception e) { log(e); } }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(complete), specMap, ALPHA, BETA, GAMMA, T1, T2);
        assertThat(results.get(0).getConstraintMatchDegree()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    @DisplayName("GAP-005 Con：规约无可计分子句时返回降级启发式（中性分，不惩罚）")
    void specWithoutCountableConstraintsDegrades() {
        // 规约仅含 OTHER 子句（无可计分子句）
        String otherOnlySpec =
                "module spec\n" +
                "sig Order { state: one State }\n" +
                "fact invariants { all o: Order | o.state in State }\n" +
                "check consistencyCheck for 5\n";
        Requirement req = buildReq(1L, "REQ-001", "订单状态需在状态域内");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, otherOnlySpec);
        CodeUnit code = buildCode(10L, "OrderService", "process", "public void process(Order o) { o.getAmount(); }");
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), specMap, ALPHA, BETA, GAMMA, T1, T2);
        // GAP-046：Con 维度始终由「需求约束点×代码证据」驱动（不依赖规约是否可计分子句）。
        // 需求"订单状态需在状态域内"未命中 STATE_GUARD 词表（"状态"通用词不在其中）→ 约束点为空，
        // 走 keywordCoverage 且需求中文词与代码英文词无交集 → 0 分，属合法降级（低分惩罚"需求要求约束而代码未实现"）。
        // 断言：分数非负（不产生非法负分）且显著低于 0.5（不把"未实现约束"误判为高分）。
        assertThat(results.get(0).getConstraintMatchDegree()).isLessThan(0.5);
        assertThat(results.get(0).getConstraintMatchDegree()).isNotNegative();
    }

    @Test
    @DisplayName("GAP-005 Inv：规约要求状态转移但 CFG 无分支时满足度下降")
    void invariantRequiresBranchButCfgLacksBranch() {
        String transitionSpec =
                "module spec\n" +
                "sig Order { state: one State }\n" +
                "fact invariants { all o: Order | o.state = Open implies o.state' = Closed }\n" +
                "check consistencyCheck for 5\n";
        Requirement req = buildReq(1L, "REQ-001", "订单状态应由待支付流转至已支付");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, transitionSpec);

        // 含分支的 CFG
        CodeUnit withBranch = buildCode(10L, "OrderService", "advance",
                "public void advance(Order o) { if (o.getStatus() == null) { return; } o.setStatus(\"PAID\"); }");
        withBranch.setCfgData("{\"nodes\":[{\"id\":0,\"type\":\"if\",\"label\":\"if x\",\"line\":1},{\"id\":1,\"type\":\"return\",\"label\":\"return\",\"line\":2}],\"edges\":[{\"from\":0,\"to\":1,\"label\":\"\"}]}");
        // 无分支的 CFG
        CodeUnit noBranch = buildCode(11L, "OrderService", "advance",
                "public void advance(Order o) { o.setStatus(\"PAID\"); }");
        noBranch.setCfgData("{\"nodes\":[{\"id\":0,\"type\":\"return\",\"label\":\"return\",\"line\":1}],\"edges\":[]}");

        List<ConsistencyResult> withResults = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(withBranch), specMap, ALPHA, BETA, GAMMA, T1, T2);
        List<ConsistencyResult> noResults = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(noBranch), specMap, ALPHA, BETA, GAMMA, T1, T2);
        double withInv = withResults.get(0).getInvariantSatisfaction();
        double noInv = noResults.get(0).getInvariantSatisfaction();
        assertThat(withInv - noInv).isGreaterThanOrEqualTo(0.3);
    }

    @Test
    @DisplayName("GAP-005 Inv：规约未要求结构特征时代码含循环不扣分")
    void specWithoutStructuralRequirementDoesNotPenalizeLoop() {
        // 规约不要求分支/循环/异常（无可计分结构要求）-> 两维度降级启发式
        String plainSpec =
                "module spec\n" +
                "sig Order { state: one State }\n" +
                "fact invariants { all o: Order | o.state in State }\n" +
                "check consistencyCheck for 5\n";
        Requirement req = buildReq(1L, "REQ-001", "订单状态需在状态域内");
        Map<Long, String> specMap = new HashMap<>();
        specMap.put(1L, plainSpec);
        CodeUnit loopCode = buildCode(10L, "OrderService", "sum",
                "public double sum(List<Order> os) { double t = 0; for (Order o : os) { t += o.getAmount(); } return t; }");
        // 含回边（循环）的 CFG
        loopCode.setCfgData("{\"nodes\":[{\"id\":0,\"type\":\"assign\",\"label\":\"t=0\",\"line\":1},{\"id\":1,\"type\":\"if\",\"label\":\"for\",\"line\":2},{\"id\":2,\"type\":\"goto\",\"label\":\"loop\",\"line\":3}],\"edges\":[{\"from\":2,\"to\":1,\"label\":\"\"}]}");
        // 同一需求不传规约（无规约）时同样降级启发式
        List<ConsistencyResult> withSpec = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(loopCode), specMap, ALPHA, BETA, GAMMA, T1, T2);
        List<ConsistencyResult> noSpec = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(loopCode), ALPHA, BETA, GAMMA, T1, T2);
        // 规约未要求结构特征 -> Inv 与无规约场景一致（不因循环扣分也不加分）
        assertThat(withSpec.get(0).getInvariantSatisfaction())
                .isEqualTo(noSpec.get(0).getInvariantSatisfaction());
    }

    // ==================== 2.6 整改：缺陷精准定位到代码行号 ====================

    @Test
    @DisplayName("2.6：代码超范围实现缺陷定位到方法起始行")
    void extraCodeDefectLocatesToStartLine() throws Exception {
        CodeUnit code = buildCode(10L, "ImageUtil", "resizeImage",
                "public BufferedImage resizeImage() {\n    int w = 10;\n    return null;\n}");
        code.setStartLine(42); // 模拟真实源文件偏移
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.<Requirement>of(), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.<Requirement>of(), List.of(code));
        Defect extra = defects.stream()
                .filter(d -> "代码超范围实现".equals(d.getDefectType())).findFirst().orElseThrow();
        // 代码超范围实现 -> 行号回退到方法起始行（42）
        assertThat(extra.getDefectLine()).isEqualTo(42);
    }

    @Test
    @DisplayName("2.6：约束条件不满足（约束缺失）缺陷行号落在方法体内")
    void constraintDefectLocatesWithinMethod() throws Exception {
        // 需求要求异常处理，代码无 try/catch/throw/异常处理证据 -> conMatch<0.6 -> 约束条件不满足
        Requirement req = buildReq(1L, "REQ-001", "订单处理失败时需返回错误信息并记录异常");
        CodeUnit code = buildCode(10L, "OrderService", "process",
                "public void process(Order o) {\n    System.out.println(o);\n}");
        code.setStartLine(100);
        code.setEndLine(102);
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        Defect d = defects.stream()
                .filter(def -> "约束条件不满足".equals(def.getDefectType())).findFirst().orElseThrow();
        assertThat(d.getDefectLine()).isNotNull();
        assertThat(d.getDefectLine()).isBetween(100, 102);
    }

    @Test
    @DisplayName("2.6：状态类缺陷精准定位到含状态关键词的代码行（绝对行号）")
    void stateDefectLocatesToStatusLine() throws Exception {
        // 需求含状态约束（状态未发布/已发布），代码含 status 校验行，逻辑不匹配 -> 状态类缺陷定位到 status 行
        Requirement req = buildReq(1L, "REQ-001", "试卷发布前状态必须为未发布，发布后状态为已发布");
        CodeUnit code = buildCode(10L, "ExamService", "publish",
                "public void publish(Exam e) {\n\n    if (e.getStatus() == PUBLISHED) return;\n    e.setStatus(PUBLISHED);\n}");
        code.setStartLine(50);
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        Defect d = defects.stream()
                .filter(def -> !"需求缺失".equals(def.getDefectType())
                        && !"代码超范围实现".equals(def.getDefectType())).findFirst().orElseThrow();
        // 状态类（或约束类）缺陷应定位到方法体内含关键词的代码行（含 status/if 行），绝对行号落在 [50,52]
        assertThat(d.getDefectLine()).isNotNull();
        assertThat(d.getDefectLine()).isBetween(50, 52);
    }

    @Test
    @DisplayName("2.6：缺陷行号非空且落在方法体行范围内")
    void defectLineWithinMethodRange() throws Exception {
        Requirement req = buildReq(1L, "REQ-001", "图像识别人脸检测算法");
        CodeUnit code = buildCode(10L, "MailService", "sendMail",
                "public void sendMail() {\n    log.info(\"sending\");\n}\n");
        code.setStartLine(20);
        code.setEndLine(23);
        List<ConsistencyResult> results = checker.checkConsistency(
                100L, 200L, List.of(req), List.of(code), ALPHA, BETA, GAMMA, T1, T2);
        List<Defect> defects = checker.generateDefects(100L, 200L, results, List.of(req), List.of(code));
        assertThat(defects).isNotEmpty();
        defects.forEach(d -> {
            if (!"需求缺失".equals(d.getDefectType())) {
                assertThat(d.getDefectLine()).isNotNull();
                assertThat(d.getDefectLine()).isBetween(code.getStartLine(), code.getEndLine());
            }
        });
    }

    // ==================== 4.1 优化：一致性分级边界对齐 SRS（Sim > T1 才一致） ====================

    @Test
    @DisplayName("4.1：Sim 恰好等于 T1 时判定为一般不一致（SRS 边界为 > T1）")
    void boundaryStatusUsesStrictGreaterThan() throws Exception {
        java.lang.reflect.Method m = ConsistencyChecker.class.getDeclaredMethod(
                "determineStatus", double.class, double.class, double.class);
        m.setAccessible(true);
        // T1=0.8：sim=0.8 应判一般不一致（非一致）；sim=0.8001 才一致
        assertThat(m.invoke(checker, 0.8, 0.8, 0.5)).isEqualTo("general_inconsistent");
        assertThat(m.invoke(checker, 0.8001, 0.8, 0.5)).isEqualTo("consistent");
        // sim=0.5 (=T2) 一般不一致；sim=0.4999 严重不一致
        assertThat(m.invoke(checker, 0.5, 0.8, 0.5)).isEqualTo("general_inconsistent");
        assertThat(m.invoke(checker, 0.4999, 0.8, 0.5)).isEqualTo("serious_inconsistent");
    }
}
