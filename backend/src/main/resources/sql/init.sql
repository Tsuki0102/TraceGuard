CREATE DATABASE IF NOT EXISTS traceguard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE traceguard;

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    real_name VARCHAR(50) COMMENT '真实姓名',
    email VARCHAR(100) COMMENT '邮箱',
    role VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin/user',
    last_login_time DATETIME COMMENT '最后登录时间',
    password_updated_at DATETIME COMMENT '密码最近修改时间（AUD-03 密码定期提醒，用于判定是否临近 90 天过期）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 项目表
CREATE TABLE IF NOT EXISTS tg_project (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_name VARCHAR(100) NOT NULL COMMENT '项目名称',
    industry_type VARCHAR(50) COMMENT '行业类型',
    tech_stack VARCHAR(100) COMMENT '技术栈',
    description TEXT COMMENT '项目描述',
    create_user_id BIGINT COMMENT '创建用户ID',
    status VARCHAR(20) DEFAULT 'created' COMMENT '项目状态',
    requirement_count INT DEFAULT 0 COMMENT '需求总数',
    coverage_rate DOUBLE DEFAULT 0 COMMENT '需求覆盖率',
    defect_count INT DEFAULT 0 COMMENT '缺陷总数',
    requirement_file_path VARCHAR(500) COMMENT '需求文件路径',
    code_project_path VARCHAR(500) COMMENT '代码项目路径',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- 分析任务表
CREATE TABLE IF NOT EXISTS tg_analysis_task (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    task_name VARCHAR(100) COMMENT '任务名称',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '任务状态：pending/running/paused/completed/failed/terminated/interrupted(服务重启中断,可续跑)',
    progress INT DEFAULT 0 COMMENT '进度百分比',
    current_step VARCHAR(100) COMMENT '当前执行步骤',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    weight_alpha DOUBLE DEFAULT 0.4 COMMENT '语义相似度权重',
    weight_beta DOUBLE DEFAULT 0.35 COMMENT '约束匹配度权重',
    weight_gamma DOUBLE DEFAULT 0.25 COMMENT '不变量满足度权重',
    threshold_t1 DOUBLE DEFAULT 0.8 COMMENT '一致性阈值T1',
    threshold_t2 DOUBLE DEFAULT 0.5 COMMENT '不一致性阈值T2',
    error_message TEXT COMMENT '错误信息',
    execution_log LONGTEXT COMMENT '执行日志',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务表';

-- 需求语义单元表
CREATE TABLE IF NOT EXISTS tg_requirement (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    requirement_id VARCHAR(50) NOT NULL COMMENT '需求业务ID',
    original_text TEXT NOT NULL COMMENT '需求原文',
    title VARCHAR(200) COMMENT '需求标题（GAP-019，首句截断提取）',
    priority VARCHAR(20) DEFAULT 'normal' COMMENT '需求优先级：must/important/optional/normal（GAP-019）',
    source_file VARCHAR(255) COMMENT '来源文档文件名（GAP-019，多文档批量解析）',
    requirement_type VARCHAR(30) COMMENT '需求类型',
    state_set TEXT COMMENT '状态集合JSON',
    initial_state VARCHAR(200) COMMENT '初始状态',
    state_transitions TEXT COMMENT '状态转移关系JSON',
    atomic_constraints TEXT COMMENT '原子命题约束JSON',
    invariants TEXT COMMENT '系统不变量JSON',
    constraint_rules TEXT COMMENT '约束规则清单',
    ambiguity_report TEXT COMMENT '歧义性检测报告',
    status INT DEFAULT 0 COMMENT '状态：0未处理，1已解析，2已形式化',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_project_id (project_id),
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求语义单元表';

-- 形式化规约表
CREATE TABLE IF NOT EXISTS tg_formal_specification (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    spec_id VARCHAR(50) NOT NULL COMMENT '规约业务ID',
    alloy_code LONGTEXT COMMENT 'Alloy代码',
    verification_status VARCHAR(20) DEFAULT 'pending' COMMENT '校验状态',
    verification_result TEXT COMMENT '校验结果',
    optimization_suggestion TEXT COMMENT '优化建议',
    verification_detail TEXT COMMENT 'Alloy真实求解结果JSON(engine/satStatus/instanceCount/counterexample/message/elapsedMs)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_project_id (project_id),
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='形式化规约表';

-- 代码语义单元表
CREATE TABLE IF NOT EXISTS tg_code_unit (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    code_id VARCHAR(50) NOT NULL COMMENT '代码业务ID',
    file_path VARCHAR(500) COMMENT '文件路径',
    class_name VARCHAR(200) COMMENT '类名',
    method_name VARCHAR(200) COMMENT '方法名',
    start_line INT COMMENT '起始行号',
    end_line INT COMMENT '结束行号',
    code_content LONGTEXT COMMENT '代码内容',
    logic_description TEXT COMMENT '逻辑还原文本',
    logic_analysis TEXT COMMENT 'CodeLogicDescriber 结构化分析结果（JSON）',
    cyclomatic_complexity INT DEFAULT 1 COMMENT '方法圈复杂度（McCabe，基值1，GAP-016）',
    semantic_vector TEXT COMMENT '语义向量JSON',
    cfg_data LONGTEXT COMMENT '控制流图数据JSON',
    constraints TEXT COMMENT '约束实现JSON',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_project_id (project_id),
    INDEX idx_code_id (code_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码语义单元表';

-- 一致性校验结果表
CREATE TABLE IF NOT EXISTS tg_consistency_result (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    requirement_id BIGINT COMMENT '需求ID',
    code_unit_id BIGINT COMMENT '代码单元ID',
    match_id VARCHAR(50) NOT NULL COMMENT '匹配ID',
    semantic_similarity DOUBLE DEFAULT 0 COMMENT '语义相似度',
    constraint_match_degree DOUBLE DEFAULT 0 COMMENT '约束匹配度',
    invariant_satisfaction DOUBLE DEFAULT 0 COMMENT '不变量满足度',
    total_similarity DOUBLE DEFAULT 0 COMMENT '综合相似度',
    consistency_status VARCHAR(20) COMMENT '一致性状态：consistent/general_inconsistent/serious_inconsistent',
    defect_type VARCHAR(50) COMMENT '缺陷类型',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_task_id (task_id),
    INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一致性校验结果表';

-- 缺陷信息表（需求-代码不一致缺陷）
CREATE TABLE IF NOT EXISTS tg_defect (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    consistency_result_id BIGINT COMMENT '一致性结果ID',
    requirement_id BIGINT COMMENT '需求ID',
    code_unit_id BIGINT COMMENT '代码单元ID',
    defect_id VARCHAR(50) NOT NULL COMMENT '缺陷业务ID',
    defect_level VARCHAR(20) COMMENT '缺陷等级：serious/general',
    defect_type VARCHAR(50) COMMENT '缺陷类型',
    defect_reason TEXT COMMENT '缺陷原因',
    repair_suggestion TEXT COMMENT '修复建议',
    code_snippet TEXT COMMENT '相关代码片段',
    requirement_text TEXT COMMENT '相关需求原文',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_task_id (task_id),
    INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷信息表';

-- 数据变更日志表（2.8 整改，FR-PLAT-004：字段级变更前后 diff）
CREATE TABLE IF NOT EXISTS tg_data_change_log (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT COMMENT '关联项目ID（用户等无项目维度实体为NULL）',
    entity_type VARCHAR(50) NOT NULL COMMENT '实体类型：user/project',
    entity_id VARCHAR(50) NOT NULL COMMENT '实体业务ID（字符串化兼容多种主键）',
    operation VARCHAR(20) NOT NULL COMMENT '变更动作：create/update/delete',
    field_name VARCHAR(50) NOT NULL COMMENT '变更字段名（create/delete 为 *）',
    old_value VARCHAR(500) COMMENT '变更前的值',
    new_value VARCHAR(500) COMMENT '变更后的值',
    operator_id BIGINT COMMENT '操作人用户ID',
    operator_name VARCHAR(100) COMMENT '操作人用户名',
    change_time DATETIME COMMENT '变更时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_project_id (project_id),
    INDEX idx_change_time (change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据变更日志表（字段级 diff）';

-- 代码基础缺陷表（静态分析检测）
CREATE TABLE IF NOT EXISTS tg_code_defect (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    task_id BIGINT COMMENT '任务ID',
    file_path VARCHAR(500) COMMENT '文件路径',
    class_name VARCHAR(200) COMMENT '类名',
    method_name VARCHAR(200) COMMENT '方法名',
    line_number INT COMMENT '行号',
    defect_type VARCHAR(50) COMMENT '缺陷类型',
    severity VARCHAR(20) COMMENT '严重程度',
    description TEXT COMMENT '缺陷描述',
    repair_suggestion TEXT COMMENT '修复建议',
    code_snippet TEXT COMMENT '代码片段',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_project_id (project_id),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码基础缺陷表';

CREATE TABLE IF NOT EXISTS tg_audit_log (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT COMMENT '操作用户ID',
    username VARCHAR(50) COMMENT '操作用户名',
    operation VARCHAR(50) COMMENT '操作类型：登录/登出/创建/更新/删除/导出/备份/恢复/启动分析/上传',
    method VARCHAR(10) COMMENT 'HTTP方法',
    path VARCHAR(500) COMMENT '请求路径',
    params VARCHAR(1000) COMMENT '请求参数摘要',
    ip VARCHAR(50) COMMENT '客户端IP',
    status_code INT COMMENT '响应状态码',
    cost_ms BIGINT COMMENT '耗时(毫秒)',
    success INT DEFAULT 1 COMMENT '是否成功：1成功/0失败',
    error_msg VARCHAR(500) COMMENT '错误信息',
    prev_hash VARCHAR(64) COMMENT '哈希链前驱摘要（AUD-08 防篡改）',
    cur_hash VARCHAR(64) COMMENT '哈希链当前摘要=SHA256(prev_hash|业务字段)（AUD-08 防篡改）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT DEFAULT 0 COMMENT '删除标记',
    INDEX idx_username (username),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- 插入默认管理员用户 (密码: admin123，BCrypt加密存储；INSERT IGNORE保证幂等，重启不报主键冲突)
INSERT IGNORE INTO sys_user (id, username, password, real_name, role) VALUES
(1, 'admin', '$2a$10$YnbH4zcV4EHjm.Hp4nelPOlZUBf9JfPW6LvP/oUsIbNxGsPSLd6kq', '系统管理员', 'admin');

-- ==================== 迭代一（P0）新增 ====================

-- GAP-021：大模型运行时配置表（单行表，id 恒为 1；api_key 加密存储于 providers_json）
CREATE TABLE IF NOT EXISTS tg_llm_config (
    id            BIGINT       NOT NULL COMMENT '主键，固定 1',
    enabled       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'LLM 总开关',
    providers_json TEXT        COMMENT 'provider 配置 JSON（api_key 加密存储）',
    routing_json  VARCHAR(500) COMMENT '环节->provider 路由表 JSON',
    models_json   VARCHAR(500) COMMENT '环节->model 名 JSON',
    update_time   DATETIME     COMMENT '更新时间',
    update_by     BIGINT       COMMENT '更新人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型运行时配置';

-- 老库 ALTER 兼容（新列/新表不存在时补充；MySQL 8.0 通过 information_schema 条件判断保证幂等）
SET @spec_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_formal_specification' AND COLUMN_NAME = 'verification_detail');
SET @ddl1 := IF(@spec_col = 0,
    'ALTER TABLE tg_formal_specification ADD COLUMN verification_detail TEXT COMMENT ''Alloy真实求解结果JSON(engine/satStatus/instanceCount/counterexample/message/elapsedMs)''',
    'SELECT 1');
PREPARE stmt1 FROM @ddl1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

-- GAP-004：tg_requirement 增列 semantic_vector（语义向量 JSON，兼容旧数据无 vector 键）
SET @semvec_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'semantic_vector');
SET @ddl_semvec := IF(@semvec_col = 0,
    'ALTER TABLE tg_requirement ADD COLUMN semantic_vector TEXT COMMENT ''语义向量 JSON({"vector":[...],"dim":N,"terms":"..."}；旧数据无 vector 键)''',
    'SELECT 1');
PREPARE stmt_semvec FROM @ddl_semvec;
EXECUTE stmt_semvec;
DEALLOCATE PREPARE stmt_semvec;

-- GAP-006：tg_code_unit 增列 logic_analysis（CodeLogicDescriber 结构化分析结果 JSON）
SET @logic_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_code_unit' AND COLUMN_NAME = 'logic_analysis');
SET @ddl_logic := IF(@logic_col = 0,
    'ALTER TABLE tg_code_unit ADD COLUMN logic_analysis TEXT COMMENT ''CodeLogicDescriber 结构化分析结果（JSON）''',
    'SELECT 1');
PREPARE stmt_logic FROM @ddl_logic;
EXECUTE stmt_logic;
DEALLOCATE PREPARE stmt_logic;

-- ==================== 迭代二（P1）新增 ====================

-- GAP-020：tg_defect 增列 sub_type（缺陷子类型，原细分类型保留供二级筛选）
SET @subtype_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_defect' AND COLUMN_NAME = 'sub_type');
SET @ddl_subtype := IF(@subtype_col = 0,
    'ALTER TABLE tg_defect ADD COLUMN sub_type VARCHAR(50) COMMENT ''缺陷子类型（原细分类型，供二级筛选与详情展示）''',
    'SELECT 1');
PREPARE stmt_subtype FROM @ddl_subtype;
EXECUTE stmt_subtype;
DEALLOCATE PREPARE stmt_subtype;

-- GAP-014：tg_project 增列 parse_failures（批量解析失败清单 JSON）
SET @pf_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_project' AND COLUMN_NAME = 'parse_failures');
SET @ddl_pf := IF(@pf_col = 0,
    'ALTER TABLE tg_project ADD COLUMN parse_failures TEXT COMMENT ''批量解析失败清单 JSON([{fileName,reason,suggestion,fileSize}])''',
    'SELECT 1');
PREPARE stmt_pf FROM @ddl_pf;
EXECUTE stmt_pf;
DEALLOCATE PREPARE stmt_pf;

-- GAP-011：缺陷状态闭环流转
SET @status_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_defect' AND COLUMN_NAME = 'status');
SET @ddl_status := IF(@status_col = 0,
    'ALTER TABLE tg_defect ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''pending'' COMMENT ''缺陷状态：pending待处理/processing处理中/resolved已解决/ignored已忽略'', ADD COLUMN handle_time DATETIME COMMENT ''最近状态流转时间'', ADD COLUMN handler_id BIGINT COMMENT ''最近流转操作人用户ID（sys_user.id）''',
    'SELECT 1');
PREPARE stmt_status FROM @ddl_status;
EXECUTE stmt_status;
DEALLOCATE PREPARE stmt_status;

-- GAP-010：报告模板用户自定义
CREATE TABLE IF NOT EXISTS tg_report_template_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) COMMENT '模板编码（系统模板：FULL/DEFECT_ONLY/BRIEF；用户模板生成 UUID 短码）',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    sections TEXT NOT NULL COMMENT '章节配置 JSON([{key,title},...]，数组顺序即渲染顺序)',
    title VARCHAR(200) COMMENT '报告主标题（空=系统默认标题）',
    sort INT DEFAULT 0 COMMENT '前端展示排序',
    is_default TINYINT(1) DEFAULT 0 COMMENT '是否默认模板（全局唯一）',
    is_system TINYINT(1) DEFAULT 0 COMMENT '系统内置模板（不可删改）',
    user_id BIGINT COMMENT '创建人用户ID（系统模板为 NULL）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0 COMMENT '删除标记'
) COMMENT '报告模板配置（FR-CHECK-005 业务规则 3）';

-- 4.4 整改：报告排版自定义字段（subtitle 封面副标题 / header_text 页眉文本）；兼容已存在旧库的增量迁移
SET @s = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_report_template_config' AND COLUMN_NAME = 'subtitle') = 0,
    'ALTER TABLE tg_report_template_config ADD COLUMN subtitle VARCHAR(200) COMMENT ''封面副标题（4.4 报告排版自定义，空=不显示）''',
    'SELECT 1'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_report_template_config' AND COLUMN_NAME = 'header_text') = 0,
    'ALTER TABLE tg_report_template_config ADD COLUMN header_text VARCHAR(200) COMMENT ''页眉文本（4.4 报告排版自定义，空=使用报告标题）''',
    'SELECT 1'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- GAP-010 修复：tg_report_template_config.code 缺唯一索引导致 INSERT IGNORE 失效、每次跑 init.sql 都新增一条同 code 模板
-- 下游会拿到几十条同 code 模板，前端模板下拉显示成百条同名项无法选择
-- 幂等：仅在索引不存在时创建
SET @s = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_report_template_config' AND INDEX_NAME = 'uk_code') = 0,
    'ALTER TABLE tg_report_template_config ADD UNIQUE KEY uk_code (code)',
    'SELECT 1'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 初始数据：三条系统模板（幂等插入）
INSERT IGNORE INTO tg_report_template_config (code, template_name, sections, title, sort, is_default, is_system) VALUES
('FULL', '完整报告',
 '[{"key":"project-overview","title":""},{"key":"stats-summary","title":""},{"key":"defect-type-distribution","title":""},{"key":"defect-detail","title":""},{"key":"code-quality","title":""},{"key":"traceability-matrix","title":""}]',
 '', 1, 1, 1),
('DEFECT_ONLY', '缺陷聚焦报告',
 '[{"key":"project-overview","title":""},{"key":"stats-summary","title":""},{"key":"defect-detail","title":""},{"key":"code-quality","title":""}]',
 '', 2, 0, 1),
('BRIEF', '简要报告',
 '[{"key":"project-overview","title":""},{"key":"stats-summary","title":""}]',
 '', 3, 0, 1);

-- GAP-026（2.6 整改）：tg_defect 增列 defect_line（缺陷命中代码行号，绝对行号；NULL=未定位，回退方法起始行）
SET @defectline_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_defect' AND COLUMN_NAME = 'defect_line');
SET @ddl_defectline := IF(@defectline_col = 0,
    'ALTER TABLE tg_defect ADD COLUMN defect_line INT COMMENT ''缺陷命中代码行号（基于 CodeUnit.startLine 偏移的绝对行号；NULL=未定位）''',
    'SELECT 1');
PREPARE stmt_defectline FROM @ddl_defectline;
EXECUTE stmt_defectline;
DEALLOCATE PREPARE stmt_defectline;

-- GAP-009：tg_defect 增列 remote_issue_key（第三方工单关联标识）
SET @ri_defect := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_defect' AND COLUMN_NAME = 'remote_issue_key');
SET @ddl_ri_defect := IF(@ri_defect = 0,
    'ALTER TABLE tg_defect ADD COLUMN remote_issue_key VARCHAR(200) COMMENT ''第三方项目管理工具工单Key（如 JIRA-123）''',
    'SELECT 1');
PREPARE stmt_ri_defect FROM @ddl_ri_defect;
EXECUTE stmt_ri_defect;
DEALLOCATE PREPARE stmt_ri_defect;

-- P1-4：tg_defect 增列 risk_signals（explainSignals 规则信号分解 JSON，供前端"命中规则/信号贡献"面板）
SET @rs_defect := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_defect' AND COLUMN_NAME = 'risk_signals');
SET @ddl_rs_defect := IF(@rs_defect = 0,
    'ALTER TABLE tg_defect ADD COLUMN risk_signals TEXT COMMENT ''规则信号分解JSON（explainSignals：{"risk":..,"signals":{..}}）''',
    'SELECT 1');
PREPARE stmt_rs_defect FROM @ddl_rs_defect;
EXECUTE stmt_rs_defect;
DEALLOCATE PREPARE stmt_rs_defect;

-- GAP-009：tg_requirement 增列 remote_issue_key（第三方工单关联标识）
SET @ri_req := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'remote_issue_key');
SET @ddl_ri_req := IF(@ri_req = 0,
    'ALTER TABLE tg_requirement ADD COLUMN remote_issue_key VARCHAR(200) COMMENT ''第三方项目管理工具工单Key（如 JIRA-456）''',
    'SELECT 1');
PREPARE stmt_ri_req FROM @ddl_ri_req;
EXECUTE stmt_ri_req;
DEALLOCATE PREPARE stmt_ri_req;

-- GAP-027：sys_user 增列 must_change_password（强制改密标志）
SET @mcp_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'must_change_password');
SET @ddl_mcp := IF(@mcp_col = 0,
    'ALTER TABLE sys_user ADD COLUMN must_change_password TINYINT(1) DEFAULT 0 COMMENT ''是否强制修改密码（0否/1是）''',
    'SELECT 1');
PREPARE stmt_mcp FROM @ddl_mcp;
EXECUTE stmt_mcp;
DEALLOCATE PREPARE stmt_mcp;

-- GAP-027：默认管理员首次需改密
UPDATE sys_user SET must_change_password = 1 WHERE username = 'admin' AND must_change_password IS NULL;

-- AUD-03：sys_user 增列 password_updated_at（密码定期提醒，90 天过期口径）
SET @pua_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'password_updated_at');
SET @ddl_pua := IF(@pua_col = 0,
    'ALTER TABLE sys_user ADD COLUMN password_updated_at DATETIME COMMENT ''密码最近修改时间（AUD-03 密码定期提醒）''',
    'SELECT 1');
PREPARE stmt_pua FROM @ddl_pua;
EXECUTE stmt_pua;
DEALLOCATE PREPARE stmt_pua;

-- AUD-03：存量用户（无密码修改时间记录）按最后登录时间或创建时间兜底，避免立即触发过期提醒
UPDATE sys_user SET password_updated_at = COALESCE(last_login_time, create_time)
    WHERE password_updated_at IS NULL;

-- AUD-08：tg_audit_log 增列 prev_hash / cur_hash（哈希链防篡改）
SET @ph_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_audit_log' AND COLUMN_NAME = 'prev_hash');
SET @ddl_ph := IF(@ph_col = 0,
    'ALTER TABLE tg_audit_log ADD COLUMN prev_hash VARCHAR(64) COMMENT ''哈希链前驱摘要（AUD-08）''',
    'SELECT 1');
PREPARE stmt_ph FROM @ddl_ph;
EXECUTE stmt_ph;
DEALLOCATE PREPARE stmt_ph;

SET @ch_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_audit_log' AND COLUMN_NAME = 'cur_hash');
SET @ddl_ch := IF(@ch_col = 0,
    'ALTER TABLE tg_audit_log ADD COLUMN cur_hash VARCHAR(64) COMMENT ''哈希链当前摘要（AUD-08）''',
    'SELECT 1');
PREPARE stmt_ch FROM @ddl_ch;
EXECUTE stmt_ch;
DEALLOCATE PREPARE stmt_ch;

-- ==================== 迭代三（P2）新增 ====================

-- AUD-07 / GAP-002：系统配置表（需求解析规则可视化配置，通用键值存储）
CREATE TABLE IF NOT EXISTS sys_config (
    config_key   VARCHAR(64)  NOT NULL COMMENT '配置键（主键）',
    config_value TEXT         COMMENT '配置值（JSON 文本）',
    description  VARCHAR(255) COMMENT '配置说明',
    updated_by   VARCHAR(64)  COMMENT '最近更新人',
    updated_at   DATETIME     COMMENT '更新时间',
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表（AUD-07）';

-- AUD-07：需求解析规则默认值（歧义/模糊词 + 互斥词对），管理员可在后台调整
INSERT IGNORE INTO sys_config (config_key, config_value, description, updated_by, updated_at) VALUES
('req_parse_rules',
 '{"ambiguityKeywords":["等","等等","适当","合适","合理","可能","也许","若干","一些","方便","友好"],"contradictionPairs":[["必须","禁止"],["必须","不得"],["禁止","(?<!不)允许"],["不得","(?<!不)允许"],["启用","禁用"]],"enableLLM":false}',
 '需求解析规则：歧义词清单与互斥词对（FR-REQ-002，可经管理后台增删）', 'system', NOW());

-- GAP-019：tg_requirement 增列 title（需求标题，首句截断提取）
SET @title_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'title');
SET @ddl_title := IF(@title_col = 0,
    'ALTER TABLE tg_requirement ADD COLUMN title VARCHAR(200) COMMENT ''需求标题（GAP-019，首句截断提取）''',
    'SELECT 1');
PREPARE stmt_title FROM @ddl_title;
EXECUTE stmt_title;
DEALLOCATE PREPARE stmt_title;

-- GAP-019：tg_requirement 增列 priority（需求优先级）
SET @pri_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'priority');
SET @ddl_pri := IF(@pri_col = 0,
    'ALTER TABLE tg_requirement ADD COLUMN priority VARCHAR(20) DEFAULT ''normal'' COMMENT ''需求优先级：must/important/optional/normal（GAP-019）''',
    'SELECT 1');
PREPARE stmt_pri FROM @ddl_pri;
EXECUTE stmt_pri;
DEALLOCATE PREPARE stmt_pri;

-- GAP-019：tg_requirement 增列 source_file（来源文档文件名）
SET @src_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'source_file');
SET @ddl_src := IF(@src_col = 0,
    'ALTER TABLE tg_requirement ADD COLUMN source_file VARCHAR(255) COMMENT ''来源文档文件名（GAP-019，多文档批量解析）''',
    'SELECT 1');
PREPARE stmt_src FROM @ddl_src;
EXECUTE stmt_src;
DEALLOCATE PREPARE stmt_src;

-- GAP-019：存量迁移——历史需求按原文首句兜底填充标题，优先级默认 normal
UPDATE tg_requirement SET title = LEFT(TRIM(original_text), 50)
    WHERE (title IS NULL OR title = '') AND original_text IS NOT NULL;
UPDATE tg_requirement SET priority = 'normal' WHERE priority IS NULL OR priority = '';

-- GAP-016：tg_code_unit 增列 cyclomatic_complexity（方法圈复杂度，McCabe 基值 1）
SET @cc_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_code_unit' AND COLUMN_NAME = 'cyclomatic_complexity');
SET @ddl_cc := IF(@cc_col = 0,
    'ALTER TABLE tg_code_unit ADD COLUMN cyclomatic_complexity INT DEFAULT 1 COMMENT ''方法圈复杂度（McCabe，基值1，GAP-016）''',
    'SELECT 1');
PREPARE stmt_cc FROM @ddl_cc;
EXECUTE stmt_cc;
DEALLOCATE PREPARE stmt_cc;

-- GAP-016：存量迁移——历史代码单元复杂度默认 1
UPDATE tg_code_unit SET cyclomatic_complexity = 1 WHERE cyclomatic_complexity IS NULL OR cyclomatic_complexity < 1;

-- P2-5：tg_code_unit 增列 content_hash（源文件内容 sha256，增量分析用；每文件内各方法单元一致）
SET @chash_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_code_unit' AND COLUMN_NAME = 'content_hash');
SET @ddl_chash := IF(@chash_col = 0,
    'ALTER TABLE tg_code_unit ADD COLUMN content_hash VARCHAR(64) NULL COMMENT ''源文件内容 sha256（P2-5 增量分析）''',
    'SELECT 1');
PREPARE stmt_chash FROM @ddl_chash;
EXECUTE stmt_chash;
DEALLOCATE PREPARE stmt_chash;

-- P2-5：tg_code_unit (project_id, file_path) 增量比对索引（老库补充，幂等）
SET @chash_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_code_unit' AND INDEX_NAME = 'idx_project_file');
SET @ddl_chash_idx := IF(@chash_idx = 0,
    'ALTER TABLE tg_code_unit ADD INDEX idx_project_file (project_id, file_path)',
    'SELECT 1');
PREPARE stmt_chash_idx FROM @ddl_chash_idx;
EXECUTE stmt_chash_idx;
DEALLOCATE PREPARE stmt_chash_idx;

-- ==================== 迭代四（P0 收尾）新增 ====================
-- GAP-045：Kripke 语义模型补全——原子命题(AP)与状态标签函数(L)
-- tg_requirement 增列 atomic_propositions（原子命题集合，逗号分隔的可判真伪命题）
-- 与 state_labeling（状态标签函数 L(state)={为真的原子命题}，JSON 列表）。
-- 存量数据无 AP/L，默认 NULL（分析时按需重算，不影响既有逻辑）。
SET @col_ap = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'atomic_propositions');
SET @sql_ap = IF(@col_ap = 0,
    'ALTER TABLE tg_requirement ADD COLUMN atomic_propositions TEXT COMMENT ''原子命题集合(AP)：从需求约束点/边界条件提取的最小可判真伪命题，逗号分隔（GAP-045）''',
    'SELECT 1');
PREPARE stmt_ap FROM @sql_ap;
EXECUTE stmt_ap;
DEALLOCATE PREPARE stmt_ap;

SET @col_l = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'state_labeling');
SET @sql_l = IF(@col_l = 0,
    'ALTER TABLE tg_requirement ADD COLUMN state_labeling TEXT COMMENT ''状态标签函数 L(state)={为真的原子命题}，JSON 列表（GAP-045）''',
    'SELECT 1');
PREPARE stmt_l FROM @sql_l;
EXECUTE stmt_l;
DEALLOCATE PREPARE stmt_l;

-- ==================== SEC-16：JWT 吊销黑名单 ====================
-- 登出时按 jti 加入黑名单，使该 token 立即失效（无需等待自然过期）
CREATE TABLE IF NOT EXISTS tg_token_blacklist (
    jti          VARCHAR(64)  NOT NULL COMMENT 'JWT jti 唯一标识',
    expire_time  DATETIME     NOT NULL COMMENT 'token 过期时间（超过即失效，用于惰性清理）',
    created_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入黑名单时间',
    PRIMARY KEY (jti),
    KEY idx_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JWT 吊销黑名单（SEC-16）';

-- ==================== E4：tg_analysis_task.project_id 查询索引（老库补充，幂等） ====================
-- 已按 project_id 维度分页/统计查询，补充索引避免全表扫描；information_schema 判断保证可重复执行
SET @task_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_analysis_task' AND INDEX_NAME = 'idx_project_id');
SET @task_idx_sql := IF(@task_idx = 0,
    'ALTER TABLE tg_analysis_task ADD INDEX idx_project_id (project_id)',
    'SELECT 1');
PREPARE task_idx_stmt FROM @task_idx_sql;
EXECUTE task_idx_stmt;
DEALLOCATE PREPARE task_idx_stmt;

-- ==================== W5：LLM 调用用量日志（大模型配置页用量区块数据源） ====================
-- 由 LlmCallExecutor 每次真实调用后写入；失败静默，不影响主流程
CREATE TABLE IF NOT EXISTS tg_llm_call_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene         VARCHAR(50)  DEFAULT NULL COMMENT '调用场景（requirement/alloy/code/consistency/chat/explain）',
    model         VARCHAR(100) DEFAULT NULL COMMENT '模型标识',
    success       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否成功 1/0',
    latency_ms    INT          DEFAULT NULL COMMENT '耗时（毫秒）',
    error_msg     VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    PRIMARY KEY (id),
    KEY idx_llmlog_day (create_time),
    KEY idx_llmlog_scene (scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用用量日志（W5 用量看板）';

-- =====================================================================
-- 个性化增强 BATCH-4：用户界面偏好（主题/密度/工作台卡片布局）
-- 每用户一行，pref_json 整包 JSON，前端本地优先、后端跨设备同步
-- =====================================================================
CREATE TABLE IF NOT EXISTS tg_user_preference (
  id BIGINT NOT NULL COMMENT '主键（雪花ID）',
  user_id BIGINT NOT NULL COMMENT '用户ID（唯一）',
  pref_json TEXT COMMENT '偏好JSON：{theme,accent,density,dashCards}',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pref_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户界面偏好';
