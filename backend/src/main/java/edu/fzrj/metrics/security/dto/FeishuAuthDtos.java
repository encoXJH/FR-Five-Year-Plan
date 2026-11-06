package edu.fzrj.metrics.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 飞书免登相关 DTO（API 约定 4.1 / 15.1）
 */
public final class FeishuAuthDtos {

    private FeishuAuthDtos() {
    }

    /** 飞书免登请求：code 为飞书 JSAPI / OAuth 的一次性授权码 */
    public record FeishuLoginRequest(@NotBlank(message = "code 不能为空") String code) {
    }

    /** 已绑定直接返回 Token；首次绑定时仅返回一次性票据，Token 字段为 null */
    public record FeishuLoginResponse(
            String accessToken,
            LocalDateTime accessTokenExpiresAt,
            String refreshToken,
            LocalDateTime refreshTokenExpiresAt,
            TokenResponse.TokenUser user,
            boolean bindRequired,
            String bindingTicket,
            LocalDateTime bindingTicketExpiresAt) {

        public static FeishuLoginResponse authenticated(TokenResponse token) {
            return new FeishuLoginResponse(token.getAccessToken(), token.getAccessTokenExpiresAt(),
                    token.getRefreshToken(), token.getRefreshTokenExpiresAt(), token.getUser(),
                    false, null, null);
        }

        public static FeishuLoginResponse bindingRequired(String ticket, LocalDateTime expiresAt) {
            return new FeishuLoginResponse(null, null, null, null, null,
                    true, ticket, expiresAt);
        }
    }

    /** 首次绑定：openId 等飞书身份只从服务端票据读取，不接受前端指定 */
    public record FeishuBindRequest(
            @NotBlank(message = "绑定票据不能为空") @Size(max = 128, message = "绑定票据长度不合法") String bindingTicket,
            @NotBlank(message = "姓名不能为空") @Size(max = 64, message = "姓名不超过 64 字") String name,
            @NotBlank(message = "工号不能为空") @Size(max = 64, message = "工号不超过 64 字") String employeeId) {
    }

    /** 浏览器 OAuth 授权跳转地址 */
    public record AuthorizeUrlView(String url) {
    }

    /** 飞书集成可用性（登录前公开可查；前端据此自动隐藏飞书入口） */
    public record FeishuConfigView(boolean enabled) {
    }
}
