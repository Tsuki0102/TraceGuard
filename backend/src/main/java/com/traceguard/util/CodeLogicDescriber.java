package com.traceguard.util;

import com.traceguard.service.LlmService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GAP-006：代码逻辑描述器
 * - 规则版 analyzeCodeLogic：结构化业务逻辑分析（业务目的/步骤/规则/异常/数据操作/领域概念）
 * - LLM 版 describe：方法代码 + CFG 摘要 -> LLM 中文逻辑描述（LLM 不可用/失败返回 null，调用方降级）
 * 用于需求-代码一致性检查的语义匹配与逻辑还原。
 */
@Slf4j
@Component
public class CodeLogicDescriber {

    @Autowired(required = false)
    private LlmService llmService;

    /**
     * GAP-006：LLM 生成中文逻辑描述（方法源码 + CFG 摘要 -> 自然语言，覆盖输入输出/分支/循环/异常/资源管理）。
     * LLM 不可用/失败/输出为空返回 null（调用方降级 extractLogicDescription）。
     */
    public String describe(String methodCode, String cfgSummary) {
        if (llmService == null || !llmService.isEnabled()) {
            return null;
        }
        try {
            String desc = llmService.describeCode(methodCode, cfgSummary);
            if (desc == null || desc.trim().isEmpty()) {
                return null;
            }
            return desc.trim();
        } catch (Exception e) {
            log.warn("CodeLogicDescriber.describe 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 代码逻辑分析结果
     */
    @Data
    public static class LogicAnalysis {
        /** 方法签名 */
        private String methodSignature;
        /** 业务目的（一句话描述） */
        private String businessPurpose;
        /** 输入参数语义描述 */
        private Map<String, String> inputSemantics = new LinkedHashMap<>();
        /** 输出结果语义描述 */
        private String outputSemantics = "";
        /** 核心业务逻辑步骤 */
        private List<String> businessLogicSteps = new ArrayList<>();
        /** 业务规则/约束 */
        private List<String> businessRules = new ArrayList<>();
        /** 异常处理逻辑 */
        private List<String> exceptionHandling = new ArrayList<>();
        /** 数据操作（CRUD） */
        private List<String> dataOperations = new ArrayList<>();
        /** 涉及的领域概念 */
        private Set<String> domainConcepts = new LinkedHashSet<>();
        /** 是否分析成功 */
        private boolean success = false;
        /** 错误信息 */
        private String error = "";
        /** 置信度评分（0-1） */
        private double confidence = 0.0;
    }

    /**
     * 分析代码逻辑
     * @param className 类名
     * @param methodName 方法名
     * @param codeContent 代码内容
     * @return 逻辑分析结果
     */
    public LogicAnalysis analyzeCodeLogic(String className, String methodName, String codeContent) {
        LogicAnalysis analysis = new LogicAnalysis();
        analysis.setMethodSignature(className + "." + methodName);
        
        try {
            if (codeContent == null || codeContent.trim().isEmpty()) {
                analysis.setSuccess(false);
                analysis.setError("代码内容为空");
                return analysis;
            }
            
            // 1. 解析方法签名和参数
            extractMethodSignatureInfo(className, methodName, codeContent, analysis);
            
            // 2. 推断业务目的
            inferBusinessPurpose(methodName, codeContent, analysis);
            
            // 3. 提取业务逻辑步骤
            extractBusinessLogicSteps(codeContent, analysis);
            
            // 4. 识别业务规则和约束
            identifyBusinessRules(codeContent, analysis);
            
            // 5. 分析异常处理逻辑
            analyzeExceptionHandling(codeContent, analysis);
            
            // 6. 识别数据操作
            identifyDataOperations(codeContent, analysis);
            
            // 7. 提取领域概念
            extractDomainConcepts(codeContent, analysis);
            
            // 8. 计算置信度
            calculateConfidence(analysis);
            
            analysis.setSuccess(true);
            
        } catch (Exception e) {
            log.error("分析代码逻辑失败: {}", className + "." + methodName, e);
            analysis.setSuccess(false);
            analysis.setError("分析异常: " + e.getMessage());
        }
        
        return analysis;
    }

    /**
     * 解析方法签名信息
     */
    private void extractMethodSignatureInfo(String className, String methodName, 
                                          String codeContent, LogicAnalysis analysis) {
        // 提取参数信息
        Pattern paramPattern = Pattern.compile("\\(([^)]*)\\)");
        Matcher paramMatcher = paramPattern.matcher(codeContent);
        
        if (paramMatcher.find()) {
            String paramsText = paramMatcher.group(1);
            if (paramsText != null && !paramsText.trim().isEmpty()) {
                String[] params = paramsText.split(",");
                for (String param : params) {
                    param = param.trim();
                    if (!param.isEmpty()) {
                        // 简单推断参数语义
                        String paramSemantic = inferParameterSemantic(param);
                        analysis.getInputSemantics().put(param, paramSemantic);
                    }
                }
            }
        }
        
        // 推断返回类型语义
        Pattern returnPattern = Pattern.compile("\\b(public|private|protected)\\s+([A-Za-z<>\\[\\]]+)\\s+" + methodName);
        Matcher returnMatcher = returnPattern.matcher(codeContent);
        if (returnMatcher.find()) {
            String returnType = returnMatcher.group(2);
            analysis.setOutputSemantics(inferReturnTypeSemantic(returnType, methodName));
        }
    }

    /**
     * 推断参数语义
     */
    private String inferParameterSemantic(String paramDecl) {
        paramDecl = paramDecl.trim().toLowerCase();
        
        // 基于参数名推断
        if (paramDecl.contains("id")) return "标识符/ID";
        if (paramDecl.contains("name")) return "名称";
        if (paramDecl.contains("user")) return "用户信息";
        if (paramDecl.contains("request")) return "请求数据";
        if (paramDecl.contains("data")) return "数据";
        if (paramDecl.contains("list") || paramDecl.contains("array")) return "列表数据";
        if (paramDecl.contains("map")) return "映射数据";
        if (paramDecl.contains("config")) return "配置信息";
        if (paramDecl.contains("param")) return "参数";
        
        // 基于类型推断
        if (paramDecl.contains("string")) return "字符串数据";
        if (paramDecl.contains("int") || paramDecl.contains("integer") || paramDecl.contains("long")) 
            return "数值数据";
        if (paramDecl.contains("boolean") || paramDecl.contains("bool")) return "布尔值";
        if (paramDecl.contains("double") || paramDecl.contains("float")) return "浮点数值";
        if (paramDecl.contains("date") || paramDecl.contains("time")) return "时间数据";
        
        return "输入参数";
    }

    /**
     * 推断返回类型语义
     */
    private String inferReturnTypeSemantic(String returnType, String methodName) {
        returnType = returnType.toLowerCase();
        methodName = methodName.toLowerCase();
        
        // 基于方法名推断
        if (methodName.startsWith("get") || methodName.startsWith("find") || 
            methodName.startsWith("query") || methodName.startsWith("retrieve")) {
            return "查询结果";
        }
        if (methodName.startsWith("is") || methodName.startsWith("has") || 
            methodName.startsWith("check") || methodName.startsWith("validate")) {
            return "验证结果";
        }
        if (methodName.startsWith("create") || methodName.startsWith("add") || 
            methodName.startsWith("insert") || methodName.startsWith("save")) {
            return "创建/保存结果";
        }
        if (methodName.startsWith("update") || methodName.startsWith("modify")) {
            return "更新结果";
        }
        if (methodName.startsWith("delete") || methodName.startsWith("remove")) {
            return "删除结果";
        }
        if (methodName.startsWith("calculate") || methodName.startsWith("compute")) {
            return "计算结果";
        }
        
        // 基于返回类型推断
        if (returnType.contains("void")) return "无返回值（执行操作）";
        if (returnType.contains("boolean") || returnType.contains("bool")) return "操作成功标志";
        if (returnType.contains("list") || returnType.contains("array") || returnType.contains("collection")) 
            return "数据列表";
        if (returnType.contains("map")) return "键值对数据";
        if (returnType.contains("string")) return "字符串结果";
        if (returnType.contains("int") || returnType.contains("integer") || returnType.contains("long")) 
            return "数值结果";
        
        return "方法执行结果";
    }

    /**
     * 推断业务目的
     */
    private void inferBusinessPurpose(String methodName, String codeContent, LogicAnalysis analysis) {
        methodName = methodName.toLowerCase();
        codeContent = codeContent.toLowerCase();
        
        StringBuilder purpose = new StringBuilder();
        
        // 基于方法名推断
        if (methodName.startsWith("get") || methodName.startsWith("find") || 
            methodName.startsWith("query") || methodName.startsWith("retrieve") ||
            methodName.startsWith("select")) {
            purpose.append("查询");
            if (methodName.contains("byid")) purpose.append("指定ID的");
            if (methodName.contains("byname")) purpose.append("指定名称的");
            if (methodName.contains("all")) purpose.append("所有");
            purpose.append("数据");
        } 
        else if (methodName.startsWith("create") || methodName.startsWith("add") || 
                 methodName.startsWith("insert") || methodName.startsWith("save")) {
            purpose.append("创建");
            if (methodName.contains("user")) purpose.append("用户");
            if (methodName.contains("order")) purpose.append("订单");
            if (methodName.contains("file")) purpose.append("文件");
            purpose.append("记录");
        }
        else if (methodName.startsWith("update") || methodName.startsWith("modify")) {
            purpose.append("更新");
            if (methodName.contains("user")) purpose.append("用户");
            if (methodName.contains("order")) purpose.append("订单");
            if (methodName.contains("status")) purpose.append("状态");
            purpose.append("信息");
        }
        else if (methodName.startsWith("delete") || methodName.startsWith("remove")) {
            purpose.append("删除");
            if (methodName.contains("user")) purpose.append("用户");
            if (methodName.contains("order")) purpose.append("订单");
            if (methodName.contains("file")) purpose.append("文件");
            purpose.append("记录");
        }
        else if (methodName.startsWith("validate") || methodName.startsWith("check") || 
                 methodName.startsWith("verify")) {
            purpose.append("验证");
            if (methodName.contains("user")) purpose.append("用户");
            if (methodName.contains("password")) purpose.append("密码");
            if (methodName.contains("email")) purpose.append("邮箱");
            purpose.append("有效性");
        }
        else if (methodName.startsWith("calculate") || methodName.startsWith("compute")) {
            purpose.append("计算");
            if (methodName.contains("total")) purpose.append("总计");
            if (methodName.contains("average")) purpose.append("平均值");
            if (methodName.contains("sum")) purpose.append("总和");
            purpose.append("数值");
        }
        else if (methodName.startsWith("export") || methodName.startsWith("generate")) {
            purpose.append("生成");
            if (methodName.contains("report")) purpose.append("报告");
            if (methodName.contains("excel")) purpose.append("Excel文件");
            if (methodName.contains("pdf")) purpose.append("PDF文档");
        }
        else if (methodName.startsWith("import") || methodName.startsWith("upload")) {
            purpose.append("导入");
            if (methodName.contains("data")) purpose.append("数据");
            if (methodName.contains("file")) purpose.append("文件");
        }
        else if (methodName.startsWith("send") || methodName.startsWith("notify")) {
            purpose.append("发送");
            if (methodName.contains("email")) purpose.append("邮件");
            if (methodName.contains("message")) purpose.append("消息");
            if (methodName.contains("notification")) purpose.append("通知");
        }
        else {
            // 基于代码内容推断
            if (codeContent.contains("sql") || codeContent.contains("select") || 
                codeContent.contains("from") || codeContent.contains("where")) {
                purpose.append("执行数据库查询操作");
            }
            else if (codeContent.contains("http") || codeContent.contains("request") || 
                     codeContent.contains("response") || codeContent.contains("api")) {
                purpose.append("处理HTTP请求/响应");
            }
            else if (codeContent.contains("file") || codeContent.contains("stream") || 
                     codeContent.contains("read") || codeContent.contains("write")) {
                purpose.append("处理文件操作");
            }
            else {
                purpose.append("执行业务操作");
            }
        }
        
        // 添加领域信息
        if (methodName.contains("user") || codeContent.contains("user")) {
            purpose.append("（用户管理）");
        }
        if (methodName.contains("order") || codeContent.contains("order")) {
            purpose.append("（订单处理）");
        }
        if (methodName.contains("product") || codeContent.contains("product")) {
            purpose.append("（产品管理）");
        }
        if (methodName.contains("payment") || codeContent.contains("payment")) {
            purpose.append("（支付处理）");
        }
        
        analysis.setBusinessPurpose(purpose.toString());
    }

    /**
     * 提取业务逻辑步骤
     */
    private void extractBusinessLogicSteps(String codeContent, LogicAnalysis analysis) {
        List<String> steps = new ArrayList<>();
        String[] lines = codeContent.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                continue;
            }
            
            // 识别关键业务步骤
            if (line.contains("=") && !line.contains("==") && !line.contains("!=")) {
                // 赋值操作
                if (line.contains("get") || line.contains("find") || line.contains("query")) {
                    steps.add("获取数据：" + extractSubject(line));
                } else if (line.contains("set") || line.contains("put") || line.contains("add")) {
                    steps.add("设置数据：" + extractSubject(line));
                }
            }
            
            if (line.contains("if") && line.contains("(")) {
                steps.add("条件判断：" + extractCondition(line));
            }
            
            if (line.contains("for") || line.contains("while") || line.contains("foreach")) {
                steps.add("循环处理：" + extractLoopPurpose(line));
            }
            
            if (line.contains("return")) {
                steps.add("返回结果：" + extractReturnValue(line));
            }
            
            if (line.contains("throw new")) {
                steps.add("抛出异常：" + extractExceptionType(line));
            }
            
            if (line.contains("try") || line.contains("catch")) {
                steps.add("异常处理");
            }
            
            // 方法调用
            Pattern methodCallPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9]*)\\s*\\([^)]*\\)");
            Matcher matcher = methodCallPattern.matcher(line);
            while (matcher.find()) {
                String methodCall = matcher.group(1);
                if (!isCommonLibraryMethod(methodCall)) {
                    steps.add("调用方法：" + methodCall);
                }
            }
        }
        
        // 去重和排序
        Set<String> uniqueSteps = new LinkedHashSet<>(steps);
        analysis.setBusinessLogicSteps(new ArrayList<>(uniqueSteps));
    }

    /**
     * 提取赋值主题
     */
    private String extractSubject(String line) {
        if (line.contains("=")) {
            String leftSide = line.split("=")[0].trim();
            // 提取变量名
            String[] parts = leftSide.split("\\s+");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
        }
        return "数据";
    }

    /**
     * 提取条件
     */
    private String extractCondition(String line) {
        // 提取if条件部分
        Pattern conditionPattern = Pattern.compile("if\\s*\\(([^)]+)\\)");
        Matcher matcher = conditionPattern.matcher(line);
        if (matcher.find()) {
            String condition = matcher.group(1);
            // 简化条件描述
            condition = condition.replace("!=", "不等于")
                                .replace("==", "等于")
                                .replace(">", "大于")
                                .replace("<", "小于")
                                .replace(">=", "大于等于")
                                .replace("<=", "小于等于")
                                .replace("&&", "且")
                                .replace("||", "或");
            return condition.trim();
        }
        return "条件判断";
    }

    /**
     * 提取循环目的
     */
    private String extractLoopPurpose(String line) {
        if (line.contains("foreach") || line.contains("for (")) {
            return "遍历集合元素";
        } else if (line.contains("while")) {
            return "条件循环";
        }
        return "循环处理";
    }

    /**
     * 提取返回值
     */
    private String extractReturnValue(String line) {
        Pattern returnPattern = Pattern.compile("return\\s+(.+?);");
        Matcher matcher = returnPattern.matcher(line);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value.contains("null")) return "空值";
            if (value.contains("true") || value.contains("false")) return "布尔值";
            if (value.matches("\\d+")) return "数值";
            if (value.contains("\"")) return "字符串";
            return "计算结果";
        }
        return "操作结果";
    }

    /**
     * 提取异常类型
     */
    private String extractExceptionType(String line) {
        Pattern exceptionPattern = Pattern.compile("throw\\s+new\\s+([A-Za-z]+)");
        Matcher matcher = exceptionPattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "异常";
    }

    /**
     * 判断是否为常见库方法
     */
    private boolean isCommonLibraryMethod(String methodName) {
        Set<String> commonMethods = new HashSet<>(Arrays.asList(
            "println", "print", "log", "debug", "info", "error", "warn",
            "toString", "equals", "hashCode", "getClass", "size", "length",
            "add", "remove", "contains", "get", "put", "set", "isEnabled"
        ));
        return commonMethods.contains(methodName);
    }

    /**
     * 识别业务规则和约束
     */
    private void identifyBusinessRules(String codeContent, LogicAnalysis analysis) {
        List<String> rules = new ArrayList<>();
        String[] lines = codeContent.split("\n");
        
        for (String line : lines) {
            line = line.trim().toLowerCase();
            
            // 空值检查
            if (line.contains("!= null") || line.contains("== null") || 
                line.contains("isempty()") || line.contains("isblank()")) {
                rules.add("非空检查");
            }
            
            // 范围检查
            if (line.contains(">") || line.contains("<") || line.contains(">=") || line.contains("<=")) {
                if (line.contains("if")) {
                    rules.add("数值范围检查");
                }
            }
            
            // 格式验证
            if (line.contains("matches(") || line.contains("pattern") || 
                line.contains("regex") || line.contains("format")) {
                rules.add("格式验证");
            }
            
            // 权限检查
            if (line.contains("permission") || line.contains("role") || 
                line.contains("authority") || line.contains("auth")) {
                rules.add("权限验证");
            }
            
            // 业务状态检查
            if (line.contains("status") || line.contains("state")) {
                if (line.contains("if") || line.contains("switch")) {
                    rules.add("业务状态检查");
                }
            }
        }
        
        // 去重
        Set<String> uniqueRules = new LinkedHashSet<>(rules);
        analysis.setBusinessRules(new ArrayList<>(uniqueRules));
    }

    /**
     * 分析异常处理逻辑
     */
    private void analyzeExceptionHandling(String codeContent, LogicAnalysis analysis) {
        List<String> exceptionHandling = new ArrayList<>();
        
        if (codeContent.contains("try") && codeContent.contains("catch")) {
            exceptionHandling.add("使用try-catch块捕获异常");
        }
        
        if (codeContent.contains("throw new")) {
            exceptionHandling.add("主动抛出业务异常");
        }
        
        if (codeContent.contains("throws")) {
            exceptionHandling.add("声明检查异常");
        }
        
        if (codeContent.contains("finally")) {
            exceptionHandling.add("使用finally块进行资源清理");
        }
        
        // 识别特定异常类型
        Pattern exceptionPattern = Pattern.compile("catch\\s*\\((\\w+Exception)\\s+");
        Matcher matcher = exceptionPattern.matcher(codeContent);
        while (matcher.find()) {
            exceptionHandling.add("捕获" + matcher.group(1));
        }
        
        analysis.setExceptionHandling(exceptionHandling);
    }

    /**
     * 识别数据操作
     */
    private void identifyDataOperations(String codeContent, LogicAnalysis analysis) {
        List<String> operations = new ArrayList<>();
        codeContent = codeContent.toLowerCase();
        
        // 数据库操作
        if (codeContent.contains("sql") || codeContent.contains("select") || 
            codeContent.contains("insert") || codeContent.contains("update") || 
            codeContent.contains("delete") || codeContent.contains("from")) {
            operations.add("数据库操作");
        }
        
        // 文件操作
        if (codeContent.contains("file") || codeContent.contains("stream") || 
            codeContent.contains("read") || codeContent.contains("write") || 
            codeContent.contains("open") || codeContent.contains("close")) {
            operations.add("文件操作");
        }
        
        // 网络操作
        if (codeContent.contains("http") || codeContent.contains("url") || 
            codeContent.contains("socket") || codeContent.contains("request") || 
            codeContent.contains("response")) {
            operations.add("网络操作");
        }
        
        // 缓存操作
        if (codeContent.contains("cache") || codeContent.contains("redis") || 
            codeContent.contains("memcached")) {
            operations.add("缓存操作");
        }
        
        // 队列操作
        if (codeContent.contains("queue") || codeContent.contains("kafka") || 
            codeContent.contains("rabbitmq") || codeContent.contains("message")) {
            operations.add("消息队列操作");
        }
        
        analysis.setDataOperations(operations);
    }

    /**
     * 提取领域概念
     */
    private void extractDomainConcepts(String codeContent, LogicAnalysis analysis) {
        Set<String> concepts = new LinkedHashSet<>();
        
        // 常见领域概念关键词
        String[] domainKeywords = {
            "user", "customer", "member", "account",
            "order", "purchase", "transaction", "payment",
            "product", "item", "goods", "service",
            "invoice", "receipt", "bill",
            "shipment", "delivery", "shipping",
            "inventory", "stock", "warehouse",
            "category", "classification", "tag",
            "price", "cost", "discount", "tax",
            "address", "location", "region",
            "schedule", "appointment", "reservation",
            "report", "statistic", "analytics",
            "permission", "role", "authorization",
            "configuration", "setting", "preference"
        };
        
        codeContent = codeContent.toLowerCase();
        for (String keyword : domainKeywords) {
            if (codeContent.contains(keyword)) {
                concepts.add(keyword);
            }
        }
        
        analysis.setDomainConcepts(concepts);
    }

    /**
     * 计算置信度
     */
    private void calculateConfidence(LogicAnalysis analysis) {
        double confidence = 0.0;
        
        // 方法名分析质量
        if (!analysis.getBusinessPurpose().isEmpty()) {
            confidence += 0.2;
        }
        
        // 参数分析质量
        if (!analysis.getInputSemantics().isEmpty()) {
            confidence += 0.1;
        }
        
        // 业务逻辑步骤质量
        if (!analysis.getBusinessLogicSteps().isEmpty()) {
            confidence += 0.2;
            // 步骤越多，置信度越高（但有限制）
            int stepCount = analysis.getBusinessLogicSteps().size();
            confidence += Math.min(0.1, stepCount * 0.01);
        }
        
        // 业务规则识别质量
        if (!analysis.getBusinessRules().isEmpty()) {
            confidence += 0.1;
        }
        
        // 领域概念识别质量
        if (!analysis.getDomainConcepts().isEmpty()) {
            confidence += 0.1;
        }
        
        // 代码复杂度（简单启发式）
        int lineCount = analysis.getMethodSignature().length() + 
                       analysis.getBusinessPurpose().length();
        if (lineCount > 50) {
            confidence += 0.1; // 较复杂的代码通常分析更可靠
        }
        
        analysis.setConfidence(Math.min(1.0, confidence));
    }

    /**
     * 将分析结果转换为JSON字符串
     */
    public String toJson(LogicAnalysis analysis) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", analysis.isSuccess());
            result.put("methodSignature", analysis.getMethodSignature());
            result.put("businessPurpose", analysis.getBusinessPurpose());
            result.put("inputSemantics", analysis.getInputSemantics());
            result.put("outputSemantics", analysis.getOutputSemantics());
            result.put("businessLogicSteps", analysis.getBusinessLogicSteps());
            result.put("businessRules", analysis.getBusinessRules());
            result.put("exceptionHandling", analysis.getExceptionHandling());
            result.put("dataOperations", analysis.getDataOperations());
            result.put("domainConcepts", new ArrayList<>(analysis.getDomainConcepts()));
            result.put("confidence", analysis.getConfidence());
            
            if (!analysis.isSuccess()) {
                result.put("error", analysis.getError());
            }
            
            return com.alibaba.fastjson2.JSON.toJSONString(result, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        } catch (Exception e) {
            log.error("转换LogicAnalysis到JSON失败", e);
            return "{\"success\":false,\"error\":\"JSON转换失败\"}";
        }
    }

    /**
     * 从JSON恢复分析结果
     */
    public LogicAnalysis fromJson(String json) {
        LogicAnalysis analysis = new LogicAnalysis();
        
        try {
            Map<String, Object> map = com.alibaba.fastjson2.JSON.parseObject(json);
            analysis.setSuccess(Boolean.TRUE.equals(map.get("success")));
            analysis.setMethodSignature((String) map.get("methodSignature"));
            analysis.setBusinessPurpose((String) map.get("businessPurpose"));
            analysis.setOutputSemantics((String) map.get("outputSemantics"));
            analysis.setConfidence(((Number) map.get("confidence")).doubleValue());
            
            // 恢复Map类型字段
            Map<String, String> inputSemantics = (Map<String, String>) map.get("inputSemantics");
            if (inputSemantics != null) {
                analysis.setInputSemantics(inputSemantics);
            }
            
            // 恢复List类型字段
            analysis.setBusinessLogicSteps((List<String>) map.get("businessLogicSteps"));
            analysis.setBusinessRules((List<String>) map.get("businessRules"));
            analysis.setExceptionHandling((List<String>) map.get("exceptionHandling"));
            analysis.setDataOperations((List<String>) map.get("dataOperations"));
            
            // 恢复Set类型字段
            List<String> domainConceptsList = (List<String>) map.get("domainConcepts");
            if (domainConceptsList != null) {
                analysis.setDomainConcepts(new LinkedHashSet<>(domainConceptsList));
            }
            
            if (!analysis.isSuccess()) {
                analysis.setError((String) map.get("error"));
            }
            
        } catch (Exception e) {
            log.error("从JSON恢复LogicAnalysis失败", e);
            analysis.setSuccess(false);
            analysis.setError("恢复失败: " + e.getMessage());
        }
        
        return analysis;
    }

    /**
     * 生成用于相似度计算的逻辑特征向量
     */
    public Map<String, Double> extractLogicFeatures(LogicAnalysis analysis) {
        Map<String, Double> features = new LinkedHashMap<>();
        
        if (!analysis.isSuccess()) {
            return features;
        }
        
        // 1. 目的特征
        String purpose = analysis.getBusinessPurpose().toLowerCase();
        features.put("purpose_query", purpose.contains("查询") ? 1.0 : 0.0);
        features.put("purpose_create", purpose.contains("创建") || purpose.contains("添加") ? 1.0 : 0.0);
        features.put("purpose_update", purpose.contains("更新") || purpose.contains("修改") ? 1.0 : 0.0);
        features.put("purpose_delete", purpose.contains("删除") ? 1.0 : 0.0);
        features.put("purpose_validate", purpose.contains("验证") || purpose.contains("检查") ? 1.0 : 0.0);
        features.put("purpose_calculate", purpose.contains("计算") ? 1.0 : 0.0);
        
        // 2. 输入输出特征
        features.put("input_count", (double) analysis.getInputSemantics().size());
        features.put("has_output", analysis.getOutputSemantics().isEmpty() ? 0.0 : 1.0);
        
        // 3. 逻辑复杂度特征
        features.put("logic_step_count", (double) analysis.getBusinessLogicSteps().size());
        features.put("business_rule_count", (double) analysis.getBusinessRules().size());
        
        // 4. 异常处理特征
        features.put("exception_handling_count", (double) analysis.getExceptionHandling().size());
        
        // 5. 数据操作特征
        features.put("data_operation_count", (double) analysis.getDataOperations().size());
        features.put("has_db_operation", analysis.getDataOperations().contains("数据库操作") ? 1.0 : 0.0);
        features.put("has_file_operation", analysis.getDataOperations().contains("文件操作") ? 1.0 : 0.0);
        
        // 6. 领域概念特征
        features.put("domain_concept_count", (double) analysis.getDomainConcepts().size());
        features.put("has_user_domain", analysis.getDomainConcepts().contains("user") ? 1.0 : 0.0);
        features.put("has_order_domain", analysis.getDomainConcepts().contains("order") ? 1.0 : 0.0);
        features.put("has_product_domain", analysis.getDomainConcepts().contains("product") ? 1.0 : 0.0);
        
        // 7. 置信度特征
        features.put("analysis_confidence", analysis.getConfidence());
        
        return features;
    }
}