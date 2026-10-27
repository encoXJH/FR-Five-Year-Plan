package edu.fzrj.metrics.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 飞书免登相关 DTO（API 约定 4.1 / 15.1）
 */
public final class FeishuAuthDtos {

    private FeishuAuthDtos() {
    }

    /** 飞书免登请求：code 为飞书 JSAPI / OAuth 的一次性授权码 */
    public record FeishuLoginRequest(@NotBlank(message = "code 不能为空") String code) {
    }

    /** 浏览器 OAuth 授权跳转地址 */
    public record AuthorizeUrlView(String url) {
    }

    /** 飞书集成可用性（登录前公开可查；前端据此自动隐藏飞书入口） */
    public record FeishuConfigView(boolean enabled) {
    }
}
