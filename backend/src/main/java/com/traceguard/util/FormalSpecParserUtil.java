package com.traceguard.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GAP-005：形式化规约（Alloy）解析工具类
 * 解析 Alloy 规约文件，提取状态、转移、约束、不变量等结构化信息
 */
@Slf4j
public class FormalSpecParserUtil {

    /**
     * Alloy 规约解析结果
     */
    @Data
    public static class FormalSpecAnalysis {
        /** 状态集合（状态名 -> 状态属性） */
        private Map<String, StateInfo> states = new LinkedHashMap<>();
        /** 转移关系（转移名 -> 转移定义） */
        private Map<String, TransitionInfo> transitions = new LinkedHashMap<>();
        /** 约束（约束名 -> 约束表达式） */
        private Map<String, ConstraintInfo> constraints = new LinkedHashMap<>();
        /** 不变量（不变量名 -> 不变量表达式） */
        private Map<String, InvariantInfo> invariants = new LinkedHashMap<>();
        /** 所有签名（signature）定义 */
        private List<String> signatures = new ArrayList<>();
        /** 所有函数（fun/pred）定义 */
        private List<String> functions = new ArrayList<>();
        /** 所有事实（fact）定义 */
        private List<String> facts = new ArrayList<>();
        /** 是否解析成功 */
        private boolean success = false;
        /** 错误信息（若解析失败） */
        private String error = "";
        /** 原始 Alloy 规约内容 */
        private String originalAlloy = "";
    }

    @Data
    public static class StateInfo {
        private String name;
        private String signatureType; // abstract, enum, sig
        private List<String> fields = new ArrayList<>(); // 字段定义
        private Set<String> parentStates = new LinkedHashSet<>(); // 继承的父状态
        private String comment = "";
    }

    @Data
    public static class TransitionInfo {
        private String name;
        private String fromState;
        private String toState;
        private List<String> parameters = new ArrayList<>(); // 参数列表
        private String guardCondition = ""; // 守卫条件
        private String action = ""; // 动作/效应
        private String comment = "";
    }

    @Data
    public static class ConstraintInfo {
        private String name;
        private String type; // "invariant", "precondition", "postcondition", "global"
        private String expression;
        private Set<String> involvedStates = new LinkedHashSet<>();
        private Set<String> involvedTransitions = new LinkedHashSet<>();
        private String comment = "";
    }

    @Data
    public static class InvariantInfo {
        private String name;
        private String expression;
        private Set<String> involvedStates = new LinkedHashSet<>();
        private String comment = "";
    }

