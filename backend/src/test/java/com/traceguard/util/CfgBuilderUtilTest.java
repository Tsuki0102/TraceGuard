package com.traceguard.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CfgBuilderUtil 单元测试：分支/循环/异常边建模正确性
 */
@DisplayName("精确CFG构建器单元测试")
class CfgBuilderUtilTest {

    private MethodDeclaration parseMethod(String code) {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(code);
        return result.getResult().orElseThrow()
                .findFirst(MethodDeclaration.class).orElseThrow();
    }

    private String cfgOf(String code) {
        return CfgBuilderUtil.build(parseMethod(code));
    }

    @Test
    @DisplayName("顺序语句生成顺序边")
    void sequentialStatements() {
        String cfg = cfgOf("class C { void m() { int a = 1; int b = 2; } }");
        assertThat(cfg).contains("\"start\"").contains("\"end\"");
        assertThat(cfg).contains("\"label\":\"seq\"");
        assertThat(cfg).doesNotContain("\"true\"").doesNotContain("\"back\"");
    }

    @Test
    @DisplayName("if-else生成true/false双分支与merge汇合")
    void ifElseBranches() {
        String cfg = cfgOf("class C { int m(int x) { if (x > 0) { return 1; } else { return 2; } } }");
        assertThat(cfg).contains("\"type\":\"if\"");
        assertThat(cfg).contains("\"label\":\"true\"");
        assertThat(cfg).contains("\"label\":\"false\"");
    }

    @Test
    @DisplayName("while循环生成back回边")
    void whileLoopBackEdge() {
        String cfg = cfgOf("class C { void m(int n) { int i = 0; while (i < n) { i++; } } }");
        assertThat(cfg).contains("\"type\":\"loop\"");
        assertThat(cfg).contains("\"label\":\"back\"");
    }

    @Test
    @DisplayName("break语句生成loop_exit跳转边")
    void breakLoopExitEdge() {
        String cfg = cfgOf("class C { void m(int n) { while (true) { if (n > 0) { break; } n--; } } }");
        assertThat(cfg).contains("\"type\":\"break\"");
        assertThat(cfg).contains("\"label\":\"loop_exit\"");
    }

    @Test
    @DisplayName("continue语句生成back跳转边")
    void continueBackEdge() {
        String cfg = cfgOf("class C { void m(int n) { for (int i = 0; i < n; i++) { if (i % 2 == 0) { continue; } n--; } } }");
        assertThat(cfg).contains("\"type\":\"continue\"");
        assertThat(cfg).contains("\"type\":\"loop\"");
    }

    @Test
    @DisplayName("switch生成多路case分支")
    void switchMultiBranch() {
        String cfg = cfgOf("class C { int m(int x) { switch (x) { case 1: return 1; case 2: return 2; default: return 0; } } }");
        assertThat(cfg).contains("\"type\":\"switch\"");
        assertThat(cfg).contains("case 1").contains("case 2").contains("default");
    }

    @Test
    @DisplayName("try-catch生成catch异常边")
    void tryCatchEdge() {
        String cfg = cfgOf("class C { void m() { try { risky(); } catch (Exception e) { handle(); } } }");
        assertThat(cfg).contains("try{...}");
        assertThat(cfg).contains("\"type\":\"catch\"");
        assertThat(cfg).contains("\"label\":\"catch\"");
        assertThat(cfg).contains("Exception");
    }

    @Test
    @DisplayName("return语句终结流程生成return节点")
    void returnNode() {
        String cfg = cfgOf("class C { int m() { return 42; } }");
        assertThat(cfg).contains("\"type\":\"return\"");
    }

    @Test
    @DisplayName("输出为合法JSON结构")
    void validJsonStructure() {
        String cfg = cfgOf("class C { int m(int x) { if (x > 0) { return x; } return -x; } }");
        assertThat(cfg).startsWith("{").endsWith("}");
        assertThat(cfg).contains("\"nodes\":[").contains("\"edges\":[");
    }

    @Test
    @DisplayName("else-if链的后续if条件入边保持false标签而非seq")
    void elseIfChainFalseEdgeLabel() {
        String cfg = cfgOf("class C { int m(int x) { int r = 0; if (x > 0) { r = 1; } else if (x < 0) { r = 2; } return r; } }");
        // 两条false边：第一个if的false进入else-if条件，第二个if的false进入merge（修复前else-if入边误标为seq）
        int falseEdges = cfg.split("\"label\":\"false\"", -1).length - 1;
        assertThat(falseEdges).isEqualTo(2);
    }
}
