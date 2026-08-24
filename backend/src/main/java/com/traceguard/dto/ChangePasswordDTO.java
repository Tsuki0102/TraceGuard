package com.traceguard.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * CQ-03：修改密码请求 DTO（Bean Validation 示例——关键接口参数校验收敛到声明式注解，
 * 由 GlobalExceptionHandler 映射 MethodArgumentNotValidException 为 400 业务消息）。
 * 密码复杂度规则与 UserService.validatePasswordComplexity 保持一致（5.2.2）。
 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, message = "新密码长度不能少于8位")
    @Pattern(regexp = ".*[a-z].*", message = "新密码需包含小写字母")
    @Pattern(regexp = ".*[A-Z].*", message = "新密码需包含大写字母")
    @Pattern(regexp = ".*[0-9].*", message = "新密码需包含数字")
    private String newPassword;
}
