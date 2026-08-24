package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.service.LlmService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 大模型接口（GAP-021 重构）
 * 保留 /llm/status、/llm/test、/llm/explain-defect；新增配置读写（GET/PUT /llm/config，admin）
 * 与按 provider 连通测试。
 */
@RestController
@RequestMapping("/llm")
@Api(tags = "10-大模型辅助")
public class LlmController {

    @Autowired
    private LlmService llmService;

    /** SEC-04：统一管理员校验（LLM 调用涉配额/费用，全部接口仅管理员） */
    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作大模型配置与调用");
        }
        return null;
    }

    /** 查询大模型配置状态（脱敏：api_key 仅尾 4 位）；仅管理员（SEC-04） */
    @ApiOperation(value = "查询大模型配置状态", notes = "返回启用状态、provider 配置（脱敏）与路由表；仅管理员")
    @GetMapping("/status")
    public Result status() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(llmService.getRuntimeConfig());
    }

    /** 连通性测试（默认按 code-explain 环节路由）；仅管理员（SEC-04，防配额滥用） */
    @ApiOperation(value = "大模型连通性测试", notes = "未启用时返回错误；可按 provider 参数指定测试目标；仅管理员")
    @PostMapping("/test")
    public Result test(@RequestParam(required = false) String provider) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        if (!llmService.isEnabled()) {
            return Result.error("大模型未启用，请在配置中设置 traceguard.llm.enabled=true 与 api-key");
        }
        if (provider != null && !provider.isEmpty()) {
            Map<String, Object> result = llmService.testProviderConnection(provider);
            return Boolean.TRUE.equals(result.get("success"))
                    ? Result.success(result.get("reply")) : Result.error(String.valueOf(result.get("reply")));
        }
        String reply = llmService.testConnection();
        return reply != null ? Result.success(reply) : Result.error("调用失败，请检查网络与API密钥");
    }

    /** 缺陷智能解释；仅管理员（SEC-04，防配额滥用） */
    @ApiOperation(value = "缺陷智能解释", notes = "未启用时返回错误；请求体传 requirementText、codeSnippet、defectType 字段；仅管理员")
    @PostMapping("/explain-defect")
    public Result explainDefect(@RequestBody Map<String, String> body) {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        if (!llmService.isEnabled()) {
            return Result.error("大模型未启用");
        }
        Map<String, String> explanation = llmService.explainDefect(
        body.get("requirementText"), body.get("codeSnippet"), body.get("defectType"));
        return explanation != null ? Result.success(explanation) : Result.error("解释生成失败");
    }

    /** 查询运行时配置（GAP-021，admin；api_key 脱敏） */
    @ApiOperation(value = "查询运行时配置", notes = "admin 权限；返回 tg_llm_config 覆盖后的脱敏配置")
    @GetMapping("/config")
    public Result config() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(llmService.getRuntimeConfig());
    }

    /** 保存运行时配置（GAP-021，admin；api_key 加密落库） */
    @ApiOperation(value = "保存运行时配置", notes = "admin 权限；body: {enabled, providers:[{provider,baseUrl,apiKey}], routing:{stage:provider}, models:{stage:model}}")
    @PutMapping("/config")
    public Result saveConfig(@RequestBody Map<String, Object> body) {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "仅管理员可修改大模型配置");
        }
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> providers = (List<Map<String, String>>) body.get("providers");
        @SuppressWarnings("unchecked")
        Map<String, String> routing = (Map<String, String>) body.get("routing");
        @SuppressWarnings("unchecked")
        Map<String, String> models = (Map<String, String>) body.get("models");
        llmService.saveRuntimeConfig(enabled, providers, routing, models);
        return Result.success("大模型配置已保存");
    }
}
