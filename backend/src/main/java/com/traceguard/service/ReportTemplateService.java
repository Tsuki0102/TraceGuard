package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.common.BusinessException;
import com.traceguard.entity.ReportTemplateConfig;
import com.traceguard.mapper.ReportTemplateConfigMapper;
import com.traceguard.service.report.ReportSectionRenderer;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * GAP-010：报告模板 CRUD 服务
 * 校验规则：名称非空唯一、sections 非空且 key 全部合法、系统模板不可删改、默认模板切换与删除回退
 */
@Service
public class ReportTemplateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportTemplateService.class);

    /** 合法 sectionKey 集合（从渲染器注册表获取） */
    @Autowired
    private List<ReportSectionRenderer> sectionRenderers;

    @Autowired
    private ReportTemplateConfigMapper templateConfigMapper;

    /** 获取所有合法 sectionKey */
    private Set<String> validSectionKeys() {
        Set<String> keys = new HashSet<>();
        for (ReportSectionRenderer r : sectionRenderers) {
            keys.add(r.key());
        }
        return keys;
    }

    /** 查询可见模板（按 sort 升序）。
     *  细粒度数据权限：管理员返回全部模板；普通用户仅返回系统模板 + 自己创建的用户模板，
     *  防止用户 A 窥探/使用用户 B 自定义的私有模板。 */
    public List<ReportTemplateConfig> listAll() {
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        if (!UserContext.isAdmin()) {
            wrapper.and(w -> w.isNull(ReportTemplateConfig::getUserId)
                            .or(w2 -> w2.eq(ReportTemplateConfig::getUserId, UserContext.getUserId())));
        }
        wrapper.orderByAsc(ReportTemplateConfig::getSort)
               .orderByDesc(ReportTemplateConfig::getCreateTime);
        return templateConfigMapper.selectList(wrapper);
    }

    /** 根据 ID 查询 */
    public ReportTemplateConfig getById(Long id) {
        ReportTemplateConfig config = templateConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "模板不存在");
        }
        return config;
    }

    /**
     * 细粒度数据权限：导出场景的默认模板解析。
     * 全局默认模板可能是他人私有模板（普通用户无权访问），此时回退到系统 FULL 模板，
     * 防止通过默认模板路径间接读取他人私有模板内容。
     */
    public ReportTemplateConfig resolveDefaultForExport() {
        ReportTemplateConfig config = getDefault();
        if (config == null) {
            return null;
        }
        if (UserContext.isAdmin()) {
            return config;
        }
        boolean isSystem = config.getIsSystem() != null && config.getIsSystem();
        boolean visible = isSystem || config.getUserId() == null
                || config.getUserId().equals(UserContext.getUserId());
        if (visible) {
            return config;
        }
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getCode, "FULL");
        return templateConfigMapper.selectOne(wrapper);
    }

    /**
     * 细粒度数据权限：校验模板归属。
     * 管理员不受限；普通用户仅可访问系统模板（userId 为 NULL）或自己创建的用户模板。
     */
    public void checkTemplateAccess(Long id) {
        ReportTemplateConfig config = getById(id);
        if (UserContext.isAdmin()) {
            return;
        }
        boolean isSystem = config.getIsSystem() != null && config.getIsSystem();
        if (isSystem || config.getUserId() == null) {
            return;
        }
        if (!config.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(403, "无权限：仅模板创建者可访问该模板");
        }
    }

    /** 获取当前默认模板 */
    public ReportTemplateConfig getDefault() {
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getIsDefault, true)
               .last("LIMIT 1");
        ReportTemplateConfig config = templateConfigMapper.selectOne(wrapper);
        if (config == null) {
            // 回退：取 FULL 系统模板
            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ReportTemplateConfig::getCode, "FULL");
            config = templateConfigMapper.selectOne(wrapper);
        }
        return config;
    }

    /** 创建用户模板 */
    public ReportTemplateConfig create(ReportTemplateConfig config) {
        validateCreate(config);
        config.setIsSystem(false);
        config.setIsDefault(false);
        config.setCode(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        config.setUserId(UserContext.getUserId());
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        if (config.getSort() == null) {
            config.setSort(100);
        }
        templateConfigMapper.insert(config);
        return config;
    }

    /** 更新用户模板 */
    public ReportTemplateConfig update(Long id, ReportTemplateConfig config) {
        ReportTemplateConfig existing = getById(id);
        if (existing.getIsSystem() != null && existing.getIsSystem()) {
            throw new BusinessException("系统内置模板不可修改");
        }
        // 细粒度数据权限：仅模板创建者可编辑（管理员不受限）
        checkTemplateOwner(existing);
        validateUpdate(existing, config);
        existing.setTemplateName(config.getTemplateName());
        existing.setSections(config.getSections());
        existing.setTitle(config.getTitle());
        existing.setSubtitle(config.getSubtitle());
        existing.setHeaderText(config.getHeaderText());
        existing.setSort(config.getSort());
        existing.setUpdateTime(LocalDateTime.now());
        templateConfigMapper.updateById(existing);
        return existing;
    }

    /** 删除用户模板 */
    public void delete(Long id) {
        ReportTemplateConfig existing = getById(id);
        if (existing.getIsSystem() != null && existing.getIsSystem()) {
            throw new BusinessException("系统内置模板不可删除");
        }
        // 细粒度数据权限：仅模板创建者可删除（管理员不受限）
        checkTemplateOwner(existing);
        // 删除默认模板时回退 FULL 为默认
        if (existing.getIsDefault() != null && existing.getIsDefault()) {
            resetDefaultToFull();
        }
        templateConfigMapper.deleteById(id);
    }

    /** 设为默认模板 */
    public void setDefault(Long id) {
        ReportTemplateConfig target = getById(id);
        // 细粒度数据权限：仅模板创建者可设为默认（管理员不受限；系统模板亦仅管理员可设默认）
        if (!UserContext.isAdmin()) {
            boolean isSystem = target.getIsSystem() != null && target.getIsSystem();
            if (isSystem || target.getUserId() == null
                    || !target.getUserId().equals(UserContext.getUserId())) {
                throw new BusinessException(403, "无权限：仅模板创建者可设置默认模板");
            }
        }
        // 先取消当前默认
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getIsDefault, true);
        List<ReportTemplateConfig> currentDefaults = templateConfigMapper.selectList(wrapper);
        for (ReportTemplateConfig d : currentDefaults) {
            d.setIsDefault(false);
            d.setUpdateTime(LocalDateTime.now());
            templateConfigMapper.updateById(d);
        }
        // 设置新默认
        target.setIsDefault(true);
        target.setUpdateTime(LocalDateTime.now());
        templateConfigMapper.updateById(target);
    }

    // ==================== 校验方法 ====================

    /**
     * 细粒度数据权限：校验当前用户是否为模板创建者（管理员不受限）。
     * 用户模板（userId 非空）仅创建者可编辑/删除。
     */
    private void checkTemplateOwner(ReportTemplateConfig config) {
        if (UserContext.isAdmin()) {
            return;
        }
        if (config.getUserId() == null || !config.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(403, "无权限：仅模板创建者可操作该模板");
        }
    }

    private void validateCreate(ReportTemplateConfig config) {
        // 名称非空
        if (config.getTemplateName() == null || config.getTemplateName().trim().isEmpty()) {
            throw new BusinessException("模板名称不能为空");
        }
        // 名称唯一
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getTemplateName, config.getTemplateName().trim());
        if (templateConfigMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("模板名称已存在");
        }
        // sections 非空且 key 合法
        validateSections(config.getSections());
    }

    private void validateUpdate(ReportTemplateConfig existing, ReportTemplateConfig config) {
        // 名称非空
        if (config.getTemplateName() == null || config.getTemplateName().trim().isEmpty()) {
            throw new BusinessException("模板名称不能为空");
        }
        // 名称唯一（排除自身）
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getTemplateName, config.getTemplateName().trim())
               .ne(ReportTemplateConfig::getId, existing.getId());
        if (templateConfigMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("模板名称已存在");
        }
        // sections 非空且 key 合法
        validateSections(config.getSections());
    }

    /** 校验 sections JSON：非空且所有 key 在渲染器注册表中合法 */
    void validateSections(String sectionsJson) {
        if (sectionsJson == null || sectionsJson.trim().isEmpty()) {
            throw new BusinessException("章节配置不能为空");
        }
        Set<String> validKeys = validSectionKeys();
        List<String> keys = parseSectionKeys(sectionsJson);
        if (keys.isEmpty()) {
            throw new BusinessException("章节配置不能为空");
        }
        for (String key : keys) {
            if (!validKeys.contains(key)) {
                throw new BusinessException("不合法的章节标识: " + key);
            }
        }
    }

    /** 从 sections JSON 中提取所有 key 值 */
    @SuppressWarnings("unchecked")
    List<String> parseSectionKeys(String sectionsJson) {
        List<String> keys = new ArrayList<>();
        try {
            String json = sectionsJson.trim();
            if (json.startsWith("[")) json = json.substring(1);
            if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
            if (json.isEmpty()) return keys;

            int depth = 0;
            int start = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') { depth++; if (depth == 1) start = i; }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String obj = json.substring(start, i + 1);
                        String key = extractJsonValue(obj, "key");
                        if (key != null && !key.isEmpty()) {
                            keys.add(key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("解析 sections JSON 失败: {}", e.getMessage());
        }
        return keys;
    }

    private String extractJsonValue(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        idx = json.indexOf(":", idx + search.length());
        if (idx < 0) return "";
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return "";
        if (json.charAt(idx) == '"') {
            int end = json.indexOf("\"", idx + 1);
            if (end < 0) return "";
            return json.substring(idx + 1, end);
        }
        int end = idx;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(idx, end).trim();
    }

    /** 将默认回退到 FULL 系统模板 */
    private void resetDefaultToFull() {
        LambdaQueryWrapper<ReportTemplateConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReportTemplateConfig::getCode, "FULL");
        ReportTemplateConfig full = templateConfigMapper.selectOne(wrapper);
        if (full != null) {
            full.setIsDefault(true);
            full.setUpdateTime(LocalDateTime.now());
            templateConfigMapper.updateById(full);
        }
    }
}
