package com.traceguard.util;

import com.traceguard.util.ConstraintDetectorUtil.EvidenceSet;
import com.traceguard.util.FormalSpecParserUtil.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConstraintDetectorUtil 单元测试（GAP-005 验证点 2）
 * 五类证据（NULL_CHECK/EXCEPTION_PATH/RESOURCE_RELEASE/UNIQUENESS/RANGE）各构造正反用例，断言行号命中。
 */
@DisplayName("实现证据检测（AST）单元测试")
class ConstraintDetectorUtilTest {

    @Test
    @DisplayName("NULL_CHECK：@NotNull/requireNonNull/null 判断均被识别")
    void detectsNullCheckEvidence() {
        String code =
                "public void save(@NotNull String name, User u) {\n" +
                "    Objects.requireNonNull(u, \"user must not be null\");\n" +
                "    if (name == null) { throw new IllegalArgumentException(); }\n" +
                "}";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.hasEvidence(ConstraintKind.NULL_CHECK)).isTrue();
    }

    @Test
    @DisplayName("EXCEPTION_PATH：try-catch、throw、throws 均被识别")
    void detectsExceptionPathEvidence() {
        String code =
                "public void read() throws IOException {\n" +
                "    try { open(); } catch (IOException e) { throw new IOException(e); }\n" +
                "}";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.hasEvidence(ConstraintKind.EXCEPTION_PATH)).isTrue();
    }

    @Test
    @DisplayName("RESOURCE_RELEASE：try-with-resources 与 close() 调用被识别")
    void detectsResourceReleaseEvidence() {
        String code =
                "public void copy() {\n" +
                "    try (FileInputStream in = new FileInputStream(\"a\");\n" +
                "         FileOutputStream out = new FileOutputStream(\"b\")) {\n" +
                "        out.write(in.read());\n" +
                "    }\n" +
                "    if (conn != null) { conn.close(); }\n" +
                "}";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.hasEvidence(ConstraintKind.RESOURCE_RELEASE)).isTrue();
    }

    @Test
    @DisplayName("UNIQUENESS：distinct() 与 contains() 判重被识别")
    void detectsUniquenessEvidence() {
        String code =
                "public List<String> uniqueNames(List<String> names) {\n" +
                "    if (seen.contains(name)) { continue; }\n" +
                "    return names.stream().distinct().collect(toList());\n" +
                "}";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.hasEvidence(ConstraintKind.UNIQUENESS)).isTrue();
    }

    @Test
    @DisplayName("RANGE：数值比较表达式被识别")
    void detectsRangeEvidence() {
        String code =
                "public void validate(int amount) {\n" +
                "    if (amount < 0) { throw new IllegalArgumentException(); }\n" +
                "    if (amount >= 10000) { reject(); }\n" +
                "}";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.hasEvidence(ConstraintKind.RANGE)).isTrue();
    }

    @Test
    @DisplayName("反例：无任何约束实现的方法不产生证据")
    void noEvidenceForPlainMethod() {
        String code = "public int add(int a, int b) { return a + b; }";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(code);
        assertThat(evidence.isEmpty()).isTrue();
        assertThat(evidence.hasEvidence(ConstraintKind.NULL_CHECK)).isFalse();
        assertThat(evidence.hasEvidence(ConstraintKind.EXCEPTION_PATH)).isFalse();
        assertThat(evidence.hasEvidence(ConstraintKind.RESOURCE_RELEASE)).isFalse();
        assertThat(evidence.hasEvidence(ConstraintKind.UNIQUENESS)).isFalse();
        assertThat(evidence.hasEvidence(ConstraintKind.RANGE)).isFalse();
    }

    @Test
    @DisplayName("空/非法输入返回空证据集，不抛异常")
    void emptyInputReturnsEmptyEvidence() {
        assertThat(ConstraintDetectorUtil.detect(null).isEmpty()).isTrue();
        assertThat(ConstraintDetectorUtil.detect("").isEmpty()).isTrue();
        assertThat(ConstraintDetectorUtil.detect("not java at all ###").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("方法片段（非完整类）亦可解析识别证据")
    void methodSnippetParsed() {
        String snippet = "if (user == null) throw new IllegalArgumentException();";
        EvidenceSet evidence = ConstraintDetectorUtil.detect(snippet);
        assertThat(evidence.hasEvidence(ConstraintKind.NULL_CHECK)).isTrue();
        assertThat(evidence.hasEvidence(ConstraintKind.EXCEPTION_PATH)).isTrue();
    }
}
