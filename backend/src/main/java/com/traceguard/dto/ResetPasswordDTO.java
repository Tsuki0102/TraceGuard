package com.traceguard.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** CQ-03：管理员重置密码请求 DTO */
@Data
public class ResetPasswordDTO {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, message = "新密码长度不能少于8位")
    @Pattern(regexp = ".*[a-z].*", message = "新密码需包含小写字母")
    @Pattern(regexp = ".*[A-Z].*", message = "新密码需包含大写字母")
    @Pattern(regexp = ".*[0-9].*", message = "新密码需包含数字")
    private String newPassword;
}
