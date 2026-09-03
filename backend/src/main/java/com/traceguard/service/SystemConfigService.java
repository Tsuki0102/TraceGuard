package com.traceguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.CodeParseScope;
import com.traceguard.config.CodeParseScopeHolder;
import com.traceguard.config.ReqParseRuleConfig;
import com.traceguard.config.RuleConfigHolder;
import com.traceguard.entity.SystemConfig;
import com.traceguard.mapper.SystemConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统配置服务（AUD-07，GAP-002）。
 * - 需求解析规则（config_key=req_parse_rules）为 JSON，存入 sys_config；
 * - 启动加载后应用到 RuleConfigHolder，使 RequirementAnalyzerUtil 在运行时使用最新规则；
 * - 管理员可在后台可视化调整并保存，保存即热更新。
 */
@Service
@Slf4j
public class SystemConfigService {

    public static final String REQ_PARSE_RULES_KEY = "req_parse_rules";

    /** FR-CODE-001 规则3/4（2.4 整改项）：代码解析范围配置 KEY */
    public static final String CODE_PARSE_SCOPE_KEY = "code_parse_scope";

    /** P1-4：规则缺陷信号权重配置 KEY（JSON：{信号名: 权重}，全量信号，热生效） */
    public static final String RISK_WEIGHTS_KEY = "risk_weights";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    /** 启动加载：读取 req_parse_rules / code_parse_scope / risk_weights 并应用到对应运行期持有者（无记录则用默认值） */
    public void loadOnStartup() {
        loadRiskWeightsOnStartup();
        SystemConfig cfg = systemConfigMapper.selectById(REQ_PARSE_RULES_KEY);
        if (cfg == null || cfg.getConfigValue() == null || cfg.getConfigValue().isEmpty()) {
            RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
            log.info("[SystemConfig] 未配置 {}，使用内置默认需求解析规则", REQ_PARSE_RULES_KEY);
        } else {
            try {
                ReqParseRuleConfig parsed = MAPPER.readValue(cfg.getConfigValue(), ReqParseRuleConfig.class);
                RuleConfigHolder.apply(parsed);
                log.info("[SystemConfig] 已加载需求解析规则：歧义词 {} 个，互斥词对 {} 个",
                        parsed.getAmbiguityKeywords().size(), parsed.getContradictionPairs().size());
            } catch (Exception e) {
                RuleConfigHolder.apply(RuleConfigHolder.defaultConfig());
                log.warn("[SystemConfig] 需求解析规则解析失败，回退默认：{}", e.getMessage());
            }
        }
        // FR-CODE-001 规则3/4（2.4 整改项）：代码解析范围
        SystemConfig scopeCfg = systemConfigMapper.selectById(CODE_PARSE_SCOPE_KEY);
        if (scopeCfg == null || scopeCfg.getConfigValue() == null || scopeCfg.getConfigValue().isEmpty()) {
            CodeParseScopeHolder.reset();
            log.info("[SystemConfig] 未配置 {}，代码解析范围不限（全量解析）", CODE_PARSE_SCOPE_KEY);
            return;
        }
        try {
            CodeParseScope scope = MAPPER.readValue(scopeCfg.getConfigValue(), CodeParseScope.class);
            CodeParseScopeHolder.apply(scope);
            log.info("[SystemConfig] 已加载代码解析范围：includePackages={}, excludePackages={}, includeClasses={}, excludeClasses={}, includeMethods={}, excludeMethods={}",
                    scope.getIncludePackages(), scope.getExcludePackages(), scope.getIncludeClasses(),
                    scope.getExcludeClasses(), scope.getIncludeMethods(), scope.getExcludeMethods());
        } catch (Exception e) {
            CodeParseScopeHolder.reset();
            log.warn("[SystemConfig] 代码解析范围解析失败，回退不限：{}", e.getMessage());
        }
    }

    /** 读取某配置原始 JSON */
    public SystemConfig getByKey(String key) {
        return systemConfigMapper.selectById(key);
    }

    /** 读取全部配置（管理后台展示） */
    public List<SystemConfig> listAll() {
        return systemConfigMapper.selectList(null);
    }

