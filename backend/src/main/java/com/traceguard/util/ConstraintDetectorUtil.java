package com.traceguard.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import soot.*;
import soot.jimple.*;
import soot.tagkit.*;
import soot.util.dot.DotGraph;
import soot.util.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GAP-005：CFG（控制流图）结构特征检测工具类
 * 检测 CFG 中的约束特征，用于形式化规约一致性检查
 */
@Slf4j
public class ConstraintDetectorUtil {

    /**
     * CFG 结构特征分析结果
     */
    @Data
    public static class CfgAnalysis {
        /** 方法签名 */
        private String methodSignature;
        /** CFG 节点总数 */
        private int nodeCount;
        /** CFG 边总数 */
        private int edgeCount;
        /** 基本块特征 */
        private BasicBlockFeatures basicBlockFeatures;
        /** 控制流特征 */
        private ControlFlowFeatures controlFlowFeatures;
        /** 数据流特征 */
        private DataFlowFeatures dataFlowFeatures;
        /** 约束特征 */
        private ConstraintFeatures constraintFeatures;
        /** 异常处理特征 */
        private ExceptionFeatures exceptionFeatures;
        /** 是否分析成功 */
        private boolean success = false;
        /** 错误信息 */
        private String error = "";
        /** 原始 CFG Dot 图（如果有） */
        private String cfgDotGraph = "";
    }

    @Data
    public static class BasicBlockFeatures {
        /** 基本块数量 */
        private int basicBlockCount;
        /** 最大基本块大小（指令数） */
        private int maxBlockSize;
        /** 平均基本块大小 */
        private double avgBlockSize;
        /** 入口块数量 */
        private int entryBlocks;
        /** 出口块数量 */
        private int exitBlocks;
        /** 孤立块数量（无前驱无后继） */
        private int isolatedBlocks;
    }

    @Data
    public static class ControlFlowFeatures {
        /** 条件分支数量 */
        private int conditionalBranches;
        /** 循环数量 */
        private int loops;
        /** 最大嵌套深度 */
        private int maxNestingDepth;
        /** switch 语句数量 */
        private int switchStatements;
        /** 直接跳转（goto）数量 */
        private int directJumps;
        /** 不可达代码块数量 */
        private int unreachableBlocks;
    }

    @Data
    public static class DataFlowFeatures {
        /** 定义-使用链数量 */
        private int defUseChains;
        /** 使用-定义链数量 */
        private int useDefChains;
        /** 活跃变量分析：最大同时活跃变量数 */
        private int maxLiveVariables;
        /** 数据依赖边数量 */
        private int dataDependencyEdges;
        /** 反依赖边数量 */
        private int antiDependencyEdges;
        /** 输出依赖边数量 */
        private int outputDependencyEdges;
    }

    @Data
    public static class ConstraintFeatures {
        /** 前置条件（precondition）数量 */
        private int preconditions;
        /** 后置条件（postcondition）数量 */
        private int postconditions;
        /** 循环不变式（loop invariant）数量 */
        private int loopInvariants;
        /** 类不变式（class invariant）引用数量 */
        private int classInvariantReferences;
        /** 断言（assert）数量 */
        private int assertions;
        /** 检查点（check）数量（如 null check, bounds check） */
        private int checks;
        /** 卫语句（guard）数量 */
        private int guards;
        /** 约束复杂度评分（0-1） */
        private double constraintComplexity;
    }

    @Data
    public static class ExceptionFeatures {
        /** try-catch 块数量 */
        private int tryCatchBlocks;
        /** finally 块数量 */
        private int finallyBlocks;
        /** throw 语句数量 */
        private int throwStatements;
        /** 抛出的异常类型集合 */
        private Set<String> exceptionTypes = new HashSet<>();
        /** 异常处理覆盖率（0-1） */
        private double exceptionCoverage;
    }

