package com.sample.apiservice;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 模板管理器 - 负责通知模板的增删改查和渲染
 * 支持变量替换（如 ${username}、${code}），模板内容不超过500字符。
 *
 * @author sample
 * @version 1.0
 */
public class TemplateManager {

    private static final Logger logger = Logger.getLogger(TemplateManager.class.getName());

    /** 模板存储 */
    private final Map<String, Template> templateStore = new ConcurrentHashMap<>();

    /** 模板内容最大长度 */
    private static final int MAX_TEMPLATE_LENGTH = 500;

    /** 变量匹配正则: ${variableName} */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    /**
     * 模板实体（内部类）
     */
    public static class Template {
        private String id;
        private String name;
        private String content;
        private String channel;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        private boolean active;

        public Template() {
        }

        public Template(String id, String name, String content, String channel) {
            this.id = id;
            this.name = name;
            this.content = content;
            this.channel = channel;
            this.createTime = LocalDateTime.now();
            this.updateTime = LocalDateTime.now();
            this.active = true;
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public LocalDateTime getCreateTime() { return createTime; }
        public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
        public LocalDateTime getUpdateTime() { return updateTime; }
        public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    // ===== 1. 创建模板 =====

    /**
     * 创建新模板
     *
     * @param name    模板名称
     * @param content 模板内容（支持 ${var} 变量）
     * @param channel 适用渠道
     * @return 模板ID
     */
    public String create(String name, String content, String channel) {
        // 校验模板内容
        validate(name, content, channel);

        String id = UUID.randomUUID().toString();
        Template template = new Template(id, name, content, channel);
        templateStore.put(id, template);

        logger.info("模板创建成功: id=" + id + ", name=" + name);
        return id;
    }

    // ===== 2. 更新模板 =====

    /**
     * 更新已有模板
     *
     * @param templateId 模板ID
     * @param name       新名称
     * @param content    新内容
     * @param channel    新渠道
     * @return 是否更新成功
     */
    public boolean update(String templateId, String name, String content, String channel) {
        Template template = templateStore.get(templateId);
        if (template == null || !template.isActive()) {
            return false;
        }

        validate(name, content, channel);

        template.setName(name);
        template.setContent(content);
        template.setChannel(channel);
        template.setUpdateTime(LocalDateTime.now());

        logger.info("模板更新成功: id=" + templateId);
        return true;
    }

    // ===== 3. 删除模板 =====

    /**
     * 删除模板（逻辑删除）
     *
     * @param templateId 模板ID
     * @return 是否删除成功
     */
    public boolean delete(String templateId) {
        Template template = templateStore.get(templateId);
        if (template == null) {
            return false;
        }
        template.setActive(false);
        template.setUpdateTime(LocalDateTime.now());
        logger.info("模板删除成功: id=" + templateId);
        return true;
    }

    // ===== 4. 根据ID获取模板 =====

    /**
     * 根据ID获取模板
     *
     * @param templateId 模板ID
     * @return 模板对象，不存在返回 null
     */
    public Template getById(String templateId) {
        Template template = templateStore.get(templateId);
        if (template != null && template.isActive()) {
            return template;
        }
        return null;
    }

    // ===== 5. 列出所有模板 =====

    /**
     * 获取所有有效模板列表
     *
     * @return 模板列表
     */
    public List<Template> listAll() {
        return templateStore.values().stream()
                .filter(Template::isActive)
                .collect(Collectors.toList());
    }

    // ===== 6. 渲染模板 =====

    /**
     * 使用变量渲染模板内容
     *
     * @param templateId 模板ID
     * @param variables  变量映射表
     * @return 渲染后的内容
     */
    public String render(String templateId, Map<String, String> variables) {
        Template template = getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        String content = template.getContent();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String varValue = variables.get(varName);
            if (varValue == null) {
                varValue = "${" + varName + "}"; // 未提供变量保持原样
            }
            // 【缺陷】没有对 varValue 进行转义，如果包含特殊字符可能导致注入
            matcher.appendReplacement(result, varValue);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // ===== 7. 校验模板 =====

    /**
     * 校验模板参数合法性
     *
     * @param name    模板名称
     * @param content 模板内容
     * @param channel 渠道
     */
    public void validate(String name, String content, String channel) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("模板内容不能为空");
        }
        // 【缺陷】需求 REQ-004 要求模板内容不超过500字符，但这里校验的是 1000
        if (content.length() > 1000) {
            throw new IllegalArgumentException("模板内容不得超过1000字符");
        }
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("模板渠道不能为空");
        }
        // 校验变量语法
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (varName.length() > 50) {
                throw new IllegalArgumentException("变量名过长: " + varName);
            }
        }
    }

    // ===== 8. 克隆模板 =====

    /**
     * 克隆一个已有模板
     *
     * @param sourceTemplateId 源模板ID
     * @param newName          新模板名称
     * @return 新模板ID
     */
    public String cloneTemplate(String sourceTemplateId, String newName) {
        Template source = getById(sourceTemplateId);
        if (source == null) {
            throw new IllegalArgumentException("源模板不存在: " + sourceTemplateId);
        }

        String newId = UUID.randomUUID().toString();
        Template cloned = new Template(newId, newName, source.getContent(), source.getChannel());
        templateStore.put(newId, cloned);

        logger.info("模板克隆成功: source=" + sourceTemplateId + ", new=" + newId);
        return newId;
    }
}
