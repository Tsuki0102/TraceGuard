package com.traceguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP-027：ConfigAuditor 启动自检测试
 * 验证点（设计 3.8.11）：
 * 1. 默认密钥 + reject=true -> 拒绝启动，异常信息含配置项名/环境变量名、不含密钥值
 * 2. 默认密钥 + reject=false -> 仅告警不抛异常
 * 3. 自定义密钥 -> 无告警无异常
 * 4. SEC-02/03：prod JWT 默认回退值、backup 默认值同样被检测
 */
@DisplayName("GAP-027 ConfigAuditor 启动自检")
class ConfigAuditorTest {

    private static final String DEFAULT_JWT = "TraceGuard-JWT-Secret-Key-2026-For-Requirement-Code-Consistency-System-Must-Be-Long";
    private static final String DEFAULT_PROD_JWT = "TraceGuard-Prod-JWT-Secret-Key-Please-Change-Me-In-Production-Env";
    private static final String DEFAULT_STORAGE = "TraceGuard#2026!UploadSecret";
    private static final String DEFAULT_LLM = "TraceGuard#2026!LlmConfigSecret";
    private static final String DEFAULT_BACKUP = "TraceGuard#2024!BackupSecret";

    /** 模拟 Environment 提供配置项值（prod profile，使 reject 模式生效） */
    private ConfigAuditor buildAuditor(String jwt, String storage, String llm, String backup, boolean reject) {
        ConfigAuditor auditor = new ConfigAuditor();
        Environment env = mock(Environment.class);
        when(env.getProperty("traceguard.jwt.secret", "")).thenReturn(jwt);
        when(env.getProperty("traceguard.storage.encrypt-key", "")).thenReturn(storage);
        when(env.getProperty("traceguard.llm.config-encrypt-key", "")).thenReturn(llm);
        when(env.getProperty("traceguard.backup.encrypt-key", "")).thenReturn(backup);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        ReflectionTestUtils.setField(auditor, "environment", env);
        ReflectionTestUtils.setField(auditor, "rejectDefaultSecrets", reject);
        return auditor;
    }

    @Test
    @DisplayName("默认密钥 + reject=true -> 拒绝启动，异常含项名不含密钥值")
    void defaultSecretsRejectThrows() {
        ConfigAuditor auditor = buildAuditor(DEFAULT_JWT, DEFAULT_STORAGE, DEFAULT_LLM, DEFAULT_BACKUP, true);
        assertThatThrownBy(() -> auditor.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("STORAGE_ENCRYPT_KEY")
                .hasMessageContaining("LLM_CONFIG_KEY")
                .hasMessageContaining("BACKUP_ENCRYPT_KEY")
                .hasMessageNotContaining(DEFAULT_JWT)
                .hasMessageNotContaining(DEFAULT_STORAGE)
                .hasMessageNotContaining(DEFAULT_LLM)
                .hasMessageNotContaining(DEFAULT_BACKUP);
    }

    @Test
    @DisplayName("默认密钥 + reject=false -> 仅告警不抛异常")
    void defaultSecretsWarnOnly() {
        ConfigAuditor auditor = buildAuditor(DEFAULT_JWT, DEFAULT_STORAGE, DEFAULT_LLM, DEFAULT_BACKUP, false);
        auditor.run(null); // 不应抛异常
    }

    @Test
    @DisplayName("自定义密钥 + reject=true -> 无告警无异常")
    void customSecretsNoWarning() {
        ConfigAuditor auditor = buildAuditor("my-strong-jwt-secret-1234567890", "my-storage-key", "my-llm-key", "my-backup-key", true);
        auditor.run(null); // 不应抛异常
    }

    @Test
    @DisplayName("仅单个默认密钥（如只漏配 JWT）+ reject=true -> 同样拒绝启动")
    void singleDefaultSecretRejectThrows() {
        ConfigAuditor auditor = buildAuditor(DEFAULT_JWT, "custom-storage", "custom-llm", "custom-backup", true);
        assertThatThrownBy(() -> auditor.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("prod 历史 JWT 默认回退值被检测（SEC-02）")
    void prodJwtDefaultFallbackRejected() {
        ConfigAuditor auditor = buildAuditor(DEFAULT_PROD_JWT, "custom-storage", "custom-llm", "custom-backup", true);
        assertThatThrownBy(() -> auditor.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("backup 默认密钥被检测（SEC-03）")
    void backupDefaultSecretRejected() {
        ConfigAuditor auditor = buildAuditor("custom-jwt-1234567890", "custom-storage", "custom-llm", DEFAULT_BACKUP, true);
        assertThatThrownBy(() -> auditor.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("BACKUP_ENCRYPT_KEY");
    }
}
