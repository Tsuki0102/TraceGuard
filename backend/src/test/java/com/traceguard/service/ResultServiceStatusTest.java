package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.Defect;
import com.traceguard.mapper.CodeDefectMapper;
import com.traceguard.mapper.CodeUnitMapper;
import com.traceguard.mapper.ConsistencyResultMapper;
import com.traceguard.mapper.DefectMapper;
import com.traceguard.mapper.FormalSpecificationMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.mapper.RequirementMapper;
import com.traceguard.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-011：ResultService 缺陷状态流转与筛选的单元测试
 */
@DisplayName("GAP-011 缺陷状态服务层测试")
@ExtendWith(MockitoExtension.class)
class ResultServiceStatusTest {

    @Mock
    private DefectMapper defectMapper;
    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private CodeUnitMapper codeUnitMapper;
    @Mock
    private CodeDefectMapper codeDefectMapper;
    @Mock
    private ConsistencyResultMapper consistencyMapper;
    @Mock
    private FormalSpecificationMapper specMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ResultService resultService;

    private Defect buildDefect(Long id, String defectId, String status) {
        Defect d = new Defect();
        d.setId(id);
        d.setDefectId(defectId);
        d.setStatus(status);
        d.setProjectId(1L);
        return d;
    }

    // ==================== updateDefectStatus ====================

    @Test
    @DisplayName("合法流转 pending → processing 成功")
    void validTransitionPendingToProcessing() {
        Defect defect = buildDefect(1L, "D-001", "pending");
        when(defectMapper.selectById(1L)).thenReturn(defect);
        when(defectMapper.updateById(any())).thenReturn(1);

        resultService.updateDefectStatus(1L, "processing", 100L);

        assertThat(defect.getStatus()).isEqualTo("processing");
        assertThat(defect.getHandlerId()).isEqualTo(100L);
        assertThat(defect.getHandleTime()).isNotNull();
    }

    @Test
    @DisplayName("合法流转 processing → resolved 成功")
    void validTransitionProcessingToResolved() {
        Defect defect = buildDefect(2L, "D-002", "processing");
        when(defectMapper.selectById(2L)).thenReturn(defect);
        when(defectMapper.updateById(any())).thenReturn(1);

        resultService.updateDefectStatus(2L, "resolved", 101L);

        assertThat(defect.getStatus()).isEqualTo("resolved");
    }

