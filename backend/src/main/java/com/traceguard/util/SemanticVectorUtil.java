package com.traceguard.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 代码语义向量化工具（FR-CHECK-001 语义分析支撑，GAP-004 升级）
 * - 保留：从方法AST提取语义特征词的 terms 生成逻辑（降级兜底）。
 * - 新增：稠密向量 JSON 输出/解析（兼容新旧格式，供真实 Embedding 使用）。
 * 新格式：{"vector":[0.012,-0.034,...],"dim":512,"terms":"..."}；旧格式（terms-only）：{"terms":"..."}。
 */
public final class SemanticVectorUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticVectorUtil() {
    }

    /** 语义向量结构：vector 可能为 null（旧格式/降级），terms 作为降级字段始终保留 */
    public static final class SemanticVector {
        private float[] vector;
        private int dim;
        private String terms = "";

        public float[] getVector() { return vector; }
        public int getDim() { return dim; }
        public String getTerms() { return terms; }
        public boolean hasVector() { return vector != null && vector.length > 0; }

        public String toJson() {
            return SemanticVectorUtil.toJson(vector, terms);
        }
    }

    /** 生成稠密向量 JSON（真实 Embedding 输出格式；vector 为 null 时输出 terms-only 旧格式） */
    public static String toJson(float[] vector, String terms) {
        String t = terms == null ? "" : terms;
        if (vector == null || vector.length == 0) {
            return "{\"terms\":\"" + escape(t) + "\"}";
        }
        StringBuilder sb = new StringBuilder("{\"vector\":[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("],\"dim\":").append(vector.length)
          .append(",\"terms\":\"").append(escape(t)).append("\"}");
        return sb.toString();
    }

    /**
     * B3 增量量化（2026-09-03）：判断 semanticVector JSON 是否已含稠密向量。
     * 增量分析复用的存量代码/需求单元已带向量 -> embedSemantics 跳过重算，
     * 使"端到端二次分析"的向量化阶段只重算变更单元（v2 修复：此前每次全量重算，
     * 占规则模式端到端 90%+ 耗时，把增量解析的收益完全淹没）。
     */
    public static boolean hasDenseVector(String semanticVectorJson) {
        if (semanticVectorJson == null || semanticVectorJson.isEmpty()
                || !semanticVectorJson.contains("\"vector\"")) {
            return false;
        }
        try {
            return parse(semanticVectorJson).hasVector();
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析语义向量JSON，兼容新旧格式：无 vector 键 -> terms-only（vector=null） */
    public static SemanticVector parse(String json) {
        SemanticVector result = new SemanticVector();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            result.terms = node.has("terms") && node.get("terms").isTextual() ? node.get("terms").asText() : "";
            if (node.has("vector") && node.get("vector").isArray()) {
                JsonNode arr = node.get("vector");
                float[] vector = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    vector[i] = (float) arr.get(i).asDouble();
                }
                result.vector = vector;
                result.dim = node.has("dim") && node.get("dim").isNumber()
                        ? node.get("dim").asInt() : vector.length;
            } else {
                result.vector = null;
                result.dim = 0;
            }
            return result;
        } catch (Exception e) {
            return result;
        }
    }

    /** 生成旧格式（terms-only）JSON，断链/降级时使用 */
    public static String termsOnly(String terms) {
        return toJson(null, terms);
    }

    /** 生成语义向量JSON（旧签名兼容）：{"terms":"save order loop branch"} */
    public static String buildVector(MethodDeclaration method) {
        return termsOnly(String.join(" ", extractTerms(method)));
    }

    /** 提取语义特征词（去重、小写、保序） */
    public static List<String> extractTerms(MethodDeclaration method) {
        Set<String> terms = new LinkedHashSet<>();
        // 结构特征：控制流形态
        if (!method.findAll(ForStmt.class).isEmpty() || !method.findAll(ForEachStmt.class).isEmpty()
                || !method.findAll(WhileStmt.class).isEmpty() || !method.findAll(DoStmt.class).isEmpty()) {
            terms.add("loop");
        }
        if (!method.findAll(IfStmt.class).isEmpty()) {
            terms.add("branch");
        }
        if (!method.findAll(SwitchStmt.class).isEmpty()) {
            terms.add("switch");
        }
        if (!method.findAll(TryStmt.class).isEmpty()) {
            terms.add("try");
        }
        if (!method.findAll(ThrowStmt.class).isEmpty()) {
            terms.add("throw");
        }
        if (!method.findAll(ReturnStmt.class).isEmpty()) {
            terms.add("return");
        }
        // 行为动词：全部方法调用名驼峰切分（含递归自调用标记）
        String selfName = method.getNameAsString();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals(selfName)) {
                terms.add("recursion");
            }
            splitCamel(call.getNameAsString(), terms);
        }
        return new ArrayList<>(terms);
    }

    /** 驼峰切分为小写词（saveOrder -> save, order） */
    private static void splitCamel(String s, Set<String> out) {
        if (s == null || s.isEmpty()) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && cur.length() > 0) {
                out.add(cur.toString().toLowerCase());
                cur = new StringBuilder();
            }
            cur.append(Character.toLowerCase(c));
        }
        if (cur.length() > 0) {
            out.add(cur.toString().toLowerCase());
        }
    }

    /** JSON 字符串内嵌转义（terms 不含双引号更稳妥） */
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}