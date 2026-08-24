package com.traceguard.util;

import com.traceguard.util.SemanticVectorUtil.SemanticVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SemanticVectorUtil 单元测试（GAP-004 验证点 2，扩展）
 * 新稠密向量 JSON 格式输出/解析；新旧格式兼容（旧 terms-only 无 vector 键）；Embedding 不可用时输出旧格式。
 */
@DisplayName("语义向量工具（稠密向量 + 新旧格式兼容）单元测试")
class SemanticVectorUtilTest {

    @Test
    @DisplayName("toJson 输出新格式：vector/dim/terms 齐全")
    void toJsonOutputsDenseVectorFormat() {
        String json = SemanticVectorUtil.toJson(new float[]{0.1f, -0.2f, 0.3f}, "save order loop");
        assertThat(json).contains("\"vector\":[0.1");
        assertThat(json).contains("\"dim\":3");
        assertThat(json).contains("\"terms\":\"save order loop\"");
    }

    @Test
    @DisplayName("parse 新格式：hasVector=true 且 dim 正确")
    void parseNewFormat() {
        String json = SemanticVectorUtil.toJson(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, "a b");
        SemanticVector v = SemanticVectorUtil.parse(json);
        assertThat(v.hasVector()).isTrue();
        assertThat(v.getDim()).isEqualTo(4);
        assertThat(v.getVector()).hasSize(4);
        assertThat(v.getTerms()).isEqualTo("a b");
    }

    @Test
    @DisplayName("parse 旧格式（terms-only）：hasVector=false，terms 保留")
    void parseOldFormat() {
        String json = "{\"terms\":\"save order loop branch\"}";
        SemanticVector v = SemanticVectorUtil.parse(json);
        assertThat(v.hasVector()).isFalse();
        assertThat(v.getDim()).isEqualTo(0);
        assertThat(v.getTerms()).isEqualTo("save order loop branch");
    }

    @Test
    @DisplayName("parse 空/非法输入：返回空向量结构，不抛异常")
    void parseInvalidInput() {
        assertThat(SemanticVectorUtil.parse(null).hasVector()).isFalse();
        assertThat(SemanticVectorUtil.parse("").hasVector()).isFalse();
        assertThat(SemanticVectorUtil.parse("not json").hasVector()).isFalse();
    }

    @Test
    @DisplayName("termsOnly 输出旧格式（无 vector 键）")
    void termsOnlyProducesOldFormat() {
        String json = SemanticVectorUtil.termsOnly("save order");
        assertThat(json).doesNotContain("vector");
        assertThat(json).contains("\"terms\":\"save order\"");
    }

    @Test
    @DisplayName("Embedding 不可用（vector 为 null）时输出 terms-only 旧格式")
    void nullVectorFallsBackToTermsOnly() {
        String json = SemanticVectorUtil.toJson(null, "save order");
        assertThat(json).doesNotContain("vector");
        assertThat(json).contains("\"terms\":\"save order\"");
    }

    @Test
    @DisplayName("实例 toJson 与静态 toJson 一致")
    void instanceToJsonMatchesStatic() {
        float[] vector = new float[]{0.1f, 0.2f};
        SemanticVector v = SemanticVectorUtil.parse(SemanticVectorUtil.toJson(vector, "x y"));
        assertThat(v.toJson()).isEqualTo(SemanticVectorUtil.toJson(vector, "x y"));
    }
}