    /**
     * 从 Soot CFG 分析结构特征
     * @param cfgBody Soot 的 Body（CFG）
     * @param method 对应的方法
     * @return CFG 分析结果
     */
    public static CfgAnalysis analyzeCfg(Body cfgBody, SootMethod method) {
        CfgAnalysis analysis = new CfgAnalysis();
        analysis.setMethodSignature(method.getSignature());
        
        try {
            if (cfgBody == null) {
                analysis.setSuccess(false);
                analysis.setError("CFG Body 为空");
                return analysis;
            }
            
            // 1. 基本块分析
            analysis.setBasicBlockFeatures(analyzeBasicBlocks(cfgBody));
            
            // 2. 控制流分析
            analysis.setControlFlowFeatures(analyzeControlFlow(cfgBody));
            
            // 3. 数据流分析
            analysis.setDataFlowFeatures(analyzeDataFlow(cfgBody));
            
            // 4. 约束特征分析
            analysis.setConstraintFeatures(analyzeConstraints(cfgBody));
            
            // 5. 异常处理分析
            analysis.setExceptionFeatures(analyzeExceptions(cfgBody));
            
            // 6. 计算节点和边总数
            calculateGraphMetrics(cfgBody, analysis);
            
            analysis.setSuccess(true);
            
            // 7. 可选：生成 Dot 图
            analysis.setCfgDotGraph(generateDotGraph(cfgBody, method));
            
        } catch (Exception e) {
            log.error("分析 CFG 失败: {}", method.getSignature(), e);
            analysis.setSuccess(false);
            analysis.setError("分析异常: " + e.getMessage());
        }
        
        return analysis;
    }

    /**
     * 分析基本块特征
     */
    private static BasicBlockFeatures analyzeBasicBlocks(Body body) {
        BasicBlockFeatures features = new BasicBlockFeatures();
        
        // 获取所有单位（Unit）
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        // 简单启发式：通过分支指令划分基本块
        List<List<Unit>> basicBlocks = new ArrayList<>();
        List<Unit> currentBlock = new ArrayList<>();
        
        for (Unit unit : units) {
            currentBlock.add(unit);
            
            // 如果当前指令是分支指令，结束当前基本块
            if (isBranchInstruction(unit)) {
                basicBlocks.add(currentBlock);
                currentBlock = new ArrayList<>();
            }
        }
        
        // 添加最后一个块
        if (!currentBlock.isEmpty()) {
            basicBlocks.add(currentBlock);
        }
        
        features.setBasicBlockCount(basicBlocks.size());
        
        // 计算块大小统计
        int totalSize = 0;
        int maxSize = 0;
        for (List<Unit> block : basicBlocks) {
            int size = block.size();
            totalSize += size;
            if (size > maxSize) maxSize = size;
        }
        
        features.setMaxBlockSize(maxSize);
        features.setAvgBlockSize(basicBlocks.size() > 0 ? (double) totalSize / basicBlocks.size() : 0.0);
        
        // 简单识别入口和出口块
        features.setEntryBlocks(1); // 假设第一个块是入口
        features.setExitBlocks(countExitBlocks(basicBlocks, body));
        features.setIsolatedBlocks(countIsolatedBlocks(basicBlocks, body));
        
        return features;
    }

    /**
     * 判断是否为分支指令
     */
    private static boolean isBranchInstruction(Unit unit) {
        String stmtStr = unit.toString();
        return stmtStr.contains("if") || stmtStr.contains("goto") || 
               stmtStr.contains("return") || stmtStr.contains("throw") ||
               stmtStr.contains("switch") || stmtStr.contains("break") ||
               stmtStr.contains("continue");
    }

    /**
     * 计算出口块数量
     */
    private static int countExitBlocks(List<List<Unit>> blocks, Body body) {
        int exitCount = 0;
        
        // 简单启发式：包含 return 或 throw 的块
        for (List<Unit> block : blocks) {
            for (Unit unit : block) {
                String stmt = unit.toString();
                if (stmt.contains("return") || stmt.contains("throw")) {
                    exitCount++;
                    break;
                }
            }
        }
        
        return exitCount;
    }

    /**
     * 计算孤立块数量
     */
    private static int countIsolatedBlocks(List<List<Unit>> blocks, Body body) {
        // 简单实现：没有前驱和后继的块（需要完整的 CFG 分析）
        // 这里简化处理
        return 0;
    }

    /**
     * 分析控制流特征
     */
    private static ControlFlowFeatures analyzeControlFlow(Body body) {
        ControlFlowFeatures features = new ControlFlowFeatures();
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        // 统计各种控制流结构
        int ifCount = 0;
        int loopCount = 0;
        int switchCount = 0;
        int gotoCount = 0;
        
        // 简单字符串匹配
        for (Unit unit : units) {
            String stmt = unit.toString();
            
            if (stmt.contains("if ")) ifCount++;
            if (stmt.contains("for(") || stmt.contains("while(") || stmt.contains("do{")) loopCount++;
            if (stmt.contains("switch(")) switchCount++;
            if (stmt.contains("goto ")) gotoCount++;
        }
        
        features.setConditionalBranches(ifCount);
        features.setLoops(loopCount);
        features.setSwitchStatements(switchCount);
        features.setDirectJumps(gotoCount);
        
        // 计算嵌套深度（简化）
        features.setMaxNestingDepth(calculateNestingDepth(body));
        
        // 不可达代码（简化）
        features.setUnreachableBlocks(0);
        
        return features;
    }

