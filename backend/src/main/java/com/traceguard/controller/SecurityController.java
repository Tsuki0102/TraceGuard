package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.task.KeyRotationRunner;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GAP-028：密钥轮换接口（仅管理员）
 */
@RestController
@RequestMapping("/security")
@Api(tags = "99-安全运维")
public class SecurityController {

    @Autowired
    private KeyRotationRunner keyRotationRunner;

    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作");
        }
        return null;
    }

    @ApiOperation(value = "执行密钥轮换", notes = "GAP-028：将上传文件与备份文件从旧密钥重加密到新密钥；仅管理员")
    @PostMapping("/rotate-keys")
    public Result<List<String>> rotateKeys(@RequestBody Map<String, String> body) throws Exception {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());

        String oldKey = body.get("oldKey");
        String newKey = body.get("newKey");
        // SEC-14：不回显内部异常，统一交全局异常处理器兜底
        List<String> result = keyRotationRunner.rotate(oldKey, newKey);
        return Result.success(result);
    }
}
