package com.traceguard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * GAP-027：配置安全审计组件（启动自检）。
 *
 * 检测关键配置是否仍为默认值：
 * - prod profile 且 reject-default-secrets=true 时：检测到默认密钥则拒绝启动
 * - 其他情况：检测到默认密钥则 WARN 告警
 *
 * 安全原则：日志中只打印配置项名与环境变量名，绝不打印实际密钥值。
 */
@Component
@Slf4j
public class ConfigAuditor implements ApplicationRunner {

    @Autowired
    private Environment environment;

    @Value("${traceguard.security.reject-default-secrets:false}")
    private boolean rejectDefaultSecrets;

    // 默认密钥值（用于检测）。SEC-02/03：覆盖 dev 主配置默认值与 prod 历史默认回退值
    private static final Set<String> DEFAULT_SECRETS = new HashSet<>(Arrays.asList(
            "TraceGuard-JWT-Secret-Key-2026-For-Requirement-Code-Consistency-System-Must-Be-Long",
            "TraceGuard-Prod-JWT-Secret-Key-Please-Change-Me-In-Production-Env",
            "TraceGuard#2026!UploadSecret",
            "TraceGuard#2026!LlmConfigSecret",
            "TraceGuard#2024!BackupSecret"
    ));

    // 配置项与环境变量映射
    private static final String[][] SECRET_CONFIGS = {
            {"traceguard.jwt.secret", "JWT_SECRET"},
            {"traceguard.storage.encrypt-key", "STORAGE_ENCRYPT_KEY"},
            {"traceguard.llm.config-encrypt-key", "LLM_CONFIG_KEY"},
            {"traceguard.backup.encrypt-key", "BACKUP_ENCRYPT_KEY"}
    };

    @Override
    public void run(ApplicationArguments args) {
        log.info("[ConfigAuditor] 启动配置安全审计...");

        boolean isProd = isProdProfile();
        boolean shouldReject = isProd && rejectDefaultSecrets;

        log.info("[ConfigAuditor] 环境: {}, reject-default-secrets: {}, 拒绝启动模式: {}",
                isProd ? "prod" : "dev", rejectDefaultSecrets, shouldReject);

        boolean hasDefaultSecret = false;
        StringBuilder auditLog = new StringBuilder();

        for (String[] config : SECRET_CONFIGS) {
            String configKey = config[0];
            String envVar = config[1];
            String value = environment.getProperty(configKey, "");

            if (isDefaultSecret(value)) {
                hasDefaultSecret = true;
                String msg = String.format(
                        "配置项 [%s] 仍使用默认值，建议通过环境变量 [%s] 注入自定义密钥",
                        configKey, envVar);
                auditLog.append(msg).append("; ");

                if (shouldReject) {
                    log.error("[ConfigAuditor] 安全拒绝: {}", msg);
                } else {
                    log.warn("[ConfigAuditor] 安全警告: {}", msg);
                }
            }
        }

        if (!hasDefaultSecret) {
            log.info("[ConfigAuditor] 配置安全审计通过，未检测到默认密钥");
        } else {
            if (shouldReject) {
                throw new RuntimeException(
                        "[ConfigAuditor] 检测到生产环境使用默认密钥，拒绝启动。" +
                        "请设置环境变量: JWT_SECRET, STORAGE_ENCRYPT_KEY, LLM_CONFIG_KEY, BACKUP_ENCRYPT_KEY");
            } else {
                log.warn("[ConfigAuditor] 配置安全审计完成，存在警告项: {}", auditLog);
            }
        }
    }

    /**
     * 判断是否为默认密钥
     */
    private boolean isDefaultSecret(String value) {
        if (value == null || value.isEmpty()) {
            return false; // 空值视为已自定义（可能故意留空）
        }
        return DEFAULT_SECRETS.contains(value);
    }

    /**
     * 判断是否为 prod 环境
     */
    private boolean isProdProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return Arrays.asList(activeProfiles).contains("prod");
    }
}
