package com.traceguard.service;

import com.traceguard.util.BackupCryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackupService 单元测试（TST-03 补齐）：
 * 覆盖 SEC-13 备份口令派生/验证/加密持久化/重启恢复、备份文件加密-解密往返（走服务内加密链路）、
 * 文件名校验防路径穿越、备份列表过滤排序、频率校验、保留份数清理。
 * 纯文件系统 + 加密工具，不依赖真实 mysqldump/mysql 进程（createBackup/restoreBackup 的进程链路
 * 属 DB 环境依赖项，见 TST-03 说明）。
 */
@DisplayName("备份服务单元测试")
class BackupServiceTest {

    private BackupService backupService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        backupService = new BackupService();
        ReflectionTestUtils.setField(backupService, "backupPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(backupService, "encryptKey", "unit-test-encrypt-key");
        ReflectionTestUtils.setField(backupService, "retentionCount", 3);
        ReflectionTestUtils.setField(backupService, "dbName", "traceguard");
        ReflectionTestUtils.setField(backupService, "dbUsername", "root");
        ReflectionTestUtils.setField(backupService, "dbPassword", "");
        ReflectionTestUtils.setField(backupService, "mysqlBinPath", "");
    }

    // ---- SEC-13：备份口令派生、验证与持久化 ----

