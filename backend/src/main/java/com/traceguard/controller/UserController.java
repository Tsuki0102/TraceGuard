package com.traceguard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.common.Result;
import com.traceguard.dto.CreateUserDTO;
import com.traceguard.dto.ResetPasswordDTO;
import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import com.traceguard.service.DataChangeLogService;
import com.traceguard.service.UserService;
import com.traceguard.util.UserContext;

import javax.validation.Valid;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理接口（仅管理员可用）
 */
@RestController
@RequestMapping("/user")
@Api(tags = "02-用户管理")
public class UserController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private DataChangeLogService dataChangeLogService;

    @Autowired
    private UserService userService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 管理员校验，非管理员返回错误Result */
    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作用户管理");
        }
        return null;
    }

    /** 分页查询用户列表 */
    @ApiOperation(value = "分页查询用户列表", notes = "仅管理员可用；支持按用户名/姓名关键字模糊搜索，按创建时间倒序；返回记录不含密码")
    @GetMapping("/page")
    public Result<Map<String, Object>> page(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) String keyword) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        Page<User> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(User::getUsername, keyword.trim())
                   .or().like(User::getRealName, keyword.trim());
        }
        wrapper.orderByDesc(User::getCreateTime);
        Page<User> result = userMapper.selectPage(page, wrapper);
        result.getRecords().forEach(u -> u.setPassword(null));
        Map<String, Object> data = new HashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return Result.success(data);
    }

    /** 新增用户 */
    @ApiOperation(value = "新增用户", notes = "仅管理员可用；用户名必须唯一；密码须至少8位且同时包含大写字母、小写字母和数字（5.2.2 访问安全）；未指定角色时默认为 user")
    @PostMapping("/create")
    public Result<User> create(@Valid @RequestBody CreateUserDTO dto) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        // CQ-07：密码复杂度校验收敛到 UserService 单点实现（异常由全局处理器映射 400 业务消息）；CQ-03：参数经 @Valid 校验
        userService.validatePasswordComplexity(dto.getPassword());
        LambdaQueryWrapper<User> exists = new LambdaQueryWrapper<>();
        exists.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(exists) > 0) {
            return Result.error("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole() != null ? dto.getRole() : "user");
        user.setCreateTime(LocalDateTime.now());
        // 4.6 整改（GAP-027 闭环）：新建用户置强制改密标志，首次登录后必须修改密码方可使用业务功能
        user.setMustChangePassword(true);
        user.setPasswordUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        // 2.8 整改：记录用户创建快照
        Map<String, String> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("username", user.getUsername());
        snapshot.put("realName", user.getRealName());
        snapshot.put("role", user.getRole());
        snapshot.put("email", user.getEmail());
        dataChangeLogService.recordCreate("user", String.valueOf(user.getId()), null, snapshot);
        user.setPassword(null);
        return Result.success(user);
    }

    /** 编辑用户信息（不含密码） */
    @ApiOperation(value = "编辑用户信息", notes = "仅管理员可用；仅更新姓名、角色、邮箱，不涉及密码字段")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody User user) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        User db = userMapper.selectById(user.getId());
        if (db == null) return Result.error("用户不存在");
        User update = new User();
        update.setId(user.getId());
        update.setRealName(user.getRealName());
        update.setRole(user.getRole());
        update.setEmail(user.getEmail());
        userMapper.updateById(update);
        // 2.8 整改：字段级变更日志（对比旧值与新值）
        Map<String, String> oldMap = new java.util.LinkedHashMap<>();
        oldMap.put("realName", db.getRealName());
        oldMap.put("role", db.getRole());
        oldMap.put("email", db.getEmail());
        Map<String, String> newMap = new java.util.LinkedHashMap<>();
        newMap.put("realName", user.getRealName());
        newMap.put("role", user.getRole());
        newMap.put("email", user.getEmail());
        dataChangeLogService.recordFieldChanges("user", String.valueOf(user.getId()), null,
                oldMap, newMap, java.util.Arrays.asList("realName", "role", "email"));
        return Result.success();
    }

    /** 重置用户密码 */
    @ApiOperation(value = "重置用户密码", notes = "仅管理员可用；新密码须满足复杂度规则：至少8位且同时包含大写字母、小写字母和数字")
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        Long userId = dto.getUserId();
        String newPassword = dto.getNewPassword();
        // CQ-07：密码复杂度校验收敛到 UserService 单点实现；CQ-03：参数经 @Valid 校验
        userService.validatePasswordComplexity(newPassword);
        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        // 4.6 整改（GAP-027 闭环）：管理员重置密码后，要求用户下次登录强制改密
        update.setMustChangePassword(true);
        update.setPasswordUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        return Result.success();
    }

    /** 删除用户（不可删除自己与内置admin） */
    @ApiOperation(value = "删除用户", notes = "仅管理员可用；不允许删除当前登录账号及内置 admin 账号")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        if (id.equals(UserContext.getUserId())) {
            return Result.error("不能删除当前登录账号");
        }
        User db = userMapper.selectById(id);
        if (db == null) return Result.error("用户不存在");
        if ("admin".equals(db.getUsername())) {
            return Result.error("内置管理员账号不允许删除");
        }
        userMapper.deleteById(id);
        // 2.8 整改：记录用户删除
        dataChangeLogService.recordDelete("user", String.valueOf(id), null);
        return Result.success();
    }
}
