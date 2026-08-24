package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.common.BusinessException;
import com.traceguard.entity.ReportTemplateConfig;
import com.traceguard.mapper.ReportTemplateConfigMapper;
import com.traceguard.service.report.ReportSectionRenderer;
import com.traceguard.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GAP-010：ReportTemplateService CRUD 与校验测试
 */
@DisplayName("GAP-010 报告模板服务测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplateServiceTest {

    /** 非 Spring 环境初始化 MyBatis-Plus lambda 缓存，使 LambdaQueryWrapper 的 lambda 列解析可用（listAll 过滤断言依赖） */
    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusLambdaCache() {
        try {
            com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
            com.baomidou.mybatisplus.core.metadata.TableInfo tableInfo =
                    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ReportTemplateConfig.class);
            com.baomidou.mybatisplus.core.toolkit.LambdaUtils.installCache(tableInfo);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MyBatis-Plus lambda 缓存失败", e);
        }
    }

    @Mock
    private ReportTemplateConfigMapper templateConfigMapper;

    @InjectMocks
    private ReportTemplateService templateService;

    private ReportSectionRenderer mockRenderer1;
    private ReportSectionRenderer mockRenderer2;

    @BeforeEach
    void setUp() {
        mockRenderer1 = mock(ReportSectionRenderer.class);
        when(mockRenderer1.key()).thenReturn("project-overview");
        when(mockRenderer1.defaultTitle()).thenReturn("项目概况");

        mockRenderer2 = mock(ReportSectionRenderer.class);
        when(mockRenderer2.key()).thenReturn("stats-summary");
        when(mockRenderer2.defaultTitle()).thenReturn("需求覆盖率与缺陷统计");

        // 通过反射注入渲染器列表（sectionRenderers 为 private 字段）
        List<ReportSectionRenderer> renderers = Arrays.asList(mockRenderer1, mockRenderer2);
        org.springframework.test.util.ReflectionTestUtils.setField(templateService, "sectionRenderers", renderers);

        // 细粒度数据权限：默认以模板创建者（userId=100）上下文执行，越权场景单独覆盖
        UserContext.set(100L, "owner", "user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ReportTemplateConfig buildSystemTemplate(String code, String name, String sections) {
        ReportTemplateConfig c = new ReportTemplateConfig();
        c.setId(1L);
        c.setCode(code);
        c.setTemplateName(name);
        c.setSections(sections);
        c.setIsSystem(true);
        c.setIsDefault("FULL".equals(code));
        return c;
    }

    private ReportTemplateConfig buildUserTemplate(Long id, String name, String sections) {
        ReportTemplateConfig c = new ReportTemplateConfig();
        c.setId(id);
        c.setCode("user001");
        c.setTemplateName(name);
        c.setSections(sections);
        c.setIsSystem(false);
        c.setIsDefault(false);
        c.setUserId(100L);
        return c;
    }

    // ==================== validateSections ====================

    @Test
    @DisplayName("校验合法 sections JSON 通过")
    void validSectionsPass() {
        String json = "[{\"key\":\"project-overview\",\"title\":\"\"},{\"key\":\"stats-summary\",\"title\":\"\"}]";
        templateService.validateSections(json);
        // 无异常即通过
    }

    @Test
    @DisplayName("sections 为空抛出异常")
    void emptySectionsThrows() {
        assertThatThrownBy(() -> templateService.validateSections(""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("章节配置不能为空");
    }

    @Test
    @DisplayName("sections 为 null 抛出异常")
    void nullSectionsThrows() {
        assertThatThrownBy(() -> templateService.validateSections(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("章节配置不能为空");
    }

    @Test
    @DisplayName("sections 含非法 key 抛出异常")
    void invalidSectionKeyThrows() {
        String json = "[{\"key\":\"project-overview\",\"title\":\"\"},{\"key\":\"invalid-key\",\"title\":\"\"}]";
        assertThatThrownBy(() -> templateService.validateSections(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不合法的章节标识");
    }

    @Test
    @DisplayName("sections 空数组抛出异常")
    void emptyArraySectionsThrows() {
        assertThatThrownBy(() -> templateService.validateSections("[]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("章节配置不能为空");
    }

    // ==================== parseSectionKeys ====================

    @Test
    @DisplayName("解析 sections JSON 提取 key 列表")
    void parseSectionKeys() {
        String json = "[{\"key\":\"project-overview\",\"title\":\"概况\"},{\"key\":\"stats-summary\",\"title\":\"统计\"}]";
        List<String> keys = templateService.parseSectionKeys(json);
        assertThat(keys).containsExactly("project-overview", "stats-summary");
    }

    @Test
    @DisplayName("解析空 JSON 返回空列表")
    void parseEmptySections() {
        assertThat(templateService.parseSectionKeys("[]")).isEmpty();
        assertThat(templateService.parseSectionKeys(null)).isEmpty();
    }

    // ==================== create ====================

    @Test
    @DisplayName("创建用户模板成功")
    void createSuccess() {
        ReportTemplateConfig input = new ReportTemplateConfig();
        input.setTemplateName("自定义模板");
        input.setSections("[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectCount(any())).thenReturn(0L);
        when(templateConfigMapper.insert(any())).thenReturn(1);

        ReportTemplateConfig result = templateService.create(input);

        assertThat(result.getIsSystem()).isFalse();
        assertThat(result.getIsDefault()).isFalse();
        assertThat(result.getCode()).isNotBlank();
        verify(templateConfigMapper).insert(any());
    }

    @Test
    @DisplayName("创建模板名称为空抛出异常")
    void createEmptyNameThrows() {
        ReportTemplateConfig input = new ReportTemplateConfig();
        input.setTemplateName("");
        input.setSections("[{\"key\":\"project-overview\",\"title\":\"\"}]");

        assertThatThrownBy(() -> templateService.create(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称不能为空");
    }

    @Test
    @DisplayName("创建模板名称重复抛出异常")
    void createDuplicateNameThrows() {
        ReportTemplateConfig input = new ReportTemplateConfig();
        input.setTemplateName("完整报告");
        input.setSections("[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> templateService.create(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称已存在");
    }

    // ==================== update ====================

    @Test
    @DisplayName("更新用户模板成功")
    void updateSuccess() {
        ReportTemplateConfig existing = buildUserTemplate(10L, "旧名", "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(10L)).thenReturn(existing);
        when(templateConfigMapper.selectCount(any())).thenReturn(0L);
        when(templateConfigMapper.updateById(any())).thenReturn(1);

        ReportTemplateConfig update = new ReportTemplateConfig();
        update.setTemplateName("新名");
        update.setSections("[{\"key\":\"stats-summary\",\"title\":\"\"}]");

        ReportTemplateConfig result = templateService.update(10L, update);
        assertThat(result.getTemplateName()).isEqualTo("新名");
    }

    @Test
    @DisplayName("更新系统模板抛出异常")
    void updateSystemTemplateThrows() {
        ReportTemplateConfig sys = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(1L)).thenReturn(sys);

        ReportTemplateConfig update = new ReportTemplateConfig();
        update.setTemplateName("改名");
        update.setSections("[{\"key\":\"project-overview\",\"title\":\"\"}]");

        assertThatThrownBy(() -> templateService.update(1L, update))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统内置模板不可修改");
    }

    // ==================== delete ====================

    @Test
    @DisplayName("删除用户模板成功")
    void deleteSuccess() {
        ReportTemplateConfig user = buildUserTemplate(20L, "用户模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(20L)).thenReturn(user);
        when(templateConfigMapper.deleteById(20L)).thenReturn(1);

        templateService.delete(20L);
        verify(templateConfigMapper).deleteById(20L);
    }

    @Test
    @DisplayName("删除系统模板抛出异常")
    void deleteSystemTemplateThrows() {
        ReportTemplateConfig sys = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(1L)).thenReturn(sys);

        assertThatThrownBy(() -> templateService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统内置模板不可删除");
    }

    @Test
    @DisplayName("删除默认模板时回退 FULL 为默认")
    void deleteDefaultTemplateResetsFull() {
        ReportTemplateConfig defaultUser = buildUserTemplate(30L, "默认用户模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        defaultUser.setIsDefault(true);
        when(templateConfigMapper.selectById(30L)).thenReturn(defaultUser);
        when(templateConfigMapper.deleteById(30L)).thenReturn(1);

        ReportTemplateConfig full = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectOne(any())).thenReturn(full);
        when(templateConfigMapper.updateById(any())).thenReturn(1);

        templateService.delete(30L);

        verify(templateConfigMapper).deleteById(30L);
        verify(templateConfigMapper).updateById(any()); // FULL 设为默认
    }

    // ==================== setDefault ====================

    @Test
    @DisplayName("设为默认成功，取消旧默认")
    void setDefaultSuccess() {
        ReportTemplateConfig oldDefault = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        oldDefault.setIsDefault(true);
        oldDefault.setId(1L);

        ReportTemplateConfig target = buildUserTemplate(40L, "用户模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");

        when(templateConfigMapper.selectById(40L)).thenReturn(target);
        when(templateConfigMapper.selectList(any())).thenReturn(Collections.singletonList(oldDefault));
        when(templateConfigMapper.updateById(any())).thenReturn(1);

        templateService.setDefault(40L);

        // 旧默认取消 + 新默认设置 = 至少2次 updateById
        verify(templateConfigMapper, atLeast(2)).updateById(any());
        assertThat(target.getIsDefault()).isTrue();
    }

    // ==================== getById ====================

    @Test
    @DisplayName("查询不存在的模板抛出异常")
    void getByIdNotFoundThrows() {
        when(templateConfigMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> templateService.getById(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    // ==================== 细粒度数据权限（第 10 项） ====================

    @Test
    @DisplayName("越权：非创建者更新他人模板抛 403")
    void updateOtherUsersTemplateThrows() {
        ReportTemplateConfig other = buildUserTemplate(50L, "他人模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(50L)).thenReturn(other);

        UserContext.set(200L, "intruder", "user");
        ReportTemplateConfig update = new ReportTemplateConfig();
        update.setTemplateName("篡改");
        update.setSections("[{\"key\":\"project-overview\",\"title\":\"\"}]");

        assertThatThrownBy(() -> templateService.update(50L, update))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
    }

    @Test
    @DisplayName("越权：非创建者删除他人模板抛 403")
    void deleteOtherUsersTemplateThrows() {
        ReportTemplateConfig other = buildUserTemplate(50L, "他人模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(50L)).thenReturn(other);

        UserContext.set(200L, "intruder", "user");
        assertThatThrownBy(() -> templateService.delete(50L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
        verify(templateConfigMapper, never()).deleteById(50L);
    }

    @Test
    @DisplayName("越权：非创建者将他人模板设为默认抛 403")
    void setDefaultOtherUsersTemplateThrows() {
        ReportTemplateConfig other = buildUserTemplate(50L, "他人模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(50L)).thenReturn(other);

        UserContext.set(200L, "intruder", "user");
        assertThatThrownBy(() -> templateService.setDefault(50L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
    }

    @Test
    @DisplayName("越权：普通用户通过模板ID访问他人模板抛 403")
    void checkTemplateAccessOtherThrows() {
        ReportTemplateConfig other = buildUserTemplate(50L, "他人模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(50L)).thenReturn(other);

        UserContext.set(200L, "intruder", "user");
        assertThatThrownBy(() -> templateService.checkTemplateAccess(50L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
    }

    @Test
    @DisplayName("模板创建者可访问自己的模板")
    void checkTemplateAccessOwnerPasses() {
        ReportTemplateConfig own = buildUserTemplate(60L, "我的模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(60L)).thenReturn(own);

        templateService.checkTemplateAccess(60L); // 无异常即通过
    }

    @Test
    @DisplayName("普通用户可访问系统模板")
    void checkTemplateAccessSystemPasses() {
        ReportTemplateConfig sys = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        sys.setUserId(null);
        when(templateConfigMapper.selectById(1L)).thenReturn(sys);

        templateService.checkTemplateAccess(1L); // 无异常即通过
    }

    @Test
    @DisplayName("管理员可访问任意用户模板")
    void checkTemplateAccessAdminPasses() {
        ReportTemplateConfig other = buildUserTemplate(50L, "他人模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectById(50L)).thenReturn(other);

        UserContext.set(1L, "admin", "admin");
        templateService.checkTemplateAccess(50L); // 无异常即通过
    }

    @Test
    @DisplayName("普通用户 listAll 过滤为系统模板+自己的模板")
    void listAllForNormalUserFiltersOthers() {
        templateService.listAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReportTemplateConfig>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(templateConfigMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql).containsIgnoringCase("user_id IS NULL");
        assertThat(sql).contains("user_id = #{ew.paramNameValuePairs.MPGENVAL1}");
        // 占位符绑定值为当前用户 ID（100）
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue(100L);
    }

    @Test
    @DisplayName("管理员 listAll 不加归属过滤条件")
    void listAllForAdminNoFilter() {
        UserContext.set(1L, "admin", "admin");
        templateService.listAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReportTemplateConfig>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(templateConfigMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql).doesNotContain("user_id");
    }

    // ==================== 导出默认模板可见性回退（第 10 项） ====================

    @Test
    @DisplayName("导出回退：默认模板为他人私有模板时普通用户回退系统 FULL")
    void resolveDefaultForExportFallbackToFullForOtherUsersDefault() {
        ReportTemplateConfig otherDefault = buildUserTemplate(70L, "他人默认",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        otherDefault.setIsDefault(true);
        ReportTemplateConfig full = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectOne(any())).thenReturn(otherDefault, full);

        UserContext.set(200L, "intruder", "user");
        ReportTemplateConfig result = templateService.resolveDefaultForExport();
        assertThat(result).isEqualTo(full);
    }

    @Test
    @DisplayName("导出：默认模板为自己创建时直接使用")
    void resolveDefaultForExportOwnDefaultPasses() {
        ReportTemplateConfig own = buildUserTemplate(60L, "我的模板",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        own.setIsDefault(true);
        when(templateConfigMapper.selectOne(any())).thenReturn(own);

        ReportTemplateConfig result = templateService.resolveDefaultForExport();
        assertThat(result).isEqualTo(own);
    }

    @Test
    @DisplayName("导出：默认模板为系统模板时直接使用")
    void resolveDefaultForExportSystemDefaultPasses() {
        ReportTemplateConfig sys = buildSystemTemplate("FULL", "完整报告",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        when(templateConfigMapper.selectOne(any())).thenReturn(sys);

        ReportTemplateConfig result = templateService.resolveDefaultForExport();
        assertThat(result).isEqualTo(sys);
    }

    @Test
    @DisplayName("导出：管理员可使用任意默认模板")
    void resolveDefaultForExportAdminGetsAnyDefault() {
        ReportTemplateConfig otherDefault = buildUserTemplate(70L, "他人默认",
                "[{\"key\":\"project-overview\",\"title\":\"\"}]");
        otherDefault.setIsDefault(true);
        when(templateConfigMapper.selectOne(any())).thenReturn(otherDefault);

        UserContext.set(1L, "admin", "admin");
        ReportTemplateConfig result = templateService.resolveDefaultForExport();
        assertThat(result).isEqualTo(otherDefault);
    }
}