    @Test
    @DisplayName("setPassphrase 拒绝少于 8 位的口令")
    void setPassphraseRejectsTooShort() {
        assertThatThrownBy(() -> backupService.setPassphrase("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("少于 8 位");
    }

    @Test
    @DisplayName("setPassphrase 后正确口令通过、错误口令拒绝")
    void setPassphraseVerifyRoundTrip() {
        backupService.setPassphrase("TraceGuard-2026!");

        assertThat(backupService.verifyPassphrase("TraceGuard-2026!")).isTrue();
        assertThat(backupService.verifyPassphrase("wrong-passphrase")).isFalse();
        assertThat(backupService.hasPassphrase()).isTrue();
    }

    @Test
    @DisplayName("口令派生密钥加密落盘且重启后可恢复（SEC-13）")
    void passphrasePersistedAndReloadedAfterRestart() throws Exception {
        backupService.setPassphrase("TraceGuard-2026!");

        // 持久化文件存在且为加密文本（不含明文口令）
        File keyFile = new File(tempDir.toFile(), ".passphrase.key");
        assertThat(keyFile).exists();
        String content = Files.readString(keyFile.toPath());
        assertThat(content).doesNotContain("TraceGuard-2026!");
        assertThat(content).startsWith("enc:v1:");

        // 模拟重启：新实例从加密文件恢复口令
        BackupService fresh = new BackupService();
        ReflectionTestUtils.setField(fresh, "backupPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(fresh, "encryptKey", "unit-test-encrypt-key");
        fresh.loadPassphrase();

        assertThat(fresh.verifyPassphrase("TraceGuard-2026!")).isTrue();
    }

    @Test
    @DisplayName("配置加密密钥轮换后口令无法恢复（回退配置密钥兜底）")
    void passphraseUnrecoverableWithDifferentEncryptKey() {
        backupService.setPassphrase("TraceGuard-2026!");

        BackupService fresh = new BackupService();
        ReflectionTestUtils.setField(fresh, "backupPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(fresh, "encryptKey", "rotated-key");
        fresh.loadPassphrase();

        assertThat(fresh.verifyPassphrase("TraceGuard-2026!")).isFalse();
    }

    @Test
    @DisplayName("未设置口令时 hasPassphrase 为 false")
    void hasPassphraseFalseInitially() {
        assertThat(backupService.hasPassphrase()).isFalse();
    }

    // ---- 备份文件加密-解密往返（服务内加密链路） ----

    @Test
    @DisplayName("配置密钥加密的备份文件可解密还原且落盘无明文")
    void encryptDecryptFileRoundTripWithConfigKey() throws Exception {
        File plain = tempDir.resolve("plain.sql").toFile();
        Files.write(plain.toPath(), "INSERT INTO tg_user(id) VALUES (1);".getBytes(StandardCharsets.UTF_8));
        File enc = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        File dec = tempDir.resolve("dec.sql").toFile();

        BackupCryptoUtil.encrypt(plain, enc, "unit-test-encrypt-key");

        // 加密文件为二进制密文：按字节校验不含明文内容（readString 会因非法 UTF-8 抛 MalformedInput）
        byte[] encBytes = Files.readAllBytes(enc.toPath());
        assertThat(new String(encBytes, StandardCharsets.ISO_8859_1)).doesNotContain("INSERT INTO");

        BackupCryptoUtil.decrypt(enc, dec, "unit-test-encrypt-key");
        assertThat(Files.readString(dec.toPath())).isEqualTo("INSERT INTO tg_user(id) VALUES (1);");
    }

    @Test
    @DisplayName("口令派生密钥（优先于配置密钥）加密的备份文件可解密还原")
    void encryptDecryptViaServiceWithPassphrase() throws Exception {
        backupService.setPassphrase("TraceGuard-2026!");
        File plain = tempDir.resolve("plain.sql").toFile();
        Files.write(plain.toPath(), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        File enc = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        File dec = tempDir.resolve("dec.sql").toFile();

        ReflectionTestUtils.invokeMethod(backupService, "encryptFile", plain, enc);
        ReflectionTestUtils.invokeMethod(backupService, "decryptFile", enc, dec);

        assertThat(Files.readString(dec.toPath())).isEqualTo("SELECT 1;");
    }

    @Test
    @DisplayName("错误密钥解密被 GCM 认证拒绝")
    void decryptFailsWithWrongKey() throws Exception {
        File plain = tempDir.resolve("plain.sql").toFile();
        Files.write(plain.toPath(), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        File enc = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        File dec = tempDir.resolve("dec.sql").toFile();

        BackupCryptoUtil.encrypt(plain, enc, "unit-test-encrypt-key");

        assertThatThrownBy(() -> BackupCryptoUtil.decrypt(enc, dec, "wrong-key"))
                .isInstanceOf(IOException.class);
    }

    // ---- 文件名校验（防路径穿越） ----

    @Test
    @DisplayName("restoreBackup 拒绝非法备份文件名")
    void restoreBackupRejectsInvalidFilename() {
        assertThatThrownBy(() -> backupService.restoreBackup("../../etc/passwd"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("非法的备份文件名");
        assertThatThrownBy(() -> backupService.restoreBackup("foo.sql"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("非法的备份文件名");
    }

    @Test
    @DisplayName("getBackupFile 合法文件名且文件存在时返回")
    void getBackupFileValidates() throws Exception {
        File f = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertThat(backupService.getBackupFile("backup_20260101_000000.sql.enc")).isEqualTo(f);
    }

    @Test
    @DisplayName("deleteBackup 文件不存在时抛错")
    void deleteBackupMissingFileFails() {
        assertThatThrownBy(() -> backupService.deleteBackup("backup_20260101_000000.sql.enc"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("备份文件不存在");
    }

    @Test
    @DisplayName("deleteBackup 删除存在的备份")
    void deleteBackupSuccess() throws Exception {
        File f = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        backupService.deleteBackup("backup_20260101_000000.sql.enc");

        assertThat(f).doesNotExist();
    }

    // ---- 备份列表 / 频率 / 保留清理 ----

    @Test
    @DisplayName("listBackups 仅返回备份文件并按时间倒序")
    void listBackupsFiltersAndSortsDesc() throws Exception {
        File old = tempDir.resolve("backup_20260101_000000.sql.enc").toFile();
        File newer = tempDir.resolve("backup_20260102_000000.sql.enc").toFile();
        File tmp = tempDir.resolve("tmp_dump_123.sql").toFile();
        Files.write(old.toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(10);
        Files.write(newer.toPath(), "bb".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.toPath(), "c".getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> list = backupService.listBackups();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("filename")).isEqualTo("backup_20260102_000000.sql.enc");
        assertThat(list.get(0).get("encrypted")).isEqualTo(true);
        assertThat(list.get(0).get("size")).isEqualTo(2L);
        assertThat(list.get(1).get("filename")).isEqualTo("backup_20260101_000000.sql.enc");
    }

    @Test
    @DisplayName("无备份时 shouldBackupNow 返回 true")
    void shouldBackupNowWhenNoBackups() {
        Boolean result = ReflectionTestUtils.invokeMethod(backupService, "shouldBackupNow");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("setBackupFrequency 仅接受 daily/weekly/monthly")
    void setBackupFrequencyValidates() {
        backupService.setBackupFrequency("weekly");
        assertThat(backupService.getBackupFrequency()).isEqualTo("weekly");
        backupService.setBackupFrequency("monthly");
        assertThat(backupService.getBackupFrequency()).isEqualTo("monthly");

        assertThatThrownBy(() -> backupService.setBackupFrequency("hourly"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    @DisplayName("cleanExpiredBackups 超出保留份数时删除最旧备份")
    void cleanExpiredBackupsDeletesOldestBeyondRetention() throws Exception {
        for (int i = 1; i <= 5; i++) {
            File f = tempDir.resolve(String.format("backup_202601%02d_000000.sql.enc", i)).toFile();
            Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
            if (i < 5) {
                Thread.sleep(5);
            }
        }

        ReflectionTestUtils.invokeMethod(backupService, "cleanExpiredBackups");

        File[] remaining = tempDir.toFile().listFiles((d, n) -> n.startsWith("backup_"));
        assertThat(remaining).hasSize(3);
    }
}
