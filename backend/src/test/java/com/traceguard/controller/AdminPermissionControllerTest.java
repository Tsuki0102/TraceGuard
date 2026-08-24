package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.dto.SetPassphraseDTO;
import com.traceguard.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TST-02：权限敏感 Controller 的非管理员 403 校验测试。
 *
 * 覆盖 SEC-04 整改涉及的管理员接口（SystemConfig/Llm/Backup/Security）：
 * 普通用户（UserContext 非 admin）调用时必须在触碰业务依赖前返回 403。
 * 采用纯 Mockito 风格直接 new Controller（checkAdmin 在依赖使用前返回，
 * 无需注入 mock），与 IntegrationControllerTest 同一轻量模式，不依赖 Spring 上下文。
 */
@DisplayName("TST-02 权限敏感 Controller 非管理员 403 校验")
class AdminPermissionControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAsNormalUser() {
        UserContext.set(99L, "normal-user", "user");
    }

    // ============ SystemConfigController（SEC-04：list/get/save 均需管理员） ============

    @Test
    @DisplayName("SystemConfig list/get/save：非管理员返回 403")
    void systemConfigRequiresAdmin() {
        loginAsNormalUser();
        SystemConfigController c = new SystemConfigController();
        assertThat(c.list().getCode()).isEqualTo(403);
        assertThat(c.get("req_parse_rules").getCode()).isEqualTo(403);
        assertThat(c.save("k", "v", null).getCode()).isEqualTo(403);
    }

    // ============ LlmController（SEC-04：status/test/explain/config 需管理员） ============

    @Test
    @DisplayName("Llm status/test/explain/config/saveConfig：非管理员返回 403")
    void llmRequiresAdmin() {
        loginAsNormalUser();
        LlmController c = new LlmController();
        assertThat(c.status().getCode()).isEqualTo(403);
        assertThat(c.test(null).getCode()).isEqualTo(403);
        assertThat(c.explainDefect(Collections.emptyMap()).getCode()).isEqualTo(403);
        assertThat(c.config().getCode()).isEqualTo(403);
        assertThat(c.saveConfig(Collections.emptyMap()).getCode()).isEqualTo(403);
    }

    // ============ BackupController（SEC-04/13：备份与口令操作需管理员） ============

    @Test
    @DisplayName("Backup create/config/setPassphrase：非管理员返回 403")
    void backupRequiresAdmin() throws Exception {
        loginAsNormalUser();
        BackupController c = new BackupController();
        assertThat(c.createBackup().getCode()).isEqualTo(403);
        assertThat(c.getConfig().getCode()).isEqualTo(403);
        SetPassphraseDTO dto = new SetPassphraseDTO();
        dto.setPassphrase("Abcd1234");
        assertThat(c.setPassphrase(dto).getCode()).isEqualTo(403);
        assertThat(c.verifyPassphrase(Collections.emptyMap()).getCode()).isEqualTo(403);
        assertThat(c.updateConfig(Collections.singletonMap("frequency", "daily")).getCode()).isEqualTo(403);
    }

    // ============ SecurityController（GAP-028 密钥轮换需管理员） ============

    @Test
    @DisplayName("Security rotateKeys：非管理员返回 403")
    void securityRequiresAdmin() throws Exception {
        loginAsNormalUser();
        SecurityController c = new SecurityController();
        Map<String, String> body = new java.util.HashMap<>();
        body.put("oldKey", "old");
        body.put("newKey", "new");
        Result<?> r = c.rotateKeys(body);
        assertThat(r.getCode()).isEqualTo(403);
    }

    // ============ UserController（用户管理需管理员） ============

    @Test
    @DisplayName("User page：非管理员返回 403")
    void userManagementRequiresAdmin() {
        loginAsNormalUser();
        UserController c = new UserController();
        assertThat(c.page(1, 10, null).getCode()).isEqualTo(403);
    }
}
