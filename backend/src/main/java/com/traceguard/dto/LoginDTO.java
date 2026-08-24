package com.traceguard.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** CQ-03：登录请求 DTO（Bean Validation，GlobalExceptionHandler 映射 400） */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
