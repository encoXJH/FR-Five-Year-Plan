package edu.fzrj.metrics.security.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录 / 刷新响应（API 约定 16.1：含 user 用户信息块；刷新时 user 为 null）
 */
@Data
@AllArgsConstructor
public class TokenResponse {

    private String accessToken;
    private LocalDateTime accessTokenExpiresAt;
    private String refreshToken;
    private LocalDateTime refreshTokenExpiresAt;
    private TokenUser user;

    /** 登录用户信息（API 约定 3.4 / 16.1） */
    public record TokenUser(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            UnitRef unit,
            List<String> roles) {
    }

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }
}
