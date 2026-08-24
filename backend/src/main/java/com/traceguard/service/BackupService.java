package com.traceguard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.traceguard.util.BackupCryptoUtil;
import com.traceguard.util.LlmConfigCryptoUtil;
import com.traceguard.util.PassphraseUtil;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据备份与恢复服务：基于 mysqldump/mysql 命令行工具实现全库导出与恢复。
 * 备份文件经GZIP压缩+AES-256-GCM加密（扩展名.sql.enc），落盘数据不含明文（需求6.3 备份数据安全）。
 * 文件格式：[12字节IV][GCM密文(含认证标签)]，密钥由配置项经SHA-256派生（见BackupCryptoUtil）。
 */
@Service
public class BackupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Value("${spring.datasource.username:root}")
    private String dbUsername;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${traceguard.mysql.database:traceguard}")
    private String dbName;

    /** MySQL bin 目录路径（含 mysqldump.exe / mysql.exe） */
    @Value("${traceguard.mysql.bin-path:}")
    private String mysqlBinPath;

    @Value("${traceguard.storage.backup-path:./backups/}")
    private String backupPath;

    /** 自动备份保留份数，超出后删除最旧的备份 */
    @Value("${traceguard.backup.retention-count:10}")
    private int retentionCount;

    /** 备份加密密钥（生产环境应通过配置或环境变量注入独立密钥） */
    @Value("${traceguard.backup.encrypt-key:TraceGuard#2024!BackupSecret}")
    private String encryptKey;

    /** 用户设置的备份口令派生密钥（GAP-017，优先于配置密钥） */
    private String passphraseDerivedKey;

    /** 口令派生密钥持久化文件名（位于备份目录内，经配置密钥加密存储） */
    private static final String PASSPHRASE_FILE = ".passphrase.key";

    /** 自动备份频率（需求6.3.1 用户自定义：daily/weekly/monthly），支持运行时修改 */
    @Value("${traceguard.backup.frequency:daily}")
    private volatile String backupFrequency = "daily";

    /**
     * 创建全库备份：mysqldump导出到临时明文后立即压缩加密为.sql.enc，返回备份文件名
     */
    public String createBackup() throws IOException {
        File dir = new File(backupPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建备份目录: " + backupPath);
        }
        String filename = "backup_" + DTF.format(LocalDateTime.now()) + ".sql.enc";
        // 临时明文文件不得以backup_开头：否则命中isBackupFile过滤，
        // 进程中断残留时会被误当作最新备份（干扰备份间隔判断、挤占保留名额、混入备份列表）
        File plainFile = new File(dir, "tmp_dump_" + System.currentTimeMillis() + ".sql");
        File backupFile = new File(dir, filename);
        File defaultsFile = createTempDefaultsFile();
        try {
            // SEC-14：数据库密码经 --defaults-extra-file 传递，避免 -p{password} 出现在进程命令行
            ProcessBuilder pb = buildProcess("mysqldump", "--defaults-extra-file=" + defaultsFile.getAbsolutePath(), dbName);
            pb.redirectOutput(plainFile);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try {
                int code = process.waitFor();
                if (code != 0) {
                    throw new IOException("mysqldump 退出码: " + code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("mysqldump 被中断", e);
            }
            encryptFile(plainFile, backupFile);
            LOGGER.info("数据库备份完成（已加密压缩）: {}", backupFile.getAbsolutePath());
            return filename;
        } finally {
            // 明文临时文件与凭证文件无论成败都立即删除，避免明文/口令落盘残留
            Files.deleteIfExists(plainFile.toPath());
            Files.deleteIfExists(defaultsFile.toPath());
        }
    }

    /**
     * SEC-14：创建 MySQL --defaults-extra-file 临时凭证文件（用户/密码写入后置 600 权限），
     * 防止密码出现在进程命令行（ps/任务管理器可读）。调用方必须在 finally 中删除。
     */
    private File createTempDefaultsFile() throws IOException {
        File dir = new File(backupPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建备份目录: " + backupPath);
        }
        File f = new File(dir, ".mysql_creds_" + System.currentTimeMillis() + ".cnf");
        String content = "[client]\nuser=" + dbUsername + "\npassword=" + dbPassword + "\n";
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!isWin) {
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
        return f;
    }

    /**
     * GAP-017：设置备份口令（SEC-13 整改）。
     * 派生密钥记录（salt:iterations:key）仅保存在服务端（内存+加密持久化文件），
     * 不再返回前端；前端仅提交口令，杜绝"前端持有返回值即等价持有解密密钥"。
     */
    public void setPassphrase(String passphrase) {
        if (passphrase == null || passphrase.length() < 8) {
            throw new IllegalArgumentException("备份口令长度不能少于 8 位");
        }
        String derived = PassphraseUtil.deriveKey(passphrase);
        this.passphraseDerivedKey = derived;
        persistPassphrase(derived);
        LOGGER.info("已设置备份口令并加密持久化");
    }

    /**
     * GAP-017：校验备份口令是否匹配（先尝试内存，缺失时从加密文件恢复）
     */
    public boolean verifyPassphrase(String passphrase) {
        if (passphraseDerivedKey == null || passphraseDerivedKey.isEmpty()) {
            loadPassphrase();
        }
        if (passphraseDerivedKey == null || passphraseDerivedKey.isEmpty()) {
            return false;
        }
        return PassphraseUtil.verify(passphrase, passphraseDerivedKey);
    }

    /** 是否已设置备份口令（页面初始化展示用） */
    public boolean hasPassphrase() {
        if (passphraseDerivedKey == null || passphraseDerivedKey.isEmpty()) {
            loadPassphrase();
        }
        return passphraseDerivedKey != null && !passphraseDerivedKey.isEmpty();
    }

    /**
     * GAP-017：获取当前有效加密密钥（口令派生密钥优先，否则使用配置密钥兜底）
     */
    private String getEffectiveKey() {
        if (passphraseDerivedKey == null || passphraseDerivedKey.isEmpty()) {
            loadPassphrase();
        }
        if (passphraseDerivedKey != null && !passphraseDerivedKey.isEmpty()) {
            return PassphraseUtil.extractKey(passphraseDerivedKey);
        }
        return encryptKey;
    }

    /** SEC-13：口令派生密钥加密落盘（用配置加密密钥 AES-GCM 保护） */
    private void persistPassphrase(String derived) {
        try {
            File dir = new File(backupPath);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建备份目录: " + backupPath);
            }
            String encrypted = LlmConfigCryptoUtil.encrypt(derived, encryptKey);
            Files.write(new File(dir, PASSPHRASE_FILE).toPath(),
                    encrypted.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("备份口令持久化失败，口令仅本次运行有效", e);
        }
    }

    /** SEC-13：启动/惰性加载口令派生密钥（从加密文件解密恢复，避免重启丢失） */
    @PostConstruct
    public void loadPassphrase() {
        File file = new File(backupPath, PASSPHRASE_FILE);
        if (!file.exists() || file.length() == 0) {
            return;
        }
        try {
            String encrypted = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String derived = LlmConfigCryptoUtil.decrypt(encrypted, encryptKey);
            this.passphraseDerivedKey = derived;
            LOGGER.info("已从加密文件恢复备份口令");
        } catch (Exception e) {
            LOGGER.error("备份口令恢复失败（可能配置加密密钥已轮换），将使用系统配置密钥", e);
        }
    }

    private void encryptFile(File plain, File target) throws IOException {
        BackupCryptoUtil.encrypt(plain, target, getEffectiveKey());
    }

    private void decryptFile(File encrypted, File target) throws IOException {
        BackupCryptoUtil.decrypt(encrypted, target, getEffectiveKey());
    }

    /**
     * 定时自动备份（需求6.3）：每日凌晨4点触发，按用户配置的频率（daily/weekly/monthly）
     * 并结合最近一次备份时间判断是否真正执行，自动清理超出保留份数的旧备份
     */
    @Scheduled(cron = "${traceguard.backup.auto-cron:0 0 4 * * ?}")
    public void autoBackup() {
        if (!shouldBackupNow()) {
            LOGGER.info("当前备份频率为[{}]，未到备份时间，跳过本次自动备份", backupFrequency);
            return;
        }
        try {
            String filename = createBackup();
            cleanExpiredBackups();
            LOGGER.info("定时自动备份完成: {}", filename);
        } catch (Exception e) {
            LOGGER.error("定时自动备份失败: {}", e.getMessage(), e);
        }
    }

    /** 备份文件名过滤（兼容加密.sql.enc与历史明文.sql） */
    private boolean isBackupFile(String name) {
        return name.startsWith("backup_") && (name.endsWith(".sql.enc") || name.endsWith(".sql"));
    }

    /** 根据频率与最近一次备份文件的修改时间判断是否需要执行备份 */
    private boolean shouldBackupNow() {
        long intervalMs;
        switch (backupFrequency == null ? "daily" : backupFrequency) {
            case "weekly":
                intervalMs = 7L * 24 * 60 * 60 * 1000;
                break;
            case "monthly":
                intervalMs = 28L * 24 * 60 * 60 * 1000;
                break;
            case "daily":
            default:
                intervalMs = 24L * 60 * 60 * 1000;
                break;
        }
        File dir = new File(backupPath);
        File[] files = dir.listFiles((d, name) -> isBackupFile(name));
        if (files == null || files.length == 0) {
            return true;
        }
        long latest = 0;
        for (File f : files) {
            latest = Math.max(latest, f.lastModified());
        }
        return System.currentTimeMillis() - latest >= intervalMs;
    }

    public String getBackupFrequency() {
        return backupFrequency == null ? "daily" : backupFrequency;
    }

    /** 设置自动备份频率（daily/weekly/monthly），仅管理员可调用 */
    public void setBackupFrequency(String frequency) {
        if (!"daily".equals(frequency) && !"weekly".equals(frequency) && !"monthly".equals(frequency)) {
            throw new IllegalArgumentException("备份频率仅支持 daily/weekly/monthly");
        }
        this.backupFrequency = frequency;
        LOGGER.info("自动备份频率已调整为: {}", frequency);
    }

    /** 删除超出保留份数的最旧备份 */
    private void cleanExpiredBackups() {
        File dir = new File(backupPath);
        File[] files = dir.listFiles((d, name) -> isBackupFile(name));
        if (files == null || files.length <= retentionCount) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toDelete = files.length - retentionCount;
        for (int i = 0; i < toDelete; i++) {
            if (files[i].delete()) {
                LOGGER.info("已清理过期备份: {}", files[i].getName());
            }
        }
    }

    /**
     * 恢复指定备份：加密备份先解密解压为临时明文再导入，导入完成立即删除临时文件；
     * 兼容历史明文.sql备份直接导入
     */
    public void restoreBackup(String filename) throws IOException {
        File backupFile = validateBackupFile(filename);
        if (filename.endsWith(".sql.enc")) {
            File temp = new File(backupFile.getParentFile(),
                    "restore_temp_" + System.currentTimeMillis() + ".sql");
            try {
                decryptFile(backupFile, temp);
                doRestore(temp);
            } finally {
                Files.deleteIfExists(temp.toPath());
            }
        } else {
            doRestore(backupFile);
        }
        LOGGER.info("数据库已从备份恢复: {}", filename);
    }

    /** 执行SQL脚本导入（SEC-14：密码经 --defaults-extra-file 传递，避免命令行泄露） */
    private void doRestore(File sqlFile) throws IOException {
        File defaultsFile = createTempDefaultsFile();
        try {
            ProcessBuilder pb = buildProcess("mysql", "--defaults-extra-file=" + defaultsFile.getAbsolutePath(), dbName);
            pb.redirectInput(sqlFile);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try {
                int code = process.waitFor();
                if (code != 0) {
                    throw new IOException("mysql 恢复退出码: " + code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("mysql 恢复被中断", e);
            }
        } finally {
            Files.deleteIfExists(defaultsFile.toPath());
        }
    }

    /**
     * 列出所有备份文件信息（按时间倒序）
     */
    public List<Map<String, Object>> listBackups() {
        File dir = new File(backupPath);
        List<Map<String, Object>> list = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> isBackupFile(name));
        if (files == null) return list;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            Map<String, Object> item = new HashMap<>();
            item.put("filename", f.getName());
            item.put("size", f.length());
            item.put("sizeText", formatSize(f.length()));
            item.put("createTime", new Date(f.lastModified()));
            item.put("encrypted", f.getName().endsWith(".sql.enc"));
            list.add(item);
        }
        return list;
    }

    /**
     * 删除指定备份
     */
    public void deleteBackup(String filename) throws IOException {
        File backupFile = validateBackupFile(filename);
        if (!Files.deleteIfExists(backupFile.toPath())) {
            throw new IOException("备份文件不存在: " + filename);
        }
        LOGGER.info("备份已删除: {}", filename);
    }

    /**
     * 获取备份文件对象（下载用），带路径校验
     */
    public File getBackupFile(String filename) throws IOException {
        return validateBackupFile(filename);
    }

    /**
     * 构造进程，自动定位 mysql/mysqldump 可执行文件
     */
    private ProcessBuilder buildProcess(String tool, String... args) throws IOException {
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? tool + ".exe" : tool;
        File exe;
        if (mysqlBinPath != null && !mysqlBinPath.isEmpty()) {
            exe = new File(mysqlBinPath, exeName);
        } else {
            // 尝试从 PATH 查找
            exe = new File(exeName);
        }
        if (!exe.exists()) {
            throw new IOException("未找到 " + exeName + "，请配置 traceguard.mysql.bin-path 指向 MySQL bin 目录");
        }
        List<String> command = new ArrayList<>();
        command.add(exe.getAbsolutePath());
        Collections.addAll(command, args);
        return new ProcessBuilder(command);
    }

    /**
     * 校验文件名合法性，防止路径遍历攻击（兼容加密.sql.enc与历史明文.sql）
     */
    private File validateBackupFile(String filename) throws IOException {
        if (filename == null || !filename.matches("backup_\\d{8}_\\d{6}\\.sql(\\.enc)?")) {
            throw new IOException("非法的备份文件名: " + filename);
        }
        File file = new File(backupPath, filename);
        if (!file.exists()) {
            throw new IOException("备份文件不存在: " + filename);
        }
        return file;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
