package com.traceguard.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP-030：StartupDbCheck 启动数据库自检测试
 * 验证点（设计 4.2.11）：
 * 1. 表/列完整 -> 通过（dev/prod 均不抛异常）
 * 2. 缺表 + 非 prod（warn 模式） -> 仅告警不抛异常
 * 3. 缺表 + prod（reject 模式） -> 拒绝启动，异常含"数据库结构检查失败"与缺失表名
 */
@DisplayName("GAP-030 StartupDbCheck 启动自检")
class StartupDbCheckTest {

    private JdbcTemplate jdbcTemplate;
    private Environment environment;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        environment = mock(Environment.class);
    }

    private StartupDbCheck buildCheck(String[] profiles, String mode) {
        StartupDbCheck check = new StartupDbCheck();
        ReflectionTestUtils.setField(check, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(check, "environment", environment);
        ReflectionTestUtils.setField(check, "checkMode", mode);
        ReflectionTestUtils.setField(check, "timeoutMs", 3000);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return check;
    }

    @Test
    @DisplayName("表/列完整：dev 与 prod 均通过不抛异常")
    void completeSchemaPasses() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);

        StartupDbCheck devCheck = buildCheck(new String[]{"dev"}, "warn");
        assertThatCode(() -> devCheck.run(null)).doesNotThrowAnyException();

        StartupDbCheck prodCheck = buildCheck(new String[]{"prod"}, "warn");
        assertThatCode(() -> prodCheck.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺表 + 非 prod（warn 模式）：仅告警不抛异常")
    void missingTableWarnsInDev() {
        stubMissingTable("tg_llm_config");

        StartupDbCheck check = buildCheck(new String[]{"dev"}, "warn");
        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺表 + prod（reject 模式）：拒绝启动并提示缺失表")
    void missingTableRejectsInProd() {
        stubMissingTable("tg_llm_config");

        StartupDbCheck check = buildCheck(new String[]{"prod"}, "warn");
        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("数据库结构检查失败")
                .hasMessageContaining("tg_llm_config")
                .hasMessageContaining("init.sql");
    }

    @Test
    @DisplayName("缺列 + prod（reject 模式）：拒绝启动并提示缺失列")
    void missingColumnRejectsInProd() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenAnswer(inv -> {
            Object[] args = inv.getArgument(2);
            String name = (String) args[0];
            return name.equals("tg_defect.status") ? 0 : 1;
        });

        StartupDbCheck check = buildCheck(new String[]{"prod"}, "warn");
        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("数据库结构检查失败")
                .hasMessageContaining("tg_defect.status");
    }

    private void stubMissingTable(String missingTable) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenAnswer(inv -> {
            Object[] args = inv.getArgument(2);
            String name = (String) args[0];
            return name.equals(missingTable) ? 0 : 1;
        });
    }
}
