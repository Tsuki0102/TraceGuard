package com.traceguard.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-011：DefectStatus 枚举单元测试（无 Spring、无 Mockito）
 */
@DisplayName("GAP-011 缺陷状态枚举")
class DefectStatusTest {

    @Nested
    @DisplayName("code 与 label 基础校验")
    class CodeAndLabelTests {

        @Test
        @DisplayName("所有枚举值 code 唯一")
        void uniqueCodes() {
            String[] codes = new String[DefectStatus.values().length];
            for (int i = 0; i < DefectStatus.values().length; i++) {
                codes[i] = DefectStatus.values()[i].getCode();
            }
            assertThat(codes).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("PENDING/PROCESSING/RESOLVED/IGNORED 的 code 和 label 正确")
        void codeAndLabelValues() {
            assertThat(DefectStatus.PENDING.getCode()).isEqualTo("pending");
            assertThat(DefectStatus.PENDING.getLabel()).isEqualTo("待处理");

            assertThat(DefectStatus.PROCESSING.getCode()).isEqualTo("processing");
            assertThat(DefectStatus.PROCESSING.getLabel()).isEqualTo("处理中");

            assertThat(DefectStatus.RESOLVED.getCode()).isEqualTo("resolved");
            assertThat(DefectStatus.RESOLVED.getLabel()).isEqualTo("已解决");

            assertThat(DefectStatus.IGNORED.getCode()).isEqualTo("ignored");
            assertThat(DefectStatus.IGNORED.getLabel()).isEqualTo("已忽略");
        }
    }

    @Nested
    @DisplayName("isValid 合法性校验")
    class IsValidTests {

        @Test
        @DisplayName("四状态 code 均返回 true")
        void validCodesReturnTrue() {
            assertThat(DefectStatus.isValid("pending")).isTrue();
            assertThat(DefectStatus.isValid("processing")).isTrue();
            assertThat(DefectStatus.isValid("resolved")).isTrue();
            assertThat(DefectStatus.isValid("ignored")).isTrue();
        }

        @Test
        @DisplayName("非法状态码返回 false")
        void invalidCodesReturnFalse() {
            assertThat(DefectStatus.isValid("closed")).isFalse();
            assertThat(DefectStatus.isValid("done")).isFalse();
            assertThat(DefectStatus.isValid(null)).isFalse();
            assertThat(DefectStatus.isValid("")).isFalse();
        }
    }

    @Nested
    @DisplayName("labelOf 标签解析")
    class LabelOfTests {

        @Test
        @DisplayName("有效 code 返回对应中文标签")
        void validCodeReturnsLabel() {
            assertThat(DefectStatus.labelOf("pending")).isEqualTo("待处理");
            assertThat(DefectStatus.labelOf("processing")).isEqualTo("处理中");
            assertThat(DefectStatus.labelOf("resolved")).isEqualTo("已解决");
            assertThat(DefectStatus.labelOf("ignored")).isEqualTo("已忽略");
        }

        @Test
        @DisplayName("null 或无效 code 默认返回待处理")
        void nullOrInvalidReturnsDefaultLabel() {
            assertThat(DefectStatus.labelOf(null)).isEqualTo("待处理");
            assertThat(DefectStatus.labelOf("unknown")).isEqualTo("待处理");
        }
    }

    @Nested
    @DisplayName("canTransition 合法流转矩阵")
    class CanTransitionTests {

        // pending -> processing / ignored
        @Test
        @DisplayName("pending 可流转至 processing 和 ignored")
        void pendingToProcessingAndIgnored() {
            assertThat(DefectStatus.canTransition("pending", "processing")).isTrue();
            assertThat(DefectStatus.canTransition("pending", "ignored")).isTrue();
        }

        @Test
        @DisplayName("pending 不可流转至 resolved")
        void pendingCannotGoDirectlyToResolved() {
            assertThat(DefectStatus.canTransition("pending", "resolved")).isFalse();
        }

        // processing -> resolved / ignored / pending
        @Test
        @DisplayName("processing 可流转至 resolved、ignored 和 pending")
        void processingToResolvedIgnoredPending() {
            assertThat(DefectStatus.canTransition("processing", "resolved")).isTrue();
            assertThat(DefectStatus.canTransition("processing", "ignored")).isTrue();
            assertThat(DefectStatus.canTransition("processing", "pending")).isTrue();
        }

