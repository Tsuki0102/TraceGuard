package com.traceguard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.common.Result;
import com.traceguard.entity.UserPreference;
import com.traceguard.mapper.UserPreferenceMapper;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户界面偏好（个性化增强 BATCH-4）
 * 每用户一份 JSON 偏好包（主题/密度/工作台布局等），登录态读写；前端本地优先，后端做跨设备同步。
 */
@RestController
@RequestMapping("/preference")
@Api(tags = "01-用户偏好")
public class PreferenceController {

    @Autowired
    private UserPreferenceMapper userPreferenceMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ApiOperation(value = "读取当前用户偏好", notes = "无记录时返回空对象（前端回退本地默认）")
    @GetMapping
    public Result<Map<String, Object>> get() {
        Long userId = UserContext.getUserId();
        UserPreference pref = userPreferenceMapper.selectOne(
                new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        if (pref == null || pref.getPrefJson() == null || pref.getPrefJson().isEmpty()) {
            return Result.success(Map.of());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(pref.getPrefJson(), Map.class);
            return Result.success(data);
        } catch (Exception e) {
            return Result.success(Map.of());
        }
    }

    @ApiOperation(value = "保存当前用户偏好", notes = "整包覆盖式保存；body 为任意 JSON 对象（建议含 theme/accent/density/dashCards）")
    @PutMapping
    public Result<Void> save(@RequestBody Map<String, Object> pref) {
        Long userId = UserContext.getUserId();
        String json;
        try {
            json = objectMapper.writeValueAsString(pref == null ? Map.of() : pref);
        } catch (Exception e) {
            return Result.error(400, "偏好序列化失败");
        }
        UserPreference existing = userPreferenceMapper.selectOne(
                new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        if (existing == null) {
            UserPreference row = new UserPreference();
            row.setUserId(userId);
            row.setPrefJson(json);
            userPreferenceMapper.insert(row);
        } else {
            existing.setPrefJson(json);
            userPreferenceMapper.updateById(existing);
        }
        return Result.success();
    }
}