    /**
     * 计算最大嵌套深度
     */
    private static int calculateNestingDepth(Body body) {
        int maxDepth = 0;
        int currentDepth = 0;
        
        // 简单括号匹配
        for (Unit unit : body.getUnits()) {
            String stmt = unit.toString();
            
            // 统计左括号
            for (char c : stmt.toCharArray()) {
                if (c == '{') currentDepth++;
                else if (c == '}') currentDepth--;
                
                if (currentDepth > maxDepth) maxDepth = currentDepth;
            }
        }
        
        return maxDepth;
    }

    /**
     * 分析数据流特征
     */
    private static DataFlowFeatures analyzeDataFlow(Body body) {
        DataFlowFeatures features = new DataFlowFeatures();
        
        // 简单启发式：统计赋值和变量使用
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        int defCount = 0;
        int useCount = 0;
        Set<String> liveVars = new HashSet<>();
        int maxLive = 0;
        
        for (Unit unit : units) {
            String stmt = unit.toString();
            
            // 检测赋值语句（定义）
            if (stmt.contains(" = ") && !stmt.contains("==")) {
                defCount++;
                // 提取变量名
                String varName = extractVariableName(stmt.split(" = ")[0]);
                if (!varName.isEmpty()) {
                    liveVars.add(varName);
                }
            }
            
            // 检测变量使用
            Pattern varPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*[^=]");
            Matcher matcher = varPattern.matcher(stmt);
            while (matcher.find()) {
                String var = matcher.group(1);
                if (!isKeyword(var)) {
                    useCount++;
                    liveVars.add(var);
                }
            }
            
            // 更新最大活跃变量数
            if (liveVars.size() > maxLive) maxLive = liveVars.size();
            
            // 模拟变量死亡（简化）
            if (stmt.contains(";")) {
                // 语句结束，可能有一些变量不再使用
                // 这里简化处理
            }
        }
        
        features.setDefUseChains(defCount);
        features.setUseDefChains(useCount);
        features.setMaxLiveVariables(maxLive);
        
        // 依赖分析（简化）
        features.setDataDependencyEdges(defCount * 2); // 估计值
        features.setAntiDependencyEdges(defCount);
        features.setOutputDependencyEdges(defCount / 2);
        
        return features;
    }

    /**
     * 提取变量名
     */
    private static String extractVariableName(String expr) {
        expr = expr.trim();
        // 移除可能的类型声明
        if (expr.contains(" ")) {
            String[] parts = expr.split(" ");
            return parts[parts.length - 1];
        }
        return expr;
    }

