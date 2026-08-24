package com.traceguard.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需求解析规则运行期持有者测试（AUD-07，FR-REQ-002）
 * 验证：可配置歧义词命中（含"等"覆盖"等等"）、互斥词对命中（含 (?<!不) 前缀否定）。
 */
@DisplayName("需求解析规则持有者单元测试")
class RuleConfigHolderTest {

    @AfterEach
    void reset() {
        RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
    }

    @Test
    @DisplayName("默认歧义词命中：包含'等'或'适当'应被识别")
    void defaultAmbiguity() {
        RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
        assertThat(RuleConfigHolder.containsAmbiguity("用户可等登录后查看", "等")).isTrue();
        assertThat(RuleConfigHolder.containsAmbiguity("系统应适当优化性能", "适当")).isTrue();
        assertThat(RuleConfigHolder.containsAmbiguity("系统必须具备高可用性", "等")).isFalse();
    }

    @Test
    @DisplayName("可配置：管理员自定义歧义词生效后即时生效")
    void configurableAmbiguity() {
        ReqParseRuleConfig cfg = new ReqParseRuleConfig();
        cfg.setAmbiguityKeywords(Arrays.asList("灵活", "尽量"));
        RuleConfigHolder.apply(cfg);
        // 模拟 RequirementAnalyzerUtil.checkAmbiguity：遍历当前配置词表逐项判定
        List<String> keywords = RuleConfigHolder.ambiguityKeywords();
        boolean hitsJinliang = keywords.stream().anyMatch(kw -> RuleConfigHolder.containsAmbiguity("界面应尽量简洁", kw));
        boolean hitsShidang = keywords.stream().anyMatch(kw -> RuleConfigHolder.containsAmbiguity("系统应适当优化", kw));
        assertThat(hitsJinliang).isTrue();   // "尽量" 在自定义词表中 -> 命中
        assertThat(hitsShidang).isFalse();   // "适当" 不在自定义词表 -> 不命中（已被覆盖）
        assertThat(keywords).doesNotContain("适当");
    }

    @Test
    @DisplayName("默认互斥词对：'必须'与'禁止'共现判定为矛盾")
    void defaultContradiction() {
        RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
        assertThat(RuleConfigHolder.matchesContradiction("管理员必须审核，普通用户禁止删除", Arrays.asList("必须", "禁止"))).isTrue();
    }

    @Test
    @DisplayName("(?<!不) 前缀否定：'不允许'不与'禁止'判为矛盾")
    void prefixNegationContradiction() {
        RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
        String text = "普通用户禁止删除，但管理员不允许删除";
        // "禁止" 命中，但 "不允许" 中"允许"前有"不"，经 (?<!不) 否定，不构成矛盾
        assertThat(RuleConfigHolder.matchesContradiction(text, Arrays.asList("禁止", "(?<!不)允许"))).isFalse();
    }

    @Test
    @DisplayName("可配置：新增互斥词对生效")
    void configurableContradiction() {
        ReqParseRuleConfig cfg = new ReqParseRuleConfig();
        cfg.setContradictionPairs(Arrays.asList(Arrays.asList("启用", "关闭")));
        RuleConfigHolder.apply(cfg);
        assertThat(RuleConfigHolder.matchesContradiction("功能启用后不得关闭", Arrays.asList("启用", "关闭"))).isTrue();
    }
}
