package com.traceguard.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.common.BusinessException;
import com.traceguard.entity.Project;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.util.FileStorageUtil;
import com.traceguard.util.UserContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProjectService 单元测试（TST-03 补齐）：
 * 覆盖项目 CRUD、数据隔离/回收站归属校验、存储配额、归档恢复、统计回写、定时清理。
 * 纯 Mockito + 临时目录，不依赖 Spring 上下文。
 */
@DisplayName("项目服务单元测试")
class ProjectServiceTest {

    private ProjectService projectService;
    private ProjectMapper projectMapper;
    private FileStorageUtil fileStorageUtil;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus lambda cache（TableInfo），使 LambdaQueryWrapper.getSqlSegment() 在纯 JVM 下可用
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Project.class);
        projectService = new ProjectService();
        projectMapper = Mockito.mock(ProjectMapper.class);
        fileStorageUtil = Mockito.mock(FileStorageUtil.class);
        ReflectionTestUtils.setField(projectService, "projectMapper", projectMapper);
        ReflectionTestUtils.setField(projectService, "fileStorageUtil", fileStorageUtil);
        ReflectionTestUtils.setField(projectService, "recycleRetentionDays", 30);
        UserContext.set(1L, "alice", "user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Project buildProject(Long id, Long ownerId) {
        Project p = new Project();
        p.setId(id);
        p.setProjectName("demo");
        p.setCreateUserId(ownerId);
        p.setStatus("created");
        return p;
    }

    // ---- 分页/列表 ----

    @Test
    @DisplayName("pageList 传入 userId 时附加归属过滤")
    void pageListAddsOwnerFilterWhenUserIdPresent() {
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(new Page<>());

        projectService.pageList(1, 10, 5L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Project>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), captor.capture());
        // 纯 JVM 下 getSqlSegment() 依赖 MyBatis-Plus lambda cache，改用 expression 列名断言过滤条件
        assertThat(captor.getValue().getExpression().getNormal())
                .anyMatch(s -> s.getSqlSegment().contains("create_user_id"));
    }

    @Test
    @DisplayName("pageList 不传 userId 时不做归属过滤（管理员视角）")
    void pageListSkipsOwnerFilterWhenUserIdNull() {
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(new Page<>());

        projectService.pageList(1, 10, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Project>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getExpression().getNormal())
                .noneMatch(s -> s.getSqlSegment().contains("create_user_id"));
    }

    @Test
    @DisplayName("pageList 返回 mapper 分页结果")
    void pageListDelegates() {
        Page<Project> expected = new Page<>(1, 10);
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(expected);

        Page<Project> result = projectService.pageList(1, 10, null);

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("list 传入 userId 时附加归属过滤")
    void listFiltersByUser() {
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        projectService.list(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Project>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMapper).selectList(captor.capture());
        assertThat(captor.getValue().getExpression().getNormal())
                .anyMatch(s -> s.getSqlSegment().contains("create_user_id"));
    }

    @Test
    @DisplayName("getById 委托 mapper")
    void getByIdDelegates() {
        Project p = buildProject(42L, 1L);
        when(projectMapper.selectById(42L)).thenReturn(p);
        assertThat(projectService.getById(42L)).isSameAs(p);
    }

    // ---- 创建与配额 ----

    @Test
    @DisplayName("create 项目总数达上限时拒绝")
    void createRejectsWhenAtLimit() {
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(100L);

        assertThatThrownBy(() -> projectService.create(buildProject(null, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
        verify(projectMapper, never()).insert(any());
    }

    @Test
    @DisplayName("create 初始化默认状态/统计/创建人并落库")
    void createInitializesDefaults() {
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        Project p = buildProject(null, null);
        projectService.create(p);

        assertThat(p.getStatus()).isEqualTo("created");
        assertThat(p.getRequirementCount()).isEqualTo(0);
        assertThat(p.getCoverageRate()).isEqualTo(0.0);
        assertThat(p.getDefectCount()).isEqualTo(0);
        assertThat(p.getCreateUserId()).isEqualTo(1L);
        verify(projectMapper).insert(p);
    }

    @Test
    @DisplayName("checkStorageQuota 未超限时不抛异常")
    void checkStorageQuotaPassesWithinLimit() throws Exception {
        when(fileStorageUtil.getUploadPath()).thenReturn(tempDir.toString());
        File reqDir = new File(tempDir.toFile(), "requirements/100");
        assertThat(reqDir.mkdirs()).isTrue();
        Files.write(reqDir.toPath().resolve("a.txt"), new byte[100]);

        projectService.checkStorageQuota(100L);
    }

    @Test
    @DisplayName("checkStorageQuota projectId 为空时直接跳过")
    void checkStorageQuotaSkipsNullProjectId() {
        projectService.checkStorageQuota(null);
        verifyNoInteractions(fileStorageUtil);
    }

    @Test
    @DisplayName("computeDirSize 递归统计目录字节数")
    void computeDirSizeRecursivelySums() throws Exception {
        File dir = new File(tempDir.toFile(), "x/y");
        assertThat(dir.mkdirs()).isTrue();
        Files.write(new File(dir, "a").toPath(), new byte[10]);
        Files.write(tempDir.resolve("x").resolve("b"), new byte[5]);

        Long size = ReflectionTestUtils.invokeMethod(projectService, "computeDirSize", new File(tempDir.toFile(), "x"));

        assertThat(size).isEqualTo(15L);
    }

    // ---- 更新/删除/回收站 ----

    @Test
    @DisplayName("update 委托 mapper.updateById")
    void updateDelegates() {
        Project p = buildProject(1L, 1L);
        projectService.update(p);
        verify(projectMapper).updateById(p);
    }

    @Test
    @DisplayName("delete 逻辑删除进入回收站")
    void deleteDelegates() {
        projectService.delete(1L);
        verify(projectMapper).deleteById(1L);
    }

    @Test
    @DisplayName("listDeleted 委托回收站分页查询")
    void listDeletedDelegates() {
        IPage<Project> expected = new Page<>(1, 20);
        when(projectMapper.selectDeletedPage(any(Page.class), anyLong())).thenReturn(expected);

        IPage<Project> result = projectService.listDeleted(1, 20, 5L);

        assertThat(result).isSameAs(expected);
        verify(projectMapper).selectDeletedPage(any(Page.class), Mockito.eq(5L));
    }

    @Test
    @DisplayName("restoreDeleted 非创建者无权操作（403）")
    void restoreDeletedRejectsNonOwner() {
        Project p = buildProject(1L, 2L);
        when(projectMapper.selectDeletedById(1L)).thenReturn(p);

        assertThatThrownBy(() -> projectService.restoreDeleted(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        verify(projectMapper, never()).restoreDeleted(anyLong());
    }

    @Test
    @DisplayName("restoreDeleted 创建者成功恢复")
    void restoreDeletedSuccess() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectDeletedById(1L)).thenReturn(p);
        when(projectMapper.restoreDeleted(1L)).thenReturn(1);

        projectService.restoreDeleted(1L);

        verify(projectMapper).restoreDeleted(1L);
    }

    @Test
    @DisplayName("restoreDeleted 恢复影响行数为 0 时返回 404")
    void restoreDeletedNotFound() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectDeletedById(1L)).thenReturn(p);
        when(projectMapper.restoreDeleted(1L)).thenReturn(0);

        assertThatThrownBy(() -> projectService.restoreDeleted(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 404);
    }

    @Test
    @DisplayName("purgeDeleted 非创建者无权彻底删除（403）")
    void purgeDeletedRejectsNonOwner() {
        Project p = buildProject(1L, 2L);
        when(projectMapper.selectDeletedById(1L)).thenReturn(p);

        assertThatThrownBy(() -> projectService.purgeDeleted(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    @DisplayName("cleanExpiredDeletedProjects 按保留天数清理并容忍失败")
    void cleanExpiredDeletedProjectsUsesRetentionDays() {
        when(projectMapper.purgeExpiredDeleted(30)).thenReturn(2);

        projectService.cleanExpiredDeletedProjects(); // 不抛异常

        verify(projectMapper).purgeExpiredDeleted(30);
    }

    // ---- 归档/恢复 ----

    @Test
    @DisplayName("archive 项目不存在时 404")
    void archiveRejectsMissingProject() {
        when(projectMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> projectService.archive(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("项目不存在");
    }

    @Test
    @DisplayName("archive 已归档项目拒绝重复归档")
    void archiveRejectsAlreadyArchived() {
        Project p = buildProject(1L, 1L);
        p.setStatus("archived");
        when(projectMapper.selectById(1L)).thenReturn(p);

        assertThatThrownBy(() -> projectService.archive(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已处于归档");
    }

    @Test
    @DisplayName("archive 置为 archived 并更新")
    void archiveSuccess() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectById(1L)).thenReturn(p);

        projectService.archive(1L);

        assertThat(p.getStatus()).isEqualTo("archived");
        verify(projectMapper).updateById(p);
    }

    @Test
    @DisplayName("restore 未归档项目拒绝恢复")
    void restoreRejectsNotArchived() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectById(1L)).thenReturn(p);

        assertThatThrownBy(() -> projectService.restore(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未归档");
    }

    @Test
    @DisplayName("restore 归档项目恢复为 created")
    void restoreSuccess() {
        Project p = buildProject(1L, 1L);
        p.setStatus("archived");
        when(projectMapper.selectById(1L)).thenReturn(p);

        projectService.restore(1L);

        assertThat(p.getStatus()).isEqualTo("created");
        verify(projectMapper).updateById(p);
    }

    // ---- 统计回写 ----

    @Test
    @DisplayName("updateStats 项目存在时回写统计")
    void updateStatsWritesWhenProjectExists() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectById(1L)).thenReturn(p);

        projectService.updateStats(1L, 3, 0.5, 2);

        assertThat(p.getRequirementCount()).isEqualTo(3);
        assertThat(p.getCoverageRate()).isEqualTo(0.5);
        assertThat(p.getDefectCount()).isEqualTo(2);
        verify(projectMapper).updateById(p);
    }

    @Test
    @DisplayName("updateStats 项目不存在时不更新")
    void updateStatsSkipsWhenProjectMissing() {
        when(projectMapper.selectById(1L)).thenReturn(null);

        projectService.updateStats(1L, 3, 0.5, 2);

        verify(projectMapper, never()).updateById(any());
    }

    // ---- 数据隔离 checkOwnership（需求5.2.3） ----

    @Test
    @DisplayName("checkOwnership projectId 为空时 400")
    void checkOwnershipRejectsNullProjectId() {
        assertThatThrownBy(() -> projectService.checkOwnership(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 400);
    }

    @Test
    @DisplayName("checkOwnership 管理员免检")
    void checkOwnershipAdminBypasses() {
        UserContext.set(1L, "admin", "admin");

        projectService.checkOwnership(99L);

        verify(projectMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("checkOwnership 普通用户项目不存在时 404")
    void checkOwnershipRejectsMissingProjectForNormalUser() {
        when(projectMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.checkOwnership(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 404);
    }

    @Test
    @DisplayName("checkOwnership 普通用户访问他人项目 403")
    void checkOwnershipRejectsOtherUsersProject() {
        Project p = buildProject(1L, 2L);
        when(projectMapper.selectById(1L)).thenReturn(p);

        assertThatThrownBy(() -> projectService.checkOwnership(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    @DisplayName("checkOwnership 普通用户访问本人项目放行")
    void checkOwnershipAllowsOwner() {
        Project p = buildProject(1L, 1L);
        when(projectMapper.selectById(1L)).thenReturn(p);

        projectService.checkOwnership(1L);
    }

    @Test
    @DisplayName("checkOwnership 历史数据（创建人为空）放行")
    void checkOwnershipAllowsHistoricNullOwner() {
        Project p = buildProject(1L, null);
        when(projectMapper.selectById(1L)).thenReturn(p);

        projectService.checkOwnership(1L);
    }
}