    @Test
    @DisplayName("非法流转 pending → resolved 抛出异常")
    void invalidTransitionPendingToResolved() {
        Defect defect = buildDefect(3L, "D-003", "pending");
        when(defectMapper.selectById(3L)).thenReturn(defect);

        assertThatThrownBy(() -> resultService.updateDefectStatus(3L, "resolved", 100L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("非法流转");
    }

    @Test
    @DisplayName("自流转处理中 → 处理中被拒绝")
    void selfTransitionProcessingRejected() {
        Defect defect = buildDefect(4L, "D-004", "processing");
        when(defectMapper.selectById(4L)).thenReturn(defect);

        assertThatThrownBy(() -> resultService.updateDefectStatus(4L, "processing", 100L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("非法流转");
    }

    @Test
    @DisplayName("忽略后重新处理 ignored → processing")
    void ignoredToProcessingFlow() {
        Defect defect = buildDefect(5L, "D-005", "ignored");
        when(defectMapper.selectById(5L)).thenReturn(defect);
        when(defectMapper.updateById(any())).thenReturn(1);

        resultService.updateDefectStatus(5L, "processing", 102L);

        assertThat(defect.getStatus()).isEqualTo("processing");
    }

    @Test
    @DisplayName("已解决回退 resolved → processing")
    void resolvedBackToProcessing() {
        Defect defect = buildDefect(6L, "D-006", "resolved");
        when(defectMapper.selectById(6L)).thenReturn(defect);
        when(defectMapper.updateById(any())).thenReturn(1);

        resultService.updateDefectStatus(6L, "processing", 103L);

        assertThat(defect.getStatus()).isEqualTo("processing");
    }

    @Test
    @DisplayName("不存在的缺陷 ID 抛出异常")
    void nonExistentDefectThrows() {
        when(defectMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> resultService.updateDefectStatus(999L, "processing", 100L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("缺陷不存在");
    }

    @Test
    @DisplayName("非法状态值抛出异常")
    void illegalStatusValueThrows() {
        Defect defect = buildDefect(7L, "D-007", "pending");
        when(defectMapper.selectById(7L)).thenReturn(defect);

        assertThatThrownBy(() -> resultService.updateDefectStatus(7L, "closed", 100L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("非法状态值");
    }

    @Test
    @DisplayName("null 初始状态默认为 pending 再流转")
    void nullStatusDefaultsToPending() {
        Defect defect = buildDefect(8L, "D-008", null);
        when(defectMapper.selectById(8L)).thenReturn(defect);
        when(defectMapper.updateById(any())).thenReturn(1);

        resultService.updateDefectStatus(8L, "processing", 100L);

        assertThat(defect.getStatus()).isEqualTo("processing");
    }

    // ==================== getDefects with status filter ====================

    /** 非 Spring 环境初始化 MyBatis-Plus lambda 缓存，使 LambdaQueryWrapper 的 lambda 列解析可用 */
    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusLambdaCache() {
        try {
            com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
            com.baomidou.mybatisplus.core.metadata.TableInfo tableInfo =
                    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Defect.class);
            com.baomidou.mybatisplus.core.toolkit.LambdaUtils.installCache(tableInfo);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MyBatis-Plus lambda 缓存失败", e);
        }
    }

    /** 反射读取 wrapper 的参数值映射（eq 条件绑定的参数值） */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> wrapperParams(LambdaQueryWrapper<Defect> wrapper) {
        try {
            java.lang.reflect.Field f = com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class
                    .getDeclaredField("paramNameValuePairs");
            f.setAccessible(true);
            return (java.util.Map<String, Object>) f.get(wrapper);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 wrapper 参数", e);
        }
    }

    @Test
    @DisplayName("按状态筛选：构造含 status=pending 条件的 wrapper 传给 mapper")
    void getDefectsFilteredByStatus() {
        when(defectMapper.selectList(any())).thenReturn(Collections.emptyList());

        resultService.getDefects(1L, null, null, null, null, "pending");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Defect>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(defectMapper).selectList(captor.capture());
        LambdaQueryWrapper<Defect> wrapper = captor.getValue();
        // 过滤由 DB 侧 wrapper 完成，此处断言服务构建的条件包含 status 与 pending
        assertThat(String.valueOf(wrapper.getSqlSegment())).contains("status");
        assertThat(wrapperParams(wrapper).values()).contains("pending");
    }

    @Test
    @DisplayName("空 status 参数不过滤，返回全部")
    void emptyStatusReturnsAll() {
        Defect p1 = buildDefect(20L, "D-020", "pending");
        Defect r1 = buildDefect(21L, "D-021", "resolved");

        when(defectMapper.selectList(any())).thenReturn(Arrays.asList(p1, r1));

        List<Defect> results = resultService.getDefects(1L, null, null, null, null, "");

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("组合筛选：status + level 同时写入 wrapper 条件")
    void combinedFilterStatusAndLevel() {
        when(defectMapper.selectList(any())).thenReturn(Collections.emptyList());

        resultService.getDefects(1L, null, "serious", null, null, "pending");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Defect>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(defectMapper).selectList(captor.capture());
        LambdaQueryWrapper<Defect> wrapper = captor.getValue();
        String cond = String.valueOf(wrapper.getSqlSegment());
        assertThat(cond).contains("status");
        assertThat(cond).contains("level");
        assertThat(wrapperParams(wrapper).values()).contains("pending", "serious");
    }

    // ==================== getDefectsPage with status filter ====================

    @Test
    @DisplayName("分页查询按状态筛选返回正确数据")
    void getDefectsPageFilteredByStatus() {
        Defect p1 = buildDefect(40L, "D-040", "pending");
        Defect p2 = buildDefect(41L, "D-041", "pending");
        Defect r1 = buildDefect(42L, "D-042", "resolved");

        Page<Defect> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(p1, p2, r1));
        page.setTotal(3);
        when(defectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(page);

        // TST-07：补强断言——返回分页数据并校验 status 过滤条件（替代仅验证不抛异常）
        IPage<Defect> result = resultService.getDefectsPage(1L, null, null, null, null, "pending", 1, 10);
        assertThat(result.getRecords()).hasSize(3);
        assertThat(result.getTotal()).isEqualTo(3);
        ArgumentCaptor<LambdaQueryWrapper<Defect>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(defectMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getExpression().getSqlSegment()).contains("status");
    }
}
