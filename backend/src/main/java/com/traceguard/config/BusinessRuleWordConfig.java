package com.traceguard.config;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.traceguard.util.CodeDefectPatternDetector;
import com.traceguard.util.CodeDefectPatternDetector.BusinessRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * FUN-09：缺陷规则业务词表外置配置。
 *
 * 从 {@code traceguard.analysis.business-rule-words} 读取可覆盖的隐含业务规则（JSON），
 * 在启动时注入 {@link CodeDefectPatternDetector}，使"库存/退款/幂等"等由领域示例驱动的
 * 缺陷规则可配置化（区分通用规则与领域示例，避免规则与评测集过拟合）。
 *
 * 配置格式（JSON 数组）：
 * [{"reqKeywords":"库存,充足","evidence":["reserved","freeze","预留"],"risk":0.30}]
 * 留空/未配置时使用 CodeDefectPatternDetector 内置默认规则。
 */
@Slf4j
@Component
public class BusinessRuleWordConfig {

    @Value("${traceguard.analysis.business-rule-words:}")
    private String businessRuleWords;

    @PostConstruct
    public void init() {
        if (businessRuleWords == null || businessRuleWords.trim().isEmpty()) {
            log.debug("[FUN-09] 未配置 business-rule-words，使用内置默认业务规则");
            return;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(businessRuleWords);
            List<BusinessRule> rules = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                cn.hutool.json.JSONObject o = arr.getJSONObject(i);
                BusinessRule r = new BusinessRule();
                r.reqKeywords = o.getStr("reqKeywords", "");
                cn.hutool.json.JSONArray ev = o.getJSONArray("evidence");
                if (ev != null) {
                    r.codeEvidence = ev.toArray(new String[0]);
                }
                r.risk = o.getDouble("risk", 0.25);
                if (!r.reqKeywords.isEmpty()) {
                    rules.add(r);
                }
            }
            CodeDefectPatternDetector.configure(rules);
            log.info("[FUN-09] 已加载 {} 条可配置业务规则（business-rule-words）", rules.size());
        } catch (Exception e) {
            log.warn("[FUN-09] business-rule-words 解析失败，保留内置默认业务规则: {}", e.getMessage());
        }
    }
}
