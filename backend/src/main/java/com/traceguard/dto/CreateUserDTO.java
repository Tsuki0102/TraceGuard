package com.traceguard.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** CQ-03：管理员创建用户请求 DTO（密码复杂度与 UserService 单点校验一致） */
@Data
public class CreateUserDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度需在3~50之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, message = "密码长度不能少于8位")
    @Pattern(regexp = ".*[a-z].*", message = "密码需包含小写字母")
    @Pattern(regexp = ".*[A-Z].*", message = "密码需包含大写字母")
    @Pattern(regexp = ".*[0-9].*", message = "密码需包含数字")
    private String password;

    private String realName;

    private String email;

    private String role;
}
