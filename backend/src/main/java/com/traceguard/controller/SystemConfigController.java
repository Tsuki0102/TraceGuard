package com.traceguard.controller;

import com.traceguard.entity.SystemConfig;
import com.traceguard.service.SystemConfigService;
import com.traceguard.common.Result;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统配置接口（AUD-07，GAP-002 需求解析规则可视化配置）。仅管理员可写。
 */
@RestController
@RequestMapping("/system/config")
@Api(tags = "系统配置")
public class SystemConfigController {

    @Autowired
    private SystemConfigService systemConfigService;

    /** SEC-04：统一管理员校验（对齐 BackupController 模式），未通过返回 403 */
    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作系统配置");
        }
        return null;
    }

    @ApiOperation(value = "查询全部系统配置", notes = "管理后台展示用；仅管理员（SEC-04）")
    @GetMapping("/list")
    public Result<List<SystemConfig>> list() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(systemConfigService.listAll());
    }

    @ApiOperation(value = "按 key 查询配置", notes = "返回原始 JSON 值；仅管理员（SEC-04）")
    @GetMapping("/{key}")
    public Result<SystemConfig> get(@PathVariable String key) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(systemConfigService.getByKey(key));
    }

    /** P1-4：规则缺陷信号权重（全量信号 -> 权重，阈值实验室调参台用；仅管理员 SEC-04） */
    @ApiOperation(value = "查询规则信号权重", notes = "返回全量信号的当前生效权重（已合并内置默认）；仅管理员（SEC-04）")
    @GetMapping("/risk-weights")
    public Result<Map<String, Double>> riskWeights() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(systemConfigService.getRiskWeights());
    }

    @ApiOperation(value = "保存/更新配置（管理员）", notes = "写入后热更新到运行期规则（req_parse_rules 立即生效）")
    @PutMapping("/{key}")
    public Result<SystemConfig> save(@PathVariable String key,
                                     @RequestParam String value,
                                     @RequestParam(required = false) String description) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        String operator = UserContext.getUsername();
        SystemConfig cfg = systemConfigService.save(key, value, description, operator);
        return Result.success(cfg);
    }
}
