-- =====================================================================
-- B6 演示环境数据复位脚本（scripts/demo-reset.sql）
-- 用途：演示实例被评审操作"玩坏"后，一键恢复到干净的初始状态，可重新执行
--       scripts/demo-seed.ps1 生成演示数据。
-- 保留：sys_user（账号）、tg_llm_config / sys_config / tg_report_template_config
--       （配置与模板）、tg_user_preference（界面偏好）。
-- 清空：项目/任务/需求/规约/代码单元/一致性结果/缺陷/工单日志/基础代码缺陷/
--       审计与变更日志/LLM 调用日志/令牌黑名单。
-- 用法：mysql -uroot -p traceguard < scripts/demo-reset.sql
--       或容器内：docker exec -i traceguard-mysql mysql -u<app> -p<pwd> traceguard < scripts/demo-reset.sql
-- 注意：逻辑删除表（tg_project 等）直接物理 DELETE，演示环境无保留诉求；
--       tg_audit_log 哈希链一并清空（链式校验从空表重新起始，不影响演示）。
-- =====================================================================
USE traceguard;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE tg_consistency_result;
TRUNCATE TABLE tg_defect;
TRUNCATE TABLE tg_code_defect;
TRUNCATE TABLE tg_code_unit;
TRUNCATE TABLE tg_formal_specification;
TRUNCATE TABLE tg_requirement;
TRUNCATE TABLE tg_analysis_task;
TRUNCATE TABLE tg_project;
TRUNCATE TABLE tg_data_change_log;
TRUNCATE TABLE tg_llm_call_log;
TRUNCATE TABLE tg_token_blacklist;
TRUNCATE TABLE tg_audit_log;
SET FOREIGN_KEY_CHECKS = 1;

SELECT '演示数据已复位：业务数据清空，账号/配置/模板保留。' AS result;