    /** 保存/更新配置（JSON 文本），并热更新到运行期持有者 */
    @Transactional
    public SystemConfig save(String key, String value, String description, String operator) {
        SystemConfig cfg = systemConfigMapper.selectById(key);
        if (cfg == null) {
            cfg = new SystemConfig();
            cfg.setConfigKey(key);
        }
        cfg.setConfigValue(value);
        if (description != null) {
            cfg.setDescription(description);
        }
        cfg.setUpdatedBy(operator);
        cfg.setUpdatedAt(LocalDateTime.now());
        if (cfg.getConfigKey() != null && systemConfigMapper.selectById(key) != null) {
            systemConfigMapper.updateById(cfg);
        } else {
            systemConfigMapper.insert(cfg);
        }

        if (REQ_PARSE_RULES_KEY.equals(key)) {
            try {
                RuleConfigHolder.apply(MAPPER.readValue(value, ReqParseRuleConfig.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("需求解析规则 JSON 解析失败：" + e.getMessage());
            }
        } else if (CODE_PARSE_SCOPE_KEY.equals(key)) {
            try {
                CodeParseScopeHolder.apply(MAPPER.readValue(value, CodeParseScope.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("代码解析范围 JSON 解析失败：" + e.getMessage());
            }
        } else if (RISK_WEIGHTS_KEY.equals(key)) {
            applyRiskWeightsValue(value);
        }
        return cfg;
    }

    /** P1-4：解析并应用规则信号权重 JSON（{信号名: 权重}）；非法 JSON / 负权重直接抛业务异常 */
    private void applyRiskWeightsValue(String value) {
        try {
            Map<String, Double> parsed = MAPPER.readValue(value,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
            if (parsed == null) {
                throw new IllegalArgumentException("规则信号权重 JSON 不能为空");
            }
            for (Map.Entry<String, Double> e : parsed.entrySet()) {
                if (e.getValue() == null || e.getValue() < 0) {
                    throw new IllegalArgumentException("信号 " + e.getKey() + " 权重必须 >= 0");
                }
            }
            // 未配置信号按 weightOf 默认 1.0 计；缺失的既有噪声信号默认 0 需显式写入，故保存时前端应提交全量
            com.traceguard.util.CodeDefectPatternDetector.configureRiskWeights(parsed);
            log.info("[SystemConfig] 已应用规则信号权重：{} 个信号", parsed.size());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("规则信号权重 JSON 解析失败：" + e.getMessage());
        }
    }

    /** P1-4：启动加载规则信号权重（无配置则落库当前基线并应用默认） */
    private void loadRiskWeightsOnStartup() {
        SystemConfig cfg = systemConfigMapper.selectById(RISK_WEIGHTS_KEY);
        if (cfg == null || cfg.getConfigValue() == null || cfg.getConfigValue().isEmpty()) {
            com.traceguard.util.CodeDefectPatternDetector.configureRiskWeights(
                    com.traceguard.util.CodeDefectPatternDetector.baselineRiskWeights());
            log.info("[SystemConfig] 未配置 {}，使用内置规则信号权重（P1-4 去噪 + 温和升权基线）", RISK_WEIGHTS_KEY);
            return;
        }
        try {
            applyRiskWeightsValue(cfg.getConfigValue());
        } catch (Exception e) {
            com.traceguard.util.CodeDefectPatternDetector.configureRiskWeights(
                    com.traceguard.util.CodeDefectPatternDetector.baselineRiskWeights());
            log.warn("[SystemConfig] 规则信号权重配置解析失败，回退内置默认：{}", e.getMessage());
        }
    }

    /** P1-4：读取当前生效的规则信号权重（全量信号 -> 权重），供 ThresholdLab 权重调参台 */
    public Map<String, Double> getRiskWeights() {
        return com.traceguard.util.CodeDefectPatternDetector.riskSignalWeights();
    }

    /** 读取并解析代码解析范围（FR-CODE-001 2.4，供前端编辑/校验） */
    public CodeParseScope getCodeParseScope() {
        SystemConfig cfg = systemConfigMapper.selectById(CODE_PARSE_SCOPE_KEY);
        if (cfg == null || cfg.getConfigValue() == null || cfg.getConfigValue().isEmpty()) {
            return new CodeParseScope();
        }
        try {
            return MAPPER.readValue(cfg.getConfigValue(), CodeParseScope.class);
        } catch (Exception e) {
            return new CodeParseScope();
        }
    }

    /** 读取并解析需求解析规则（供校验） */
    public ReqParseRuleConfig getReqParseRules() {
        SystemConfig cfg = systemConfigMapper.selectById(REQ_PARSE_RULES_KEY);
        if (cfg == null || cfg.getConfigValue() == null || cfg.getConfigValue().isEmpty()) {
            return RuleConfigHolder.defaultConfig();
        }
        try {
            return MAPPER.readValue(cfg.getConfigValue(), ReqParseRuleConfig.class);
        } catch (Exception e) {
            return RuleConfigHolder.defaultConfig();
        }
    }
}
