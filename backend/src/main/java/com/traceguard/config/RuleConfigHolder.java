package com.traceguard.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 需求解析规则运行期持有者（AUD-07，FR-REQ-002）。
 * RequirementAnalyzerUtil 为分析流程内 new 出来的工具类、非 Spring Bean，故以静态持有者承载
 * 可配置规则；SystemConfigService 在启动与配置变更时调用 apply() 刷新。未配置时使用内置默认值。
 */
public final class RuleConfigHolder {

    private static volatile ReqParseRuleConfig current = defaultConfig();

    private RuleConfigHolder() {
    }

    public static ReqParseRuleConfig defaultConfig() {
        return new ReqParseRuleConfig();
    }

    public static void apply(ReqParseRuleConfig config) {
        current = (config != null) ? config : defaultConfig();
    }

    public static ReqParseRuleConfig get() {
        return current;
    }

    public static List<String> ambiguityKeywords() {
        return current.getAmbiguityKeywords();
    }

    public static List<List<String>> contradictionPairs() {
        return current.getContradictionPairs();
    }

    /** 歧义词命中判断：包含该词（"等" 同时覆盖 "等等"）即命中 */
    public static boolean containsAmbiguity(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        if ("等".equals(keyword)) {
            return text.contains("等");
        }
        return text.contains(keyword);
    }

    /** 互斥词对命中判断：词A 原文命中且词B（支持 (?<!不) 前缀否定）正则命中 */
    public static boolean matchesContradiction(String text, List<String> pair) {
        if (text == null || pair == null || pair.size() < 2) {
            return false;
        }
        String a = pair.get(0);
        String b = pair.get(1);
        if (!text.contains(a)) {
            return false;
        }
        // 词B 可能带正则前缀（如 (?<!不)允许）；普通词直接 contains
        String bRegex = b.replace("(?<!不)", "");
        boolean bMatched;
        if (bRegex.equals(b)) {
            bMatched = text.contains(b);
        } else {
            bMatched = java.util.regex.Pattern.compile(b).matcher(text).find();
        }
        return bMatched;
    }

    public static List<String> safeContradictionPair(List<String> pair) {
        return pair == null ? Collections.emptyList() : new ArrayList<>(pair);
    }
}
