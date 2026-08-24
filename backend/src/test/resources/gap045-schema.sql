-- GAP-045：Kripke 语义模型补全——测试库幂等补列（与 init.sql 迭代四一致）
-- 测试库（application-test.yml 指向）可能未执行 init.sql 的 GAP-045 段，
-- 此处幂等补充 atomic_propositions / state_labeling，确保 Requirement 实体查询不报缺列。
SET @col_ap = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'atomic_propositions');
SET @sql_ap = IF(@col_ap = 0,
    'ALTER TABLE tg_requirement ADD COLUMN atomic_propositions TEXT COMMENT ''原子命题集合(AP)（GAP-045）''',
    'SELECT 1');
PREPARE stmt_ap FROM @sql_ap;
EXECUTE stmt_ap;
DEALLOCATE PREPARE stmt_ap;

SET @col_l = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_requirement' AND COLUMN_NAME = 'state_labeling');
SET @sql_l = IF(@col_l = 0,
    'ALTER TABLE tg_requirement ADD COLUMN state_labeling TEXT COMMENT ''状态标签函数 L(state)（GAP-045）''',
    'SELECT 1');
PREPARE stmt_l FROM @sql_l;
EXECUTE stmt_l;
DEALLOCATE PREPARE stmt_l;