        // resolved -> processing
        @Test
        @DisplayName("resolved 只能回退至 processing")
        void resolvedOnlyToProcessing() {
            assertThat(DefectStatus.canTransition("resolved", "processing")).isTrue();
            assertThat(DefectStatus.canTransition("resolved", "ignored")).isFalse();
            assertThat(DefectStatus.canTransition("resolved", "resolved")).isFalse();
        }

        // ignored -> processing
        @Test
        @DisplayName("ignored 只能重新处理，转至 processing")
        void ignoredOnlyToProcessing() {
            assertThat(DefectStatus.canTransition("ignored", "processing")).isTrue();
            assertThat(DefectStatus.canTransition("ignored", "resolved")).isFalse();
            assertThat(DefectStatus.canTransition("ignored", "ignored")).isFalse();
        }

        // self-transition not allowed
        @Test
        @DisplayName("同一状态之间不允许自流转")
        void noSelfTransition() {
            assertThat(DefectStatus.canTransition("pending", "pending")).isFalse();
            assertThat(DefectStatus.canTransition("processing", "processing")).isFalse();
            assertThat(DefectStatus.canTransition("resolved", "resolved")).isFalse();
            assertThat(DefectStatus.canTransition("ignored", "ignored")).isFalse();
        }

        // null handling
        @Test
        @DisplayName("null 参数一律返回 false")
        void nullParamsReturnFalse() {
            assertThat(DefectStatus.canTransition(null, "processing")).isFalse();
            assertThat(DefectStatus.canTransition("pending", null)).isFalse();
            assertThat(DefectStatus.canTransition(null, null)).isFalse();
        }

        // illegal source
        @Test
        @DisplayName("未知源状态返回 false")
        void unknownSourceReturnsFalse() {
            assertThat(DefectStatus.canTransition("unknown", "processing")).isFalse();
        }
    }

    @Nested
    @DisplayName("allowedTargets 可用目标列表")
    class AllowedTargetsTests {

        @Test
        @DisplayName("pending 可流转目标为 processing/ignored/falsePositive（W5 误报治理）")
        void pendingAllowedTargets() {
            List<String> targets = DefectStatus.allowedTargets("pending");
            assertThat(targets).containsExactlyInAnyOrder("processing", "ignored", "falsePositive");
        }

        @Test
        @DisplayName("processing 可流转目标为 resolved/ignored/pending")
        void processingAllowedTargets() {
            List<String> targets = DefectStatus.allowedTargets("processing");
            assertThat(targets).contains("resolved", "ignored", "pending");
        }

        @Test
        @DisplayName("resolved 可流转目标仅 processing")
        void resolvedAllowedTargets() {
            List<String> targets = DefectStatus.allowedTargets("resolved");
            assertThat(targets).containsExactly("processing");
        }

        @Test
        @DisplayName("ignored 可流转目标仅 processing")
        void ignoredAllowedTargets() {
            List<String> targets = DefectStatus.allowedTargets("ignored");
            assertThat(targets).containsExactly("processing");
        }

        @Test
        @DisplayName("未知状态返回空列表")
        void unknownStatusReturnsEmptyList() {
            assertThat(DefectStatus.allowedTargets("unknown")).isEmpty();
        }
    }

    @Nested
    @DisplayName("完整生命周期场景")
    class LifecycleScenarios {

        @Test
        @DisplayName("正常修复流程：pending → processing → resolved")
        void normalFixFlow() {
            assertThat(DefectStatus.canTransition("pending", "processing")).isTrue();
            assertThat(DefectStatus.canTransition("processing", "resolved")).isTrue();
        }

        @Test
        @DisplayName("修复中发现未解决需重新打开：resolved → processing → ...")
        void reopenAfterResolve() {
            assertThat(DefectStatus.canTransition("resolved", "processing")).isTrue();
            assertThat(DefectStatus.canTransition("processing", "resolved")).isTrue();
        }

        @Test
        @DisplayName("直接忽略：pending → ignored")
        void directIgnore() {
            assertThat(DefectStatus.canTransition("pending", "ignored")).isTrue();
        }

        @Test
        @DisplayName("非法路径：pending → resolved（跳过中间态）")
        void illegalSkipToResolved() {
            assertThat(DefectStatus.canTransition("pending", "resolved")).isFalse();
        }

        @Test
        @DisplayName("非法路径：resolved → ignored（跨态跳转）")
        void illegalCrossTransition() {
            assertThat(DefectStatus.canTransition("resolved", "ignored")).isFalse();
        }
    }
}
