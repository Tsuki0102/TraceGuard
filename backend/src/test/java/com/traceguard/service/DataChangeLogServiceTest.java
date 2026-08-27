package com.traceguard.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.ISqlSegment;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.DataChangeLog;
import com.traceguard.mapper.DataChangeLogMapper;
import com.traceguard.util.UserContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataChangeLogService 单元测试（TST-03 补齐，FR-PLAT-004 字段级变更追溯）：
 * 覆盖字段级 diff 记录、创建/删除快照、长值截断、按实体/项目分页查询、操作人注入。
 * 纯 Mockito，不依赖 Spring 上下文。
 */
@DisplayName("数据变更日志服务单元测试")
class DataChangeLogServiceTest {

    private DataChangeLogService dataChangeLogService;
    private DataChangeLogMapper mapper;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus lambda cache（TableInfo），使 LambdaQueryWrapper.getSqlSegment() 在纯 JVM 下可用
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataChangeLog.class);
        dataChangeLogService = new DataChangeLogService();
        mapper = Mockito.mock(DataChangeLogMapper.class);
        ReflectionTestUtils.setField(dataChangeLogService, "mapper", mapper);
        UserContext.set(7L, "bob", "user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("recordFieldChanges 仅记录发生变化的字段并注入操作人")
    void recordFieldChangesWritesOnlyChangedFields() {
        Map<String, String> oldMap = Map.of("status", "created", "name", "a");
        Map<String, String> newMap = Map.of("status", "archived", "name", "a");

        dataChangeLogService.recordFieldChanges("project", "1", 10L, oldMap, newMap, List.of("status", "name"));

        ArgumentCaptor<DataChangeLog> captor = ArgumentCaptor.forClass(DataChangeLog.class);
        verify(mapper, Mockito.times(1)).insert(captor.capture());
        DataChangeLog log = captor.getValue();
        assertThat(log.getFieldName()).isEqualTo("status");
        assertThat(log.getOldValue()).isEqualTo("created");
        assertThat(log.getNewValue()).isEqualTo("archived");
        assertThat(log.getEntityType()).isEqualTo("project");
        assertThat(log.getEntityId()).isEqualTo("1");
        assertThat(log.getProjectId()).isEqualTo(10L);
        assertThat(log.getOperation()).isEqualTo("update");
        assertThat(log.getOperatorId()).isEqualTo(7L);
        assertThat(log.getOperatorName()).isEqualTo("bob");
    }

    @Test
    @DisplayName("recordFieldChanges 无字段变化时不写任何日志")
    void recordFieldChangesNoDiffWritesNothing() {
        Map<String, String> oldMap = Map.of("name", "a");
        Map<String, String> newMap = Map.of("name", "a");

        dataChangeLogService.recordFieldChanges("project", "1", 1L, oldMap, newMap, List.of("name"));

        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("recordFieldChanges 超长字段值截断到 500 字符")
    void recordFieldChangesTruncatesLongValues() {
        String longVal = "x".repeat(600);
        Map<String, String> oldMap = Map.of("desc", longVal);
        Map<String, String> newMap = Map.of("desc", "y");

        dataChangeLogService.recordFieldChanges("project", "1", 1L, oldMap, newMap, List.of("desc"));

        ArgumentCaptor<DataChangeLog> captor = ArgumentCaptor.forClass(DataChangeLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOldValue()).hasSize(500);
    }

    @Test
    @DisplayName("recordCreate 记录整体快照")
    void recordCreateWritesSnapshot() {
        dataChangeLogService.recordCreate("project", "9", 3L, Map.of("name", "demo"));

        ArgumentCaptor<DataChangeLog> captor = ArgumentCaptor.forClass(DataChangeLog.class);
        verify(mapper).insert(captor.capture());
        DataChangeLog log = captor.getValue();
        assertThat(log.getOperation()).isEqualTo("create");
        assertThat(log.getFieldName()).isEqualTo("*");
        assertThat(log.getOldValue()).isNull();
        assertThat(log.getNewValue()).contains("name=demo");
        assertThat(log.getOperatorId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("recordDelete 记录删除动作")
    void recordDeleteWritesDeleteLog() {
        dataChangeLogService.recordDelete("user", "2", null);

        ArgumentCaptor<DataChangeLog> captor = ArgumentCaptor.forClass(DataChangeLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo("delete");
        assertThat(captor.getValue().getNewValue()).isNull();
    }

    @Test
    @DisplayName("pageByEntity 按实体过滤分页查询")
    void pageByEntityDelegates() {
        Page<DataChangeLog> expected = new Page<>(1, 10);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(expected);

        Page<DataChangeLog> result = dataChangeLogService.pageByEntity("project", "1", 1, 10);

        assertThat(result).isSameAs(expected);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DataChangeLog>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        // 纯 JVM 下 getSqlSegment() 依赖 MyBatis-Plus lambda cache，改用 expression 列名断言过滤条件
        List<ISqlSegment> normal = captor.getValue().getExpression().getNormal();
        assertThat(normal).anyMatch(s -> s.getSqlSegment().contains("entity_type"));
        assertThat(normal).anyMatch(s -> s.getSqlSegment().contains("entity_id"));
    }

    @Test
    @DisplayName("pageByProject 按项目过滤分页查询")
    void pageByProjectDelegates() {
        Page<DataChangeLog> expected = new Page<>(1, 10);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(expected);

        Page<DataChangeLog> result = dataChangeLogService.pageByProject(5L, 1, 10);

        assertThat(result).isSameAs(expected);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DataChangeLog>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getExpression().getNormal())
                .anyMatch(s -> s.getSqlSegment().contains("project_id"));
    }
}
