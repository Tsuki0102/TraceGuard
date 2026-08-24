package com.traceguard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * GAP-030：启动数据库自检组件。
 *
 * 仅 MySQL，启动时校验关键表/列存在性：
 * - dev 环境：缺表 WARN 并提示执行 init.sql
 * - prod 环境：缺表 REJECT 启动（避免带病上线）
 *
 * 自检查询超时 3s，超时按"通过但告警"处理，不阻塞启动。
 */
@Component
@Slf4j
public class StartupDbCheck implements ApplicationRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Value("${traceguard.db.check.mode:warn}")
    private String checkMode;

    @Value("${traceguard.db.check.timeout-ms:3000}")
    private int timeoutMs;

    @Autowired
    private com.traceguard.service.SystemConfigService systemConfigService;

    /**
     * 关键表清单（按 init.sql 核心表定义）
     */
    private static final List<String> REQUIRED_TABLES = Arrays.asList(
            "sys_user",
            "tg_project",
            "tg_requirement",
            "tg_code_unit",
            "tg_consistency_result",
            "tg_defect",
            "tg_analysis_task",
            "tg_formal_specification",
            "tg_report_template_config",
            "tg_llm_config"
    );

    /**
     * 关键列清单（格式：表名.列名）
     */
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "sys_user.username",
            "sys_user.password",
            "tg_project.project_name",
            "tg_project.requirement_file_path",
            "tg_project.code_project_path",
            "tg_requirement.original_text",
            "tg_requirement.constraint_rules",
            "tg_code_unit.class_name",
            "tg_code_unit.method_name",
            "tg_code_unit.code_content",
            "tg_defect.defect_type",
            "tg_defect.status"
    );

    @Override
    public void run(ApplicationArguments args) {
        boolean isProd = isProdProfile();
        String mode = isProd ? "reject" : checkMode;

        log.info("[StartupDbCheck] 启动数据库自检，环境: {}, 模式: {}",
                isProd ? "prod" : "dev", mode);

        try {
            // AUD-07：加载系统配置（含需求解析规则）到运行期持有者，使解析器使用最新配置
            try {
                systemConfigService.loadOnStartup();
            } catch (Exception e) {
                log.warn("[StartupDbCheck] 系统配置加载失败（使用默认规则）: {}", e.getMessage());
            }
            // 设置查询超时
            jdbcTemplate.setQueryTimeout(timeoutMs / 1000);

            // 检查表存在性
            List<String> missingTables = checkTables();
            // 检查列存在性
            List<String> missingColumns = checkColumns();

            if (missingTables.isEmpty() && missingColumns.isEmpty()) {
                log.info("[StartupDbCheck] 数据库结构检查通过，所有关键表/列存在");
            } else {
                // 结构不完整时不进入数据级校验（表可能不存在），仅按原逻辑告警/拒绝
                // 构建错误信息
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("数据库结构不完整：");
                if (!missingTables.isEmpty()) {
                    errorMsg.append("缺失表: ").append(missingTables).append("; ");
                }
                if (!missingColumns.isEmpty()) {
                    errorMsg.append("缺失列: ").append(missingColumns).append("; ");
                }
                errorMsg.append("请执行 resources/sql/init.sql 初始化数据库");

                if ("reject".equals(mode)) {
                    log.error("[StartupDbCheck] 拒绝启动: {}", errorMsg);
                    throw new RuntimeException("数据库结构检查失败: " + errorMsg);
                } else {
                    log.warn("[StartupDbCheck] 警告: {}", errorMsg);
                    return;
                }
            }

            // AUD-09：数据库结构通过后，执行数据级完整性校验与自动修复（不阻塞启动，仅告警）
            try {
                initDataIntegrity();
            } catch (Exception e) {
                log.warn("[StartupDbCheck] 数据级完整性校验异常（不影响启动）: {}", e.getMessage());
            }
            return;

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("数据库结构检查失败")) {
                throw e; // 已处理的拒绝启动异常
            }
            // 查询超时或其他异常，按"通过但告警"处理
            log.warn("[StartupDbCheck] 自检过程异常（超时或连接问题）: {}，按通过但告警处理", e.getMessage());
        }
    }

    /**
     * AUD-09：数据级完整性校验与自动修复。
     * 在「表/列结构完整」前提下执行，校验并清理跨表孤儿数据、修复中断的卡死任务；
     * 任何异常均不阻塞启动，仅记录告警。修复策略：删除孤儿子表行（下次分析自动重建）。
     */
    private void initDataIntegrity() {
        log.info("[StartupDbCheck] 开始数据级完整性校验与自动修复");

        // 1) 需求引用了不存在的项目 -> 孤儿需求（删除，后续重新解析重建）
        int orphanReq = deleteOrphans(
                "tg_requirement",
                "project_id",
                "SELECT id FROM tg_project");
        if (orphanReq > 0) log.warn("[StartupDbCheck] 已清理孤儿需求 {} 条（项目已不存在）", orphanReq);

        // 2) 形式化规约引用了不存在的需求 -> 孤儿规约
        int orphanSpec = deleteOrphans(
                "tg_formal_specification",
                "requirement_id",
                "SELECT id FROM tg_requirement");
        if (orphanSpec > 0) log.warn("[StartupDbCheck] 已清理孤儿形式化规约 {} 条", orphanSpec);

        // 3) 一致性结果引用了不存在的分析任务 -> 孤儿结果
        int orphanResult = deleteOrphans(
                "tg_consistency_result",
                "task_id",
                "SELECT id FROM tg_analysis_task");
        if (orphanResult > 0) log.warn("[StartupDbCheck] 已清理孤儿一致性结果 {} 条", orphanResult);

        // 4) 缺陷引用了不存在的分析任务 -> 孤儿缺陷
        int orphanDefect = deleteOrphans(
                "tg_defect",
                "task_id",
                "SELECT id FROM tg_analysis_task");
        if (orphanDefect > 0) log.warn("[StartupDbCheck] 已清理孤儿缺陷 {} 条", orphanDefect);

        // 5) 卡死的 running 任务（超过 24h 未完成）恢复为 failed，避免前端持续轮询无响应
        int recoveredTasks = recoverStuckRunningTasks();
        if (recoveredTasks > 0) log.warn("[StartupDbCheck] 已恢复卡死分析任务 {} 个（status->failed）", recoveredTasks);

        log.info("[StartupDbCheck] 数据级完整性校验与自动修复完成");
    }

    /**
     * 删除 childTable.childCol 不在 parentIds 子查询中的孤儿记录，返回删除行数。
     */
    private int deleteOrphans(String childTable, String childCol, String parentIdsSql) {
        try {
            String sql = String.format(
                    "DELETE FROM %s WHERE %s IS NOT NULL AND %s NOT IN (%s)",
                    childTable, childCol, childCol, parentIdsSql);
            return jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.warn("[StartupDbCheck] 孤儿清理失败 {}.{}: {}", childTable, childCol, e.getMessage());
            return 0;
        }
    }

    /**
     * 将 start_time 超过 24 小时、状态仍为 running 的分析任务标记为 failed（服务重启中断保护）。
     * 注意：status='interrupted' 为设计上可续跑状态，不在此处强制失败。
     */
    private int recoverStuckRunningTasks() {
        try {
            String sql = "UPDATE tg_analysis_task SET status='failed', "
                    + "error_message = CONCAT(IFNULL(error_message,''), ' [启动自检恢复：running 超时]'), "
                    + "end_time = NOW(), update_time = NOW() "
                    + "WHERE status='running' AND start_time < DATE_SUB(NOW(), INTERVAL 24 HOUR)";
            return jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.warn("[StartupDbCheck] 卡死任务恢复失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 检查关键表是否存在
     */
    private List<String> checkTables() {
        List<String> missing = new java.util.ArrayList<>();
        for (String table : REQUIRED_TABLES) {
            try {
                // 使用 information_schema 查询表存在性（MySQL 专用）
                String sql = "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?";
                Integer count = jdbcTemplate.queryForObject(sql, Integer.class, table);
                if (count == null || count == 0) {
                    missing.add(table);
                }
            } catch (Exception e) {
                // 查询异常视为表不存在
                missing.add(table);
            }
        }
        return missing;
    }

    /**
     * 检查关键列是否存在
     */
    private List<String> checkColumns() {
        List<String> missing = new java.util.ArrayList<>();
        for (String columnRef : REQUIRED_COLUMNS) {
            String[] parts = columnRef.split("\\.");
            if (parts.length != 2) continue;
            String table = parts[0];
            String column = parts[1];

            try {
                // 使用 information_schema 查询列存在性（MySQL 专用）
                String sql = "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
                Integer count = jdbcTemplate.queryForObject(sql, Integer.class, table, column);
                if (count == null || count == 0) {
                    missing.add(columnRef);
                }
            } catch (Exception e) {
                // 查询异常视为列不存在
                missing.add(columnRef);
            }
        }
        return missing;
    }

    /**
     * 判断是否为 prod 环境
     */
    private boolean isProdProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return Arrays.asList(activeProfiles).contains("prod");
    }
}