    /**
     * 解析 Alloy 规约文件
     * @param alloyContent Alloy 规约内容
     * @return 解析结果
     */
    public static FormalSpecAnalysis parseAlloySpec(String alloyContent) {
        FormalSpecAnalysis result = new FormalSpecAnalysis();
        result.setOriginalAlloy(alloyContent);
        
        try {
            if (alloyContent == null || alloyContent.trim().isEmpty()) {
                result.setSuccess(false);
                result.setError("Alloy 规约内容为空");
                return result;
            }
            
            // 1. 提取签名（signature）定义
            extractSignatures(alloyContent, result);
            
            // 2. 提取状态（sig）定义
            extractStates(alloyContent, result);
            
            // 3. 提取转移关系（pred/fun 作为转移）
            extractTransitions(alloyContent, result);
            
            // 4. 提取约束和不变式
            extractConstraintsAndInvariants(alloyContent, result);
            
            // 5. 提取函数和事实
            extractFunctionsAndFacts(alloyContent, result);
            
            // 6. 建立关联关系
            establishRelationships(result);
            
            result.setSuccess(true);
        } catch (Exception e) {
            log.error("解析 Alloy 规约失败", e);
            result.setSuccess(false);
            result.setError("解析异常: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 提取签名定义
     */
    private static void extractSignatures(String content, FormalSpecAnalysis result) {
        // 匹配签名定义：sig State { ... }
        Pattern sigPattern = Pattern.compile("\\bsig\\s+([A-Z][a-zA-Z0-9_]*)\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        Matcher sigMatcher = sigPattern.matcher(content);
        
        while (sigMatcher.find()) {
            String sigName = sigMatcher.group(1);
            result.getSignatures().add(sigName);
            
            // 如果是 abstract 或 enum 签名
            Pattern abstractPattern = Pattern.compile("\\babstract\\s+sig\\s+" + sigName);
            Pattern enumPattern = Pattern.compile("\\benum\\s+sig\\s+" + sigName);
            
            if (abstractPattern.matcher(content).find()) {
                // 抽象签名，添加到状态
                StateInfo state = new StateInfo();
                state.setName(sigName);
                state.setSignatureType("abstract");
                result.getStates().put(sigName, state);
            } else if (enumPattern.matcher(content).find()) {
                // 枚举签名，添加到状态
                StateInfo state = new StateInfo();
                state.setName(sigName);
                state.setSignatureType("enum");
                result.getStates().put(sigName, state);
            }
        }
    }

    /**
     * 提取状态定义
     */
    private static void extractStates(String content, FormalSpecAnalysis result) {
        // 匹配 sig 定义（可能已部分在 extractSignatures 中处理）
        Pattern sigPattern = Pattern.compile("\\b(abstract\\s+|enum\\s+|)sig\\s+([A-Z][a-zA-Z0-9_]*)(\\s+extends\\s+([A-Z][a-zA-Z0-9_]*))?\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        Matcher sigMatcher = sigPattern.matcher(content);
        
        while (sigMatcher.find()) {
            String modifier = sigMatcher.group(1).trim();
            String stateName = sigMatcher.group(2);
            String extendsClause = sigMatcher.group(4);
            String fieldsText = sigMatcher.group(5);
            
            StateInfo state = result.getStates().get(stateName);
            if (state == null) {
                state = new StateInfo();
                state.setName(stateName);
                result.getStates().put(stateName, state);
            }
            
            if (!modifier.isEmpty()) {
                state.setSignatureType(modifier.replace("sig", "").trim());
            } else {
                state.setSignatureType("sig");
            }
            
            // 处理继承关系
            if (extendsClause != null) {
                state.getParentStates().add(extendsClause);
            }
            
            // 提取字段
            if (fieldsText != null) {
                String[] fields = fieldsText.split("[,\n]");
                for (String field : fields) {
                    String trimmed = field.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("--")) {
                        // 移除注释
                        int commentIdx = trimmed.indexOf("//");
                        if (commentIdx != -1) trimmed = trimmed.substring(0, commentIdx);
                        commentIdx = trimmed.indexOf("--");
                        if (commentIdx != -1) trimmed = trimmed.substring(0, commentIdx);
                        
                        trimmed = trimmed.trim();
                        if (!trimmed.isEmpty()) {
                            state.getFields().add(trimmed);
                        }
                    }
                }
            }
        }
    }

    /**
     * 提取转移关系
     */
    private static void extractTransitions(String content, FormalSpecAnalysis result) {
        // 匹配 pred 和 fun 定义，可能表示转移
        Pattern predPattern = Pattern.compile("\\b(pred|fun)\\s+([a-z][a-zA-Z0-9_]*)\\s*\\((.*?)\\)\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher predMatcher = predPattern.matcher(content);
        
        while (predMatcher.find()) {
            String type = predMatcher.group(1); // pred 或 fun
            String transName = predMatcher.group(2);
            String paramsText = predMatcher.group(3);
            String body = predMatcher.group(4);
            
            TransitionInfo trans = new TransitionInfo();
            trans.setName(transName);
            
            // 解析参数
            if (paramsText != null && !paramsText.trim().isEmpty()) {
                String[] params = paramsText.split(",");
                for (String param : params) {
                    String trimmed = param.trim();
                    if (!trimmed.isEmpty()) {
                        trans.getParameters().add(trimmed);
                    }
                }
            }
            
            // 尝试从主体中识别 fromState 和 toState
            identifyTransitionStates(body, trans);
            
            // 识别守卫条件和动作
            identifyGuardAndAction(body, trans);
            
            result.getTransitions().put(transName, trans);
        }
    }

    /**
     * 识别转移的起始状态和结束状态
     */
    private static void identifyTransitionStates(String body, TransitionInfo trans) {
        // 简单启发式：查找状态名模式
        for (String stateName : trans.getFromState() == null ? Arrays.asList("State", "S", "s") : Collections.<String>emptyList()) {
            Pattern statePattern = Pattern.compile("\\b(" + stateName + ")\\s*=\\s*([A-Z][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);
            Matcher stateMatcher = statePattern.matcher(body);
            if (stateMatcher.find()) {
                trans.setFromState(stateMatcher.group(2));
                break;
            }
        }
        
        // 类似地识别目标状态
        for (String keyword : Arrays.asList("next", "after", "then", "to")) {
            Pattern toPattern = Pattern.compile("\\b" + keyword + "\\s+([A-Z][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);
            Matcher toMatcher = toPattern.matcher(body);
            if (toMatcher.find()) {
                trans.setToState(toMatcher.group(1));
                break;
            }
        }
        
        // 如果未识别，设置默认值
        if (trans.getFromState() == null) trans.setFromState("Unknown");
        if (trans.getToState() == null) trans.setToState("Unknown");
    }

    /**
     * 识别守卫条件和动作
     */
    private static void identifyGuardAndAction(String body, TransitionInfo trans) {
        // 查找守卫条件模式：if、when、guard
        Pattern guardPattern = Pattern.compile("\\b(if|when|guard)\\s*\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher guardMatcher = guardPattern.matcher(body);
        if (guardMatcher.find()) {
            trans.setGuardCondition(guardMatcher.group(2).trim());
        }
        
        // 查找动作模式：do、then、action
        Pattern actionPattern = Pattern.compile("\\b(do|then|action)\\s*\\{(.*?)\\}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher actionMatcher = actionPattern.matcher(body);
        if (actionMatcher.find()) {
            trans.setAction(actionMatcher.group(2).trim());
        }
    }

    /**
     * 提取约束和不变式
     */
    private static void extractConstraintsAndInvariants(String content, FormalSpecAnalysis result) {
        // 匹配 inv 定义（Alloy 中的不变量）
        Pattern invPattern = Pattern.compile("\\binv\\s+([a-z][a-zA-Z0-9_]*)\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher invMatcher = invPattern.matcher(content);
        
        while (invMatcher.find()) {
            String invName = invMatcher.group(1);
            String expression = invMatcher.group(2);
            
            InvariantInfo inv = new InvariantInfo();
            inv.setName(invName);
            inv.setExpression(expression.trim());
            
            // 识别涉及的状态
            for (String stateName : result.getStates().keySet()) {
                if (expression.contains(stateName)) {
                    inv.getInvolvedStates().add(stateName);
                }
            }
            
            result.getInvariants().put(invName, inv);
        }
        
        // 匹配 fact 定义（可能作为全局约束）
        Pattern factPattern = Pattern.compile("\\bfact\\s+([a-z][a-zA-Z0-9_]*)\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher factMatcher = factPattern.matcher(content);
        
        while (factMatcher.find()) {
            String constraintName = factMatcher.group(1);
            String expression = factMatcher.group(2);
            
            ConstraintInfo constraint = new ConstraintInfo();
            constraint.setName(constraintName);
            constraint.setType("global");
            constraint.setExpression(expression.trim());
            
            // 识别涉及的状态和转移
            for (String stateName : result.getStates().keySet()) {
                if (expression.contains(stateName)) {
                    constraint.getInvolvedStates().add(stateName);
                }
            }
            for (String transName : result.getTransitions().keySet()) {
                if (expression.contains(transName)) {
                    constraint.getInvolvedTransitions().add(transName);
                }
            }
            
            result.getConstraints().put(constraintName, constraint);
        }
    }

    /**
     * 提取函数和事实
     */
    private static void extractFunctionsAndFacts(String content, FormalSpecAnalysis result) {
        // 提取所有函数（fun）定义
        Pattern funPattern = Pattern.compile("\\bfun\\s+([a-z][a-zA-Z0-9_]*)\\s*\\(.*?\\)\\s*\\{.*?\\}", Pattern.DOTALL);
        Matcher funMatcher = funPattern.matcher(content);
        while (funMatcher.find()) {
            String funDef = funMatcher.group(0);
            if (!funDef.contains("pred")) { // 排除已作为转移处理的
                result.getFunctions().add(funDef);
            }
        }
        
        // 提取所有事实（fact）定义
        Pattern factPattern = Pattern.compile("\\bfact\\s+[^{]*\\{.*?\\}", Pattern.DOTALL);
        Matcher factMatcher = factPattern.matcher(content);
        while (factMatcher.find()) {
            String factDef = factMatcher.group(0);
            result.getFacts().add(factDef);
        }
    }

    /**
     * 建立关联关系
     */
    private static void establishRelationships(FormalSpecAnalysis result) {
        // 完善转移关系的状态关联
        for (TransitionInfo trans : result.getTransitions().values()) {
            if ("Unknown".equals(trans.getFromState())) {
                // 尝试从参数或表达式中推断
                for (String param : trans.getParameters()) {
                    for (String stateName : result.getStates().keySet()) {
                        if (param.contains(stateName)) {
                            trans.setFromState(stateName);
                            break;
                        }
                    }
                    if (!"Unknown".equals(trans.getFromState())) break;
                }
            }
            
            // 为约束添加涉及的转移
            for (ConstraintInfo constraint : result.getConstraints().values()) {
                if (constraint.getExpression().contains(trans.getName())) {
                    constraint.getInvolvedTransitions().add(trans.getName());
                }
            }
        }
    }

    /**
     * 将解析结果转换为 JSON 字符串
     */
    public static String toJson(FormalSpecAnalysis analysis) {
        if (!analysis.isSuccess()) {
            return JSON.toJSONString(Collections.singletonMap("error", analysis.getError()));
        }
        
        JSONObject result = new JSONObject();
        result.put("success", true);
        
        // 状态列表
        JSONArray statesArray = new JSONArray();
        for (StateInfo state : analysis.getStates().values()) {
            JSONObject stateObj = new JSONObject();
            stateObj.put("name", state.getName());
            stateObj.put("type", state.getSignatureType());
            stateObj.put("fields", state.getFields());
            stateObj.put("parents", new ArrayList<>(state.getParentStates()));
            stateObj.put("comment", state.getComment());
            statesArray.add(stateObj);
        }
        result.put("states", statesArray);
        
        // 转移列表
        JSONArray transitionsArray = new JSONArray();
        for (TransitionInfo trans : analysis.getTransitions().values()) {
            JSONObject transObj = new JSONObject();
            transObj.put("name", trans.getName());
            transObj.put("from", trans.getFromState());
            transObj.put("to", trans.getToState());
            transObj.put("parameters", trans.getParameters());
            transObj.put("guard", trans.getGuardCondition());
            transObj.put("action", trans.getAction());
            transObj.put("comment", trans.getComment());
            transitionsArray.add(transObj);
        }
        result.put("transitions", transitionsArray);
        
        // 约束列表
        JSONArray constraintsArray = new JSONArray();
        for (ConstraintInfo constraint : analysis.getConstraints().values()) {
            JSONObject constraintObj = new JSONObject();
            constraintObj.put("name", constraint.getName());
            constraintObj.put("type", constraint.getType());
            constraintObj.put("expression", constraint.getExpression());
            constraintObj.put("involvedStates", new ArrayList<>(constraint.getInvolvedStates()));
            constraintObj.put("involvedTransitions", new ArrayList<>(constraint.getInvolvedTransitions()));
            constraintObj.put("comment", constraint.getComment());
            constraintsArray.add(constraintObj);
        }
        result.put("constraints", constraintsArray);
        
        // 不变量列表
        JSONArray invariantsArray = new JSONArray();
        for (InvariantInfo inv : analysis.getInvariants().values()) {
            JSONObject invObj = new JSONObject();
            invObj.put("name", inv.getName());
            invObj.put("expression", inv.getExpression());
            invObj.put("involvedStates", new ArrayList<>(inv.getInvolvedStates()));
            invObj.put("comment", inv.getComment());
            invariantsArray.add(invObj);
        }
        result.put("invariants", invariantsArray);
        
        // 其他信息
        result.put("signatures", analysis.getSignatures());
        result.put("functions", analysis.getFunctions());
        result.put("facts", analysis.getFacts());
        
        return result.toJSONString();
    }

    /**
     * 从 JSON 恢复解析结果
     */
    public static FormalSpecAnalysis fromJson(String json) {
        FormalSpecAnalysis analysis = new FormalSpecAnalysis();
        
        try {
            JSONObject obj = JSON.parseObject(json);
            analysis.setSuccess(obj.getBooleanValue("success"));
            
            if (!analysis.isSuccess()) {
                analysis.setError(obj.getString("error"));
                return analysis;
            }
            
            // 恢复状态
            JSONArray statesArray = obj.getJSONArray("states");
            if (statesArray != null) {
                for (int i = 0; i < statesArray.size(); i++) {
                    JSONObject stateObj = statesArray.getJSONObject(i);
                    StateInfo state = new StateInfo();
                    state.setName(stateObj.getString("name"));
                    state.setSignatureType(stateObj.getString("type"));
                    state.setFields(stateObj.getJSONArray("fields").toList(String.class));
                    state.setParentStates(new LinkedHashSet<>(stateObj.getJSONArray("parents").toList(String.class)));
                    state.setComment(stateObj.getString("comment"));
                    analysis.getStates().put(state.getName(), state);
                }
            }
            
            // 恢复转移
            JSONArray transitionsArray = obj.getJSONArray("transitions");
            if (transitionsArray != null) {
                for (int i = 0; i < transitionsArray.size(); i++) {
                    JSONObject transObj = transitionsArray.getJSONObject(i);
                    TransitionInfo trans = new TransitionInfo();
                    trans.setName(transObj.getString("name"));
                    trans.setFromState(transObj.getString("from"));
                    trans.setToState(transObj.getString("to"));
                    trans.setParameters(transObj.getJSONArray("parameters").toList(String.class));
                    trans.setGuardCondition(transObj.getString("guard"));
                    trans.setAction(transObj.getString("action"));
                    trans.setComment(transObj.getString("comment"));
                    analysis.getTransitions().put(trans.getName(), trans);
                }
            }
            
            // 恢复约束
            JSONArray constraintsArray = obj.getJSONArray("constraints");
            if (constraintsArray != null) {
                for (int i = 0; i < constraintsArray.size(); i++) {
                    JSONObject constraintObj = constraintsArray.getJSONObject(i);
                    ConstraintInfo constraint = new ConstraintInfo();
                    constraint.setName(constraintObj.getString("name"));
                    constraint.setType(constraintObj.getString("type"));
                    constraint.setExpression(constraintObj.getString("expression"));
                    constraint.setInvolvedStates(new LinkedHashSet<>(constraintObj.getJSONArray("involvedStates").toList(String.class)));
                    constraint.setInvolvedTransitions(new LinkedHashSet<>(constraintObj.getJSONArray("involvedTransitions").toList(String.class)));
                    constraint.setComment(constraintObj.getString("comment"));
                    analysis.getConstraints().put(constraint.getName(), constraint);
                }
            }
            
            // 恢复不变量
            JSONArray invariantsArray = obj.getJSONArray("invariants");
            if (invariantsArray != null) {
                for (int i = 0; i < invariantsArray.size(); i++) {
                    JSONObject invObj = invariantsArray.getJSONObject(i);
                    InvariantInfo inv = new InvariantInfo();
                    inv.setName(invObj.getString("name"));
                    inv.setExpression(invObj.getString("expression"));
                    inv.setInvolvedStates(new LinkedHashSet<>(invObj.getJSONArray("involvedStates").toList(String.class)));
                    inv.setComment(invObj.getString("comment"));
                    analysis.getInvariants().put(inv.getName(), inv);
                }
            }
            
            // 恢复其他信息
            analysis.setSignatures(obj.getJSONArray("signatures").toList(String.class));
            analysis.setFunctions(obj.getJSONArray("functions").toList(String.class));
            analysis.setFacts(obj.getJSONArray("facts").toList(String.class));
            
        } catch (Exception e) {
            log.error("从 JSON 恢复 FormalSpecAnalysis 失败", e);
            analysis.setSuccess(false);
            analysis.setError("恢复失败: " + e.getMessage());
        }
        
        return analysis;
    }

    /**
     * 计算形式化规约的结构化特征向量
     * 用于相似度计算
     */
    public static Map<String, Double> extractFormalSpecFeatures(FormalSpecAnalysis analysis) {
        Map<String, Double> features = new LinkedHashMap<>();
        
        if (!analysis.isSuccess()) {
            return features;
        }
        
        // 1. 状态相关特征
        int stateCount = analysis.getStates().size();
        int abstractStateCount = 0;
        int enumStateCount = 0;
        int totalFields = 0;
        int maxFields = 0;
        
        for (StateInfo state : analysis.getStates().values()) {
            if ("abstract".equals(state.getSignatureType())) abstractStateCount++;
            if ("enum".equals(state.getSignatureType())) enumStateCount++;
            int fieldCount = state.getFields().size();
            totalFields += fieldCount;
            if (fieldCount > maxFields) maxFields = fieldCount;
        }
        
        features.put("state_count", (double) stateCount);
        features.put("abstract_state_ratio", stateCount > 0 ? (double) abstractStateCount / stateCount : 0.0);
        features.put("enum_state_ratio", stateCount > 0 ? (double) enumStateCount / stateCount : 0.0);
        features.put("avg_fields_per_state", stateCount > 0 ? (double) totalFields / stateCount : 0.0);
        features.put("max_fields", (double) maxFields);
        
        // 2. 转移相关特征
        int transitionCount = analysis.getTransitions().size();
        int totalParams = 0;
        int guardedTransitions = 0;
        int actionTransitions = 0;
        
        for (TransitionInfo trans : analysis.getTransitions().values()) {
            totalParams += trans.getParameters().size();
            if (!trans.getGuardCondition().isEmpty()) guardedTransitions++;
            if (!trans.getAction().isEmpty()) actionTransitions++;
        }
        
        features.put("transition_count", (double) transitionCount);
        features.put("avg_params_per_transition", transitionCount > 0 ? (double) totalParams / transitionCount : 0.0);
        features.put("guarded_transition_ratio", transitionCount > 0 ? (double) guardedTransitions / transitionCount : 0.0);
        features.put("action_transition_ratio", transitionCount > 0 ? (double) actionTransitions / transitionCount : 0.0);
        
        // 3. 约束相关特征
        int constraintCount = analysis.getConstraints().size();
        int invariantCount = analysis.getInvariants().size();
        int totalConstraintLength = 0;
        
        for (ConstraintInfo constraint : analysis.getConstraints().values()) {
            totalConstraintLength += constraint.getExpression().length();
        }
        for (InvariantInfo inv : analysis.getInvariants().values()) {
            totalConstraintLength += inv.getExpression().length();
        }
        
        features.put("constraint_count", (double) constraintCount);
        features.put("invariant_count", (double) invariantCount);
        features.put("avg_constraint_length", (constraintCount + invariantCount) > 0 ? 
            (double) totalConstraintLength / (constraintCount + invariantCount) : 0.0);
        
        // 4. 函数和事实相关特征
        features.put("function_count", (double) analysis.getFunctions().size());
        features.put("fact_count", (double) analysis.getFacts().size());
        
        // 5. 复杂度指标
        double complexityScore = stateCount * 1.0 + transitionCount * 1.5 + 
                               (constraintCount + invariantCount) * 2.0;
        features.put("complexity_score", complexityScore);
        
        // 6. 继承深度（最大继承链）
        int maxInheritanceDepth = calculateMaxInheritanceDepth(analysis);
        features.put("max_inheritance_depth", (double) maxInheritanceDepth);
        
        return features;
    }

    /**
     * 计算最大继承深度
     */
    private static int calculateMaxInheritanceDepth(FormalSpecAnalysis analysis) {
        int maxDepth = 0;
        Map<String, StateInfo> states = analysis.getStates();
        
        for (StateInfo state : states.values()) {
            int depth = calculateDepth(state, states, new HashSet<>());
            if (depth > maxDepth) maxDepth = depth;
        }
        
        return maxDepth;
    }
    
    private static int calculateDepth(StateInfo state, Map<String, StateInfo> allStates, Set<String> visited) {
        if (visited.contains(state.getName())) return 0; // 避免循环
        visited.add(state.getName());
        
        int maxParentDepth = 0;
        for (String parentName : state.getParentStates()) {
            StateInfo parent = allStates.get(parentName);
            if (parent != null) {
                int parentDepth = calculateDepth(parent, allStates, new HashSet<>(visited));
                if (parentDepth > maxParentDepth) maxParentDepth = parentDepth;
            }
        }
        
        return 1 + maxParentDepth;
    }

    // ==================== GAP-005 规约模型（约束子句 + 不变量，供一致性 Con/Inv 维度计分） ====================

    /** 约束子句分类（GAP-023 阈值标定复用；OTHER 不参与 Con 计分，避免误惩罚） */
    public enum ConstraintKind {
        RANGE, NULL_CHECK, EXCEPTION_PATH, RESOURCE_RELEASE, UNIQUENESS, OTHER
    }

    /** 约束子句 */
    public static class ConstraintClause {
        private String clauseText;
        private ConstraintKind kind;

        public String getClauseText() { return clauseText; }
        public void setClauseText(String clauseText) { this.clauseText = clauseText; }
        public ConstraintKind getKind() { return kind; }
        public void setKind(ConstraintKind kind) { this.kind = kind; }
    }

    /** 不变量子句 */
    public static class InvariantClause {
        private String clauseText;
        private List<String> states = new ArrayList<>();
        private boolean hasTransition;

        public String getClauseText() { return clauseText; }
        public void setClauseText(String clauseText) { this.clauseText = clauseText; }
        public List<String> getStates() { return states; }
        public void setStates(List<String> states) { this.states = states; }
        public boolean isHasTransition() { return hasTransition; }
        public void setHasTransition(boolean hasTransition) { this.hasTransition = hasTransition; }
    }

    /** 规约模型：可计分约束子句 + 不变量 */
    public static class SpecModel {
        private List<ConstraintClause> constraints = new ArrayList<>();
        private List<InvariantClause> invariants = new ArrayList<>();

        public List<ConstraintClause> getConstraints() { return constraints; }
        public void setConstraints(List<ConstraintClause> constraints) { this.constraints = constraints; }
        public List<InvariantClause> getInvariants() { return invariants; }
        public void setInvariants(List<InvariantClause> invariants) { this.invariants = invariants; }

        /** 可计分子句：kind != OTHER */
        public List<ConstraintClause> countableConstraints() {
            List<ConstraintClause> list = new ArrayList<>();
            for (ConstraintClause c : constraints) {
                if (c.getKind() != ConstraintKind.OTHER) list.add(c);
            }
            return list;
        }
    }

    /**
     * GAP-005：解析 Alloy 规约中的约束子句与不变量（兼容规则模板与 LLM 生成的通用 Alloy 语法子集）。
     * 全程 try/catch，异常返回空 SpecModel（触发降级启发式）。
     */
    public static SpecModel parse(String alloyCode) {
        SpecModel model = new SpecModel();
        if (alloyCode == null || alloyCode.trim().isEmpty()) {
            return model;
        }
        try {
            String code = stripComments(alloyCode);
            // 从 fact 块 / assert 块 / pred 主体中提取子句（按行/分号切分）
            for (String block : extractBodyBlocks(code)) {
                for (String rawClause : splitClauses(block)) {
                    String clause = rawClause.trim();
                    if (clause.isEmpty() || clause.startsWith("//")) continue;
                    ConstraintClause cc = new ConstraintClause();
                    cc.setClauseText(clause);
                    cc.setKind(classifyConstraint(clause));
                    model.getConstraints().add(cc);
                    // 不变量：all 量化且谓词涉及状态等值/转移的子句
                    if (isInvariantClause(clause)) {
                        InvariantClause inv = new InvariantClause();
                        inv.setClauseText(clause);
                        inv.setHasTransition(clause.contains("=>") || clause.contains("implies")
                                || clause.contains("'") || clause.contains("state'"));
                        inv.setStates(extractStateNames(clause));
                        model.getInvariants().add(inv);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 SpecModel 失败，返回空模型（触发降级启发式）: {}", e.getMessage());
            return new SpecModel();
        }
        return model;
    }

    /** 提取 fact/assert/pred 等花括号块内容（含嵌套，括号平衡扫描） */
    private static List<String> extractBodyBlocks(String code) {
        List<String> blocks = new ArrayList<>();
        Pattern p = Pattern.compile("\\b(fact|assert|pred|fun)\\s+[A-Za-z_][A-Za-z0-9_]*[^{]*\\{");
        Matcher m = p.matcher(code);
        while (m.find()) {
            int start = m.end();
            int depth = 1;
            int i = start;
            while (i < code.length() && depth > 0) {
                char c = code.charAt(i);
                if (c == '{') depth++;
                if (c == '}') depth--;
                i++;
            }
            if (depth == 0) {
                blocks.add(code.substring(start, i - 1));
            }
        }
        return blocks;
    }

    /** 按分号与换行切分子句 */
    private static List<String> splitClauses(String block) {
        List<String> clauses = new ArrayList<>();
        String normalized = block.replace(';', '\n');
        for (String line : normalized.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) clauses.add(t);
        }
        return clauses;
    }

    /** 子句分类启发（用于映射代码实现证据检测器） */
    private static ConstraintKind classifyConstraint(String clause) {
        String c = clause.toLowerCase();
        // RANGE：数值比较（>=/<=/>/</范围词），须含数字以避免与 Alloy 蕴含符 "=>"（字符序列即 ">="）误判
        boolean numericCompare = (c.contains(">=") || c.contains("<=") || c.contains("> ") || c.contains(" <")
                || c.contains("范围") || c.contains("between")) && c.matches("(?s).*\\d.*");
        if (numericCompare) return ConstraintKind.RANGE;
        if (c.contains("null") || c.contains("none") || c.contains("非空") || c.contains("不能为空")
                || c.contains("required") || c.contains("nonempty")) return ConstraintKind.NULL_CHECK;
        if (c.contains("exception") || c.contains("异常") || c.contains("throw")) return ConstraintKind.EXCEPTION_PATH;
        if (c.contains("close") || c.contains("release") || c.contains("resource")
                || c.contains("资源") || c.contains("释放")) return ConstraintKind.RESOURCE_RELEASE;
        if (c.contains("disj") || c.contains("unique") || c.contains("唯一")
                || c.contains("不能重复") || c.contains("no duplicate")) return ConstraintKind.UNIQUENESS;
        return ConstraintKind.OTHER;
    }

    /** 不变量判定：all 量化 + 状态等值/转移谓词 */
    private static boolean isInvariantClause(String clause) {
        String c = clause.toLowerCase();
        if (!c.contains("all ")) return false;
        return c.contains("=") || c.contains("=>") || c.contains("implies")
                || c.contains("in state") || c.contains("state");
    }

    /** 从子句中提取状态名（首字母大写标识符，排除量词/关系关键字） */
    private static List<String> extractStateNames(String clause) {
        List<String> states = new ArrayList<>();
        Pattern p = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
        Matcher m = p.matcher(clause);
        while (m.find()) {
            String s = m.group(1);
            if ("State".equals(s) || "Time".equals(s) || "Int".equals(s)) continue;
            if (!states.contains(s)) states.add(s);
        }
        return states;
    }

    /** 去除行注释与块注释 */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        return noBlock.replaceAll("//[^\n]*", " ");
    }
}