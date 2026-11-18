package edu.fzrj.metrics.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 当前登录用户自助修改密码。 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度必须为 8–64 位")
    private String password;

    @NotBlank(message = "确认新密码不能为空")
    @Size(min = 8, max = 64, message = "确认新密码长度必须为 8–64 位")
    private String confirmPassword;
}
