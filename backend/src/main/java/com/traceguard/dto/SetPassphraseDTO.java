package com.traceguard.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** CQ-03：设置备份口令请求 DTO */
@Data
public class SetPassphraseDTO {

    @NotBlank(message = "备份口令不能为空")
    @Size(min = 8, message = "备份口令长度不能少于8位")
    private String passphrase;
}
