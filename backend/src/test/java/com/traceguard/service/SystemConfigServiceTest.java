package com.traceguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.CodeParseScopeHolder;
import com.traceguard.config.ReqParseRuleConfig;
import com.traceguard.config.RuleConfigHolder;
import com.traceguard.entity.SystemConfig;
import com.traceguard.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SystemConfigService 单元测试（TST-03 补齐）：
 * 覆盖启动加载/热更新（需求解析规则 + 代码解析范围）、配置 CRUD、JSON 解析失败回退。
 * 纯 Mockito，不依赖 Spring 上下文（静态持有者 RuleConfigHolder/CodeParseScopeHolder 可直读断言）。
 */
@DisplayName("系统配置服务单元测试")
class SystemConfigServiceTest {

    private SystemConfigService systemConfigService;
    private SystemConfigMapper systemConfigMapper;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        systemConfigService = new SystemConfigService();
        systemConfigMapper = Mockito.mock(SystemConfigMapper.class);
        ReflectionTestUtils.setField(systemConfigService, "systemConfigMapper", systemConfigMapper);
    }

    // ---- 启动加载 ----

    @Test
    @DisplayName("loadOnStartup 无规则配置时回退内置默认")
    void loadOnStartupNoConfigFallsBackToDefault() {
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(null);

        systemConfigService.loadOnStartup();

        assertThat(RuleConfigHolder.get().getAmbiguityKeywords()).contains("等");
        assertThat(CodeParseScopeHolder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("loadOnStartup 规则 JSON 解析成功并应用")
    void loadOnStartupAppliesParsedRules() throws Exception {
        ReqParseRuleConfig cfg = new ReqParseRuleConfig();
        cfg.setAmbiguityKeywords(Arrays.asList("自定义词"));
        cfg.setContradictionPairs(Arrays.asList(Arrays.asList("必须", "禁止")));
        SystemConfig sc = new SystemConfig();
        sc.setConfigKey(SystemConfigService.REQ_PARSE_RULES_KEY);
        sc.setConfigValue(MAPPER.writeValueAsString(cfg));
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(sc);
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(null);

        systemConfigService.loadOnStartup();

        assertThat(RuleConfigHolder.get().getAmbiguityKeywords()).containsExactly("自定义词");
    }

    @Test
    @DisplayName("loadOnStartup 规则 JSON 非法时回退默认")
    void loadOnStartupInvalidJsonFallsBack() {
        SystemConfig sc = new SystemConfig();
        sc.setConfigKey(SystemConfigService.REQ_PARSE_RULES_KEY);
        sc.setConfigValue("{not valid json");
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(sc);
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(null);

        systemConfigService.loadOnStartup();

        assertThat(RuleConfigHolder.get().getAmbiguityKeywords()).contains("等");
    }

    @Test
    @DisplayName("loadOnStartup 代码解析范围配置解析成功并应用")
    void loadOnStartupAppliesCodeParseScope() {
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);
        // 手写 JSON：CodeParseScope 含 isEmpty() 会被 Jackson 序列化为 "empty" 字段导致反序列化失败，
        // 故不通过对象序列化构造，直接给出仅含目标字段的 JSON（缺失字段走默认值）
        SystemConfig sc = new SystemConfig();
        sc.setConfigKey(SystemConfigService.CODE_PARSE_SCOPE_KEY);
        sc.setConfigValue("{\"includePackages\":[\"com.example.*\"]}");
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(sc);

        systemConfigService.loadOnStartup();

        assertThat(CodeParseScopeHolder.get().getIncludePackages()).containsExactly("com.example.*");
    }

    @Test
    @DisplayName("loadOnStartup 代码解析范围 JSON 非法时重置为不过滤")
    void loadOnStartupInvalidScopeFallsBack() {
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);
        SystemConfig sc = new SystemConfig();
        sc.setConfigKey(SystemConfigService.CODE_PARSE_SCOPE_KEY);
        sc.setConfigValue("{bad");
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(sc);

        systemConfigService.loadOnStartup();

        assertThat(CodeParseScopeHolder.get().isEmpty()).isTrue();
    }

    // ---- 查询 ----

    @Test
    @DisplayName("getByKey 委托 mapper 按主键查询")
    void getByKeyDelegates() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey("k");
        when(systemConfigMapper.selectById("k")).thenReturn(cfg);

        assertThat(systemConfigService.getByKey("k")).isSameAs(cfg);
    }

    @Test
    @DisplayName("listAll 返回全部配置")
    void listAllDelegates() {
        List<SystemConfig> list = Arrays.asList(new SystemConfig());
        when(systemConfigMapper.selectList(null)).thenReturn(list);

        assertThat(systemConfigService.listAll()).isSameAs(list);
    }

    // ---- 保存/热更新 ----

    @Test
    @DisplayName("save 新配置走 insert 并回填更新人")
    void saveNewConfigInserts() {
        when(systemConfigMapper.selectById("k")).thenReturn(null);

        SystemConfig result = systemConfigService.save("k", "v", "desc", "admin");

        verify(systemConfigMapper).insert(any(SystemConfig.class));
        assertThat(result.getConfigValue()).isEqualTo("v");
        assertThat(result.getDescription()).isEqualTo("desc");
        assertThat(result.getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("save 已存在配置走 updateById")
    void saveExistingConfigUpdates() {
        SystemConfig existing = new SystemConfig();
        existing.setConfigKey("k");
        when(systemConfigMapper.selectById("k")).thenReturn(existing);

        systemConfigService.save("k", "v2", "desc2", "admin");

        verify(systemConfigMapper).updateById(existing);
        assertThat(existing.getConfigValue()).isEqualTo("v2");
    }

    @Test
    @DisplayName("save req_parse_rules 非法 JSON 抛出业务错误")
    void saveInvalidReqParseRulesRejected() {
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);

        assertThatThrownBy(() -> systemConfigService.save(
                SystemConfigService.REQ_PARSE_RULES_KEY, "{bad", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需求解析规则 JSON 解析失败");
    }

    @Test
    @DisplayName("save code_parse_scope 非法 JSON 抛出业务错误")
    void saveInvalidCodeParseScopeRejected() {
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> systemConfigService.save(
                SystemConfigService.CODE_PARSE_SCOPE_KEY, "{bad", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("代码解析范围 JSON 解析失败");
    }

    @Test
    @DisplayName("save 需求解析规则保存后热更新到运行期持有者")
    void saveReqParseRulesHotReloads() throws Exception {
        ReqParseRuleConfig cfg = new ReqParseRuleConfig();
        cfg.setAmbiguityKeywords(Arrays.asList("热更新词"));
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);

        systemConfigService.save(SystemConfigService.REQ_PARSE_RULES_KEY,
                MAPPER.writeValueAsString(cfg), null, "admin");

        assertThat(RuleConfigHolder.get().getAmbiguityKeywords()).containsExactly("热更新词");
    }

    // ---- 读取解析 ----

    @Test
    @DisplayName("getCodeParseScope 无配置时返回空作用域")
    void getCodeParseScopeEmptyReturnsEmptyScope() {
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(null);

        assertThat(systemConfigService.getCodeParseScope().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getCodeParseScope 解析失败时返回空作用域")
    void getCodeParseScopeInvalidReturnsEmptyScope() {
        SystemConfig sc = new SystemConfig();
        sc.setConfigValue("{bad");
        when(systemConfigMapper.selectById(SystemConfigService.CODE_PARSE_SCOPE_KEY)).thenReturn(sc);

        assertThat(systemConfigService.getCodeParseScope().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getReqParseRules 无配置时返回默认规则")
    void getReqParseRulesEmptyReturnsDefault() {
        when(systemConfigMapper.selectById(SystemConfigService.REQ_PARSE_RULES_KEY)).thenReturn(null);

        assertThat(systemConfigService.getReqParseRules().getAmbiguityKeywords()).contains("等");
    }
}
