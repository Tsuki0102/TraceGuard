package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.dto.SetPassphraseDTO;
import com.traceguard.service.BackupService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据备份与恢复接口（仅管理员，需求5.2.3权限控制）
 */
@RestController
@RequestMapping("/backup")
@Api(tags = "08-数据备份与恢复")
public class BackupController {

    @Autowired
    private BackupService backupService;

    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作数据备份");
        }
        return null;
    }

    @ApiOperation(value = "设置备份口令", notes = "GAP-017：设置备份口令用于派生加密密钥；SEC-13 起派生密钥不再回传前端；仅管理员")
    @PostMapping("/passphrase")
    public Result<Void> setPassphrase(@Valid @RequestBody SetPassphraseDTO dto) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return denied;
        // SEC-13：派生密钥仅服务端持有，前端仅提交口令；CQ-03：参数经 @Valid 校验（口令≥8位）
        backupService.setPassphrase(dto.getPassphrase());
        return Result.success("备份口令已设置", null);
    }

    @ApiOperation(value = "校验备份口令", notes = "GAP-017：校验备份口令是否正确；仅管理员")
    @PostMapping("/passphrase/verify")
    public Result<Boolean> verifyPassphrase(@RequestBody Map<String, String> body) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        String passphrase = body.get("passphrase");
        return Result.success(backupService.verifyPassphrase(passphrase));
    }

    @ApiOperation(value = "创建数据备份", notes = "仅管理员可操作（需求5.2.3权限控制）；成功返回备份文件名")
    @PostMapping("/create")
    public Result<String> createBackup() throws IOException {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        String filename = backupService.createBackup();
        return Result.success("备份成功", filename);
    }

    /** 查询自动备份频率配置与口令状态（需求6.3.1 用户自定义：daily/weekly/monthly） */
    @ApiOperation(value = "查询自动备份频率配置", notes = "需求6.3.1 用户自定义，取值 daily/weekly/monthly；仅管理员可查看")
    @GetMapping("/config")
    public Result<Map<String, String>> getConfig() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        Map<String, String> data = new HashMap<>();
        data.put("frequency", backupService.getBackupFrequency());
        data.put("passphraseSet", String.valueOf(backupService.hasPassphrase()));
        return Result.success(data);
    }

    /** 修改自动备份频率配置（需求6.3.1） */
    @ApiOperation(value = "修改自动备份频率配置", notes = "需求6.3.1，请求体传 frequency 字段（daily/weekly/monthly）；仅管理员可操作")
    @PutMapping("/config")
    public Result<Void> updateConfig(@RequestBody Map<String, String> body) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        backupService.setBackupFrequency(body.get("frequency"));
        return Result.success();
    }

    @ApiOperation(value = "查询备份文件列表", notes = "仅管理员可查看")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> listBackups() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(backupService.listBackups());
    }

    @ApiOperation(value = "恢复指定备份", notes = "仅管理员可操作；恢复成功后需重新登录")
    @PostMapping("/restore")
    public Result<String> restoreBackup(@RequestParam String filename) throws IOException {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        backupService.restoreBackup(filename);
        return Result.success("恢复成功，请重新登录", null);
    }

    @ApiOperation(value = "删除指定备份", notes = "仅管理员可操作，按备份文件名删除")
    @DeleteMapping("/{filename}")
    public Result<String> deleteBackup(@PathVariable String filename) throws IOException {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        backupService.deleteBackup(filename);
        return Result.success("删除成功", null);
    }

    @ApiOperation(value = "下载备份文件", notes = "仅管理员可下载（无权限返回403）；返回二进制附件文件流")
    @GetMapping("/download/{filename}")
    public ResponseEntity<FileSystemResource> downloadBackup(@PathVariable String filename) throws Exception {
        if (!UserContext.isAdmin()) {
            return ResponseEntity.status(403).build();
        }
        File file = backupService.getBackupFile(filename);
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }
}