    /**
     * 判断是否为关键字
     */
    private static boolean isKeyword(String word) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "try", "catch", "finally", "throw",
            "new", "this", "super", "null", "true", "false", "instanceof"
        ));
        return keywords.contains(word);
    }

    /**
     * 分析约束特征
     */
    private static ConstraintFeatures analyzeConstraints(Body body) {
        ConstraintFeatures features = new ConstraintFeatures();
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        int preconditionCount = 0;
        int postconditionCount = 0;
        int loopInvariantCount = 0;
        int classInvariantCount = 0;
        int assertCount = 0;
        int checkCount = 0;
        int guardCount = 0;
        
        for (Unit unit : units) {
            String stmt = unit.toString();
            
            // 检测断言
            if (stmt.contains("assert ")) {
                assertCount++;
            }
            
            // 检测检查点
            if (stmt.contains("!= null") || stmt.contains("== null") ||
                stmt.contains(".length") || stmt.contains(".size()") ||
                stmt.contains(".isEmpty()") || stmt.contains("index")) {
                checkCount++;
            }
            
            // 检测卫语句
            if (stmt.contains("if (") && (stmt.contains("!= null") || stmt.contains("instanceof"))) {
                guardCount++;
            }
            
            // 检测前置条件（方法开始处的检查）
            // 检测后置条件（方法结束前的检查）
            // 检测循环不变式
        }
        
        features.setPreconditions(preconditionCount);
        features.setPostconditions(postconditionCount);
        features.setLoopInvariants(loopInvariantCount);
        features.setClassInvariantReferences(classInvariantCount);
        features.setAssertions(assertCount);
        features.setChecks(checkCount);
        features.setGuards(guardCount);
        
        // 计算约束复杂度
        double complexity = (assertCount * 0.3 + checkCount * 0.2 + guardCount * 0.2 + 
                           preconditionCount * 0.15 + postconditionCount * 0.15) / 
                           Math.max(1, units.size());
        features.setConstraintComplexity(Math.min(complexity, 1.0));
        
        return features;
    }

    /**
     * 分析异常处理特征
     */
    private static ExceptionFeatures analyzeExceptions(Body body) {
        ExceptionFeatures features = new ExceptionFeatures();
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        int tryCatchCount = 0;
        int finallyCount = 0;
        int throwCount = 0;
        Set<String> exceptionTypes = new HashSet<>();
        
        boolean inTryBlock = false;
        boolean inCatchBlock = false;
        
        for (Unit unit : units) {
            String stmt = unit.toString();
            
            if (stmt.contains("try {")) {
                inTryBlock = true;
                tryCatchCount++;
            }
            
            if (stmt.contains("catch (")) {
                inCatchBlock = true;
                // 提取异常类型
                Pattern catchPattern = Pattern.compile("catch\\s*\\((\\w+)\\s+");
                Matcher matcher = catchPattern.matcher(stmt);
                if (matcher.find()) {
                    exceptionTypes.add(matcher.group(1));
                }
            }
            
            if (stmt.contains("finally {")) {
                finallyCount++;
            }
            
            if (stmt.contains("throw ")) {
                throwCount++;
                // 提取异常类型
                Pattern throwPattern = Pattern.compile("throw\\s+new\\s+(\\w+)");
                Matcher matcher = throwPattern.matcher(stmt);
                if (matcher.find()) {
                    exceptionTypes.add(matcher.group(1));
                }
            }
            
            if (stmt.contains("}") && (inTryBlock || inCatchBlock)) {
                inTryBlock = false;
                inCatchBlock = false;
            }
        }
        
        features.setTryCatchBlocks(tryCatchCount);
        features.setFinallyBlocks(finallyCount);
        features.setThrowStatements(throwCount);
        features.setExceptionTypes(exceptionTypes);
        
        // 计算异常处理覆盖率
        int totalProtectedBlocks = tryCatchCount * 3; // 估计值
        int actualCoverage = throwCount > 0 ? Math.min(tryCatchCount * 2, throwCount) : 0;
        features.setExceptionCoverage(totalProtectedBlocks > 0 ? 
            (double) actualCoverage / totalProtectedBlocks : 0.0);
        
        return features;
    }

    /**
     * 计算图指标
     */
    private static void calculateGraphMetrics(Body body, CfgAnalysis analysis) {
        // 简化计算
        List<Unit> units = new ArrayList<>(body.getUnits());
        
        // 节点数 = 基本块数
        int nodeCount = analysis.getBasicBlockFeatures().getBasicBlockCount();
        
        // 边数估计：每个分支指令产生2条边，其他指令产生1条边
        int edgeCount = 0;
        for (Unit unit : units) {
            if (isBranchInstruction(unit)) {
                edgeCount += 2; // 分支：true 和 false 边
            } else {
                edgeCount += 1; // 顺序边
            }
        }
        
        analysis.setNodeCount(nodeCount);
        analysis.setEdgeCount(edgeCount);
    }

    /**
     * 生成 Dot 图
     */
    private static String generateDotGraph(Body body, SootMethod method) {
        try {
            DotGraph graph = new DotGraph(method.getName());
            graph.setGraphLabel("CFG for " + method.getSignature());
            graph.setGraphAttribute("rankdir", "TB");
            
            // 创建节点
            List<Unit> units = new ArrayList<>(body.getUnits());
            Map<Unit, String> nodeMap = new HashMap<>();
            
            int nodeId = 1;
            for (Unit unit : units) {
                String nodeName = "node" + nodeId++;
                nodeMap.put(unit, nodeName);
                
                String label = unit.toString();
                if (label.length() > 50) {
                    label = label.substring(0, 47) + "...";
                }
                label = label.replace("\"", "\\\"");
                
                graph.drawNode(nodeName).setLabel(label);
            }
            
            // 创建边（简化）
            for (int i = 0; i < units.size() - 1; i++) {
                Unit from = units.get(i);
                Unit to = units.get(i + 1);
                
                if (nodeMap.containsKey(from) && nodeMap.containsKey(to)) {
                    graph.drawEdge(nodeMap.get(from), nodeMap.get(to));
                }
            }
            
            return graph.toString();
        } catch (Exception e) {
            log.warn("生成 Dot 图失败", e);
            return "";
        }
    }

    /**
     * 将分析结果转换为 JSON 字符串
     */
    public static String toJson(CfgAnalysis analysis) {
        if (!analysis.isSuccess()) {
            return JSON.toJSONString(Collections.singletonMap("error", analysis.getError()));
        }
        
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("method", analysis.getMethodSignature());
        result.put("nodeCount", analysis.getNodeCount());
        result.put("edgeCount", analysis.getEdgeCount());
        
        // 基本块特征
        JSONObject bbFeatures = new JSONObject();
        BasicBlockFeatures bb = analysis.getBasicBlockFeatures();
        bbFeatures.put("basicBlockCount", bb.getBasicBlockCount());
        bbFeatures.put("maxBlockSize", bb.getMaxBlockSize());
        bbFeatures.put("avgBlockSize", bb.getAvgBlockSize());
        bbFeatures.put("entryBlocks", bb.getEntryBlocks());
        bbFeatures.put("exitBlocks", bb.getExitBlocks());
        bbFeatures.put("isolatedBlocks", bb.getIsolatedBlocks());
        result.put("basicBlockFeatures", bbFeatures);
        
        // 控制流特征
        JSONObject cfFeatures = new JSONObject();
        ControlFlowFeatures cf = analysis.getControlFlowFeatures();
        cfFeatures.put("conditionalBranches", cf.getConditionalBranches());
        cfFeatures.put("loops", cf.getLoops());
        cfFeatures.put("maxNestingDepth", cf.getMaxNestingDepth());
        cfFeatures.put("switchStatements", cf.getSwitchStatements());
        cfFeatures.put("directJumps", cf.getDirectJumps());
        cfFeatures.put("unreachableBlocks", cf.getUnreachableBlocks());
        result.put("controlFlowFeatures", cfFeatures);
        
        // 数据流特征
        JSONObject dfFeatures = new JSONObject();
        DataFlowFeatures df = analysis.getDataFlowFeatures();
        dfFeatures.put("defUseChains", df.getDefUseChains());
        dfFeatures.put("useDefChains", df.getUseDefChains());
        dfFeatures.put("maxLiveVariables", df.getMaxLiveVariables());
        dfFeatures.put("dataDependencyEdges", df.getDataDependencyEdges());
        dfFeatures.put("antiDependencyEdges", df.getAntiDependencyEdges());
        dfFeatures.put("outputDependencyEdges", df.getOutputDependencyEdges());
        result.put("dataFlowFeatures", dfFeatures);
        
        // 约束特征
        JSONObject constraintFeatures = new JSONObject();
        ConstraintFeatures con = analysis.getConstraintFeatures();
        constraintFeatures.put("preconditions", con.getPreconditions());
        constraintFeatures.put("postconditions", con.getPostconditions());
        constraintFeatures.put("loopInvariants", con.getLoopInvariants());
        constraintFeatures.put("classInvariantReferences", con.getClassInvariantReferences());
        constraintFeatures.put("assertions", con.getAssertions());
        constraintFeatures.put("checks", con.getChecks());
        constraintFeatures.put("guards", con.getGuards());
        constraintFeatures.put("constraintComplexity", con.getConstraintComplexity());
        result.put("constraintFeatures", constraintFeatures);
        
        // 异常特征
        JSONObject exceptionFeatures = new JSONObject();
        ExceptionFeatures ex = analysis.getExceptionFeatures();
        exceptionFeatures.put("tryCatchBlocks", ex.getTryCatchBlocks());
        exceptionFeatures.put("finallyBlocks", ex.getFinallyBlocks());
        exceptionFeatures.put("throwStatements", ex.getThrowStatements());
        exceptionFeatures.put("exceptionTypes", new ArrayList<>(ex.getExceptionTypes()));
        exceptionFeatures.put("exceptionCoverage", ex.getExceptionCoverage());
        result.put("exceptionFeatures", exceptionFeatures);
        
        // CFG 图
        result.put("cfgDotGraph", analysis.getCfgDotGraph());
        
        return result.toJSONString();
    }

    /**
     * 从 JSON 恢复分析结果
     */
    public static CfgAnalysis fromJson(String json) {
        CfgAnalysis analysis = new CfgAnalysis();
        
        try {
            JSONObject obj = JSON.parseObject(json);
            analysis.setSuccess(obj.getBooleanValue("success"));
            analysis.setMethodSignature(obj.getString("method"));
            analysis.setNodeCount(obj.getIntValue("nodeCount"));
            analysis.setEdgeCount(obj.getIntValue("edgeCount"));
            analysis.setCfgDotGraph(obj.getString("cfgDotGraph"));
            
            if (!analysis.isSuccess()) {
                analysis.setError(obj.getString("error"));
                return analysis;
            }
            
            // 恢复基本块特征
            JSONObject bbObj = obj.getJSONObject("basicBlockFeatures");
            BasicBlockFeatures bb = new BasicBlockFeatures();
            bb.setBasicBlockCount(bbObj.getIntValue("basicBlockCount"));
            bb.setMaxBlockSize(bbObj.getIntValue("maxBlockSize"));
            bb.setAvgBlockSize(bbObj.getDouble("avgBlockSize"));
            bb.setEntryBlocks(bbObj.getIntValue("entryBlocks"));
            bb.setExitBlocks(bbObj.getIntValue("exitBlocks"));
            bb.setIsolatedBlocks(bbObj.getIntValue("isolatedBlocks"));
            analysis.setBasicBlockFeatures(bb);
            
            // 恢复控制流特征
            JSONObject cfObj = obj.getJSONObject("controlFlowFeatures");
            ControlFlowFeatures cf = new ControlFlowFeatures();
            cf.setConditionalBranches(cfObj.getIntValue("conditionalBranches"));
            cf.setLoops(cfObj.getIntValue("loops"));
            cf.setMaxNestingDepth(cfObj.getIntValue("maxNestingDepth"));
            cf.setSwitchStatements(cfObj.getIntValue("switchStatements"));
            cf.setDirectJumps(cfObj.getIntValue("directJumps"));
            cf.setUnreachableBlocks(cfObj.getIntValue("unreachableBlocks"));
            analysis.setControlFlowFeatures(cf);
            
            // 恢复数据流特征
            JSONObject dfObj = obj.getJSONObject("dataFlowFeatures");
            DataFlowFeatures df = new DataFlowFeatures();
            df.setDefUseChains(dfObj.getIntValue("defUseChains"));
            df.setUseDefChains(dfObj.getIntValue("useDefChains"));
            df.setMaxLiveVariables(dfObj.getIntValue("maxLiveVariables"));
            df.setDataDependencyEdges(dfObj.getIntValue("dataDependencyEdges"));
            df.setAntiDependencyEdges(dfObj.getIntValue("antiDependencyEdges"));
            df.setOutputDependencyEdges(dfObj.getIntValue("outputDependencyEdges"));
            analysis.setDataFlowFeatures(df);
            
            // 恢复约束特征
            JSONObject conObj = obj.getJSONObject("constraintFeatures");
            ConstraintFeatures con = new ConstraintFeatures();
            con.setPreconditions(conObj.getIntValue("preconditions"));
            con.setPostconditions(conObj.getIntValue("postconditions"));
            con.setLoopInvariants(conObj.getIntValue("loopInvariants"));
            con.setClassInvariantReferences(conObj.getIntValue("classInvariantReferences"));
            con.setAssertions(conObj.getIntValue("assertions"));
            con.setChecks(conObj.getIntValue("checks"));
            con.setGuards(conObj.getIntValue("guards"));
            con.setConstraintComplexity(conObj.getDouble("constraintComplexity"));
            analysis.setConstraintFeatures(con);
            
            // 恢复异常特征
            JSONObject exObj = obj.getJSONObject("exceptionFeatures");
            ExceptionFeatures ex = new ExceptionFeatures();
            ex.setTryCatchBlocks(exObj.getIntValue("tryCatchBlocks"));
            ex.setFinallyBlocks(exObj.getIntValue("finallyBlocks"));
            ex.setThrowStatements(exObj.getIntValue("throwStatements"));
            ex.setExceptionTypes(new HashSet<>(exObj.getJSONArray("exceptionTypes").toList(String.class)));
            ex.setExceptionCoverage(exObj.getDouble("exceptionCoverage"));
            analysis.setExceptionFeatures(ex);
            
        } catch (Exception e) {
            log.error("从 JSON 恢复 CfgAnalysis 失败", e);
            analysis.setSuccess(false);
            analysis.setError("恢复失败: " + e.getMessage());
        }
        
        return analysis;
    }

    /**
     * 计算 CFG 的结构化特征向量
     * 用于相似度计算
     */
    public static Map<String, Double> extractCfgFeatures(CfgAnalysis analysis) {
        Map<String, Double> features = new LinkedHashMap<>();
        
        if (!analysis.isSuccess()) {
            return features;
        }
        
        BasicBlockFeatures bb = analysis.getBasicBlockFeatures();
        ControlFlowFeatures cf = analysis.getControlFlowFeatures();
        DataFlowFeatures df = analysis.getDataFlowFeatures();
        ConstraintFeatures con = analysis.getConstraintFeatures();
        ExceptionFeatures ex = analysis.getExceptionFeatures();
        
        // 1. 图结构特征
        features.put("node_count", (double) analysis.getNodeCount());
        features.put("edge_count", (double) analysis.getEdgeCount());
        features.put("edge_node_ratio", analysis.getNodeCount() > 0 ? 
            (double) analysis.getEdgeCount() / analysis.getNodeCount() : 0.0);
        
        // 2. 基本块特征
        features.put("basic_block_count", (double) bb.getBasicBlockCount());
        features.put("max_block_size", (double) bb.getMaxBlockSize());
        features.put("avg_block_size", bb.getAvgBlockSize());
        features.put("entry_exit_ratio", bb.getExitBlocks() > 0 ? 
            (double) bb.getEntryBlocks() / bb.getExitBlocks() : 0.0);
        
        // 3. 控制流特征
        features.put("conditional_branch_density", bb.getBasicBlockCount() > 0 ? 
            (double) cf.getConditionalBranches() / bb.getBasicBlockCount() : 0.0);
        features.put("loop_density", bb.getBasicBlockCount() > 0 ? 
            (double) cf.getLoops() / bb.getBasicBlockCount() : 0.0);
        features.put("max_nesting_depth", (double) cf.getMaxNestingDepth());
        features.put("switch_density", bb.getBasicBlockCount() > 0 ? 
            (double) cf.getSwitchStatements() / bb.getBasicBlockCount() : 0.0);
        
        // 4. 数据流特征
        features.put("def_use_density", bb.getBasicBlockCount() > 0 ? 
            (double) df.getDefUseChains() / bb.getBasicBlockCount() : 0.0);
        features.put("max_live_variables", (double) df.getMaxLiveVariables());
        features.put("data_dependency_density", analysis.getEdgeCount() > 0 ? 
            (double) df.getDataDependencyEdges() / analysis.getEdgeCount() : 0.0);
        
        // 5. 约束特征
        features.put("constraint_density", bb.getBasicBlockCount() > 0 ? 
            (double) (con.getPreconditions() + con.getPostconditions() + 
                     con.getAssertions() + con.getChecks()) / bb.getBasicBlockCount() : 0.0);
        features.put("assertion_density", bb.getBasicBlockCount() > 0 ? 
            (double) con.getAssertions() / bb.getBasicBlockCount() : 0.0);
        features.put("check_density", bb.getBasicBlockCount() > 0 ? 
            (double) con.getChecks() / bb.getBasicBlockCount() : 0.0);
        features.put("guard_density", bb.getBasicBlockCount() > 0 ? 
            (double) con.getGuards() / bb.getBasicBlockCount() : 0.0);
        features.put("constraint_complexity", con.getConstraintComplexity());
        
        // 6. 异常处理特征
        features.put("exception_handling_density", bb.getBasicBlockCount() > 0 ? 
            (double) ex.getTryCatchBlocks() / bb.getBasicBlockCount() : 0.0);
        features.put("exception_coverage", ex.getExceptionCoverage());
        features.put("exception_type_count", (double) ex.getExceptionTypes().size());
        
        // 7. 复杂度综合评分
        double complexityScore = analysis.getNodeCount() * 0.1 + 
                               cf.getConditionalBranches() * 0.3 +
                               cf.getLoops() * 0.5 +
                               con.getConstraintComplexity() * 2.0 +
                               ex.getExceptionCoverage() * 0.5;
        features.put("cfg_complexity_score", complexityScore);
        
        return features;
    }

    // ==================== GAP-005 AST 级实现证据检测（Con 维度：规约约束 vs 实现证据覆盖度） ====================

    /** 实现证据集：证据类型 -> 命中行号列表 */
    public static class EvidenceSet {
        private Map<FormalSpecParserUtil.ConstraintKind, List<Integer>> evidences =
                new EnumMap<>(FormalSpecParserUtil.ConstraintKind.class);

        public Map<FormalSpecParserUtil.ConstraintKind, List<Integer>> getEvidences() { return evidences; }
        public void setEvidences(Map<FormalSpecParserUtil.ConstraintKind, List<Integer>> evidences) {
            this.evidences = evidences;
        }

        public void addEvidence(FormalSpecParserUtil.ConstraintKind kind, int line) {
            if (line <= 0) return;
            evidences.computeIfAbsent(kind, k -> new ArrayList<>()).add(line);
        }

        public boolean hasEvidence(FormalSpecParserUtil.ConstraintKind kind) {
            List<Integer> lines = evidences.get(kind);
            return lines != null && !lines.isEmpty();
        }

        public boolean isEmpty() {
            for (List<Integer> lines : evidences.values()) {
                if (lines != null && !lines.isEmpty()) return false;
            }
            return true;
        }
    }

    /**
     * GAP-005：对一个代码单元（源码 AST，基于 JavaParser）检测实现证据。
     * 支持方法片段（自动包裹为类解析）；全程 try/catch，异常返回空 EvidenceSet（触发降级）。
     */
    public static EvidenceSet detect(String sourceCode) {
        EvidenceSet result = new EvidenceSet();
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return result;
        }
        try {
            com.github.javaparser.ast.CompilationUnit cu = parseSource(sourceCode);
            if (cu == null) {
                return result;
            }
            for (com.github.javaparser.ast.body.MethodDeclaration m :
                    cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
                detectInMethod(m, result);
            }
        } catch (Exception e) {
            log.warn("AST 实现证据检测失败: {}", e.getMessage());
        }
        return result;
    }

    /** 解析源码：优先整文件，失败依次按"完整方法"与"方法体片段"包裹后重试 */
    private static com.github.javaparser.ast.CompilationUnit parseSource(String sourceCode) {
        com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser();
        com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> pr = parser.parse(sourceCode);
        if (pr.isSuccessful() && pr.getResult().isPresent()) {
            return pr.getResult().get();
        }
        String wrappedMethod = "class Wrapper {\n" + sourceCode + "\n}";
        pr = parser.parse(wrappedMethod);
        if (pr.isSuccessful() && pr.getResult().isPresent()) {
            return pr.getResult().get();
        }
        String wrappedBody = "class Wrapper {\npublic void m() {\n" + sourceCode + "\n}\n}";
        pr = parser.parse(wrappedBody);
        return pr.getResult().orElse(null);
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static void detectInMethod(com.github.javaparser.ast.body.MethodDeclaration m, EvidenceSet result) {
        // NULL_CHECK：@NotNull/@Valid/@Nonnull 参数注解、Objects.requireNonNull、null 判断分支
        boolean paramAnnotated = m.getParameters().stream().anyMatch(p -> p.getAnnotations().stream()
                .anyMatch(a -> {
                    String n = a.getNameAsString();
                    return "NotNull".equals(n) || "Valid".equals(n) || "Nonnull".equals(n);
                }));
        if (paramAnnotated) result.addEvidence(FormalSpecParserUtil.ConstraintKind.NULL_CHECK, lineOf(m));
        for (com.github.javaparser.ast.expr.MethodCallExpr call : m.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            if ("requireNonNull".equals(call.getNameAsString())) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.NULL_CHECK, lineOf(call));
            }
        }
        for (com.github.javaparser.ast.expr.BinaryExpr be : m.findAll(com.github.javaparser.ast.expr.BinaryExpr.class)) {
            if ((be.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS
                    || be.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS)
                    && be.getRight() instanceof com.github.javaparser.ast.expr.NullLiteralExpr) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.NULL_CHECK, lineOf(be));
            }
        }

        // EXCEPTION_PATH：try-catch 块、throw 语句、方法签名 throws 子句
        if (!m.findAll(com.github.javaparser.ast.stmt.TryStmt.class).isEmpty()) {
            result.addEvidence(FormalSpecParserUtil.ConstraintKind.EXCEPTION_PATH, lineOf(m));
        }
        if (!m.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).isEmpty()) {
            result.addEvidence(FormalSpecParserUtil.ConstraintKind.EXCEPTION_PATH, lineOf(m));
        }
        if (!m.getThrownExceptions().isEmpty()) {
            result.addEvidence(FormalSpecParserUtil.ConstraintKind.EXCEPTION_PATH, lineOf(m));
        }

        // RESOURCE_RELEASE：try-with-resources、close() 调用
        for (com.github.javaparser.ast.stmt.TryStmt t : m.findAll(com.github.javaparser.ast.stmt.TryStmt.class)) {
            if (!t.getResources().isEmpty()) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.RESOURCE_RELEASE, lineOf(t));
            }
        }
        for (com.github.javaparser.ast.expr.MethodCallExpr call : m.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            if ("close".equals(call.getNameAsString())) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.RESOURCE_RELEASE, lineOf(call));
            }
        }

        // UNIQUENESS：distinct() 去重、contains() 判重
        for (com.github.javaparser.ast.expr.MethodCallExpr call : m.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            if ("distinct".equals(call.getNameAsString()) || "contains".equals(call.getNameAsString())) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.UNIQUENESS, lineOf(call));
            }
        }

        // RANGE：数值比较表达式（>=、<=、>、<）
        for (com.github.javaparser.ast.expr.BinaryExpr be : m.findAll(com.github.javaparser.ast.expr.BinaryExpr.class)) {
            com.github.javaparser.ast.expr.BinaryExpr.Operator op = be.getOperator();
            if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.GREATER
                    || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.GREATER_EQUALS
                    || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.LESS
                    || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.LESS_EQUALS) {
                result.addEvidence(FormalSpecParserUtil.ConstraintKind.RANGE, lineOf(be));
            }
        }
    }
}