package edu.fzrj.metrics.module.integration.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书集成配置（全部来自环境变量，见 .env.example；未配置 AppID/AppSecret 时整个集成视为不可用，
 * 免登返回 20006、消息推送置 SKIPPED、事件回调拒绝验签，不影响账号密码登录）。
 */
@Getter
@Component
public class FeishuConfig {

    /** 飞书开放平台 API 地址 */
    @Value("${app.feishu.base-url:https://open.feishu.cn}")
    private String baseUrl;

    /** 飞书账号授权页地址 */
    @Value("${app.feishu.accounts-url:https://accounts.feishu.cn}")
    private String accountsUrl;

    @Value("${app.feishu.app-id:}")
    private String appId;

    @Value("${app.feishu.app-secret:}")
    private String appSecret;

    /** 浏览器 OAuth 回调地址（飞书后台配置的重定向 URL） */
    @Value("${app.feishu.redirect-uri:}")
    private String redirectUri;

    /** 事件订阅 Encrypt Key（验签与报文解密） */
    @Value("${app.feishu.encrypt-key:}")
    private String encryptKey;

    /** 事件订阅 Verification Token */
    @Value("${app.feishu.verification-token:}")
    private String verificationToken;

    /** 平台对外访问基础地址（消息卡片链接拼接，如 https://metrics.example.com） */
    @Value("${app.feishu.app-base-url:}")
    private String appBaseUrl;

    /** 消息推送轮询开关（便携 / 测试环境可关） */
    @Value("${app.feishu.push-enabled:true}")
    private boolean pushEnabled;

    /** 推送单次批量上限 */
    @Value("${app.feishu.push-batch-size:50}")
    private int pushBatchSize;

    /** 飞书 API 连接超时（毫秒） */
    @Value("${app.feishu.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** 飞书 API 读取超时（毫秒） */
    @Value("${app.feishu.read-timeout-ms:5000}")
    private int readTimeoutMs;

    /** 是否已配置飞书应用凭证（未配置时集成整体不可用） */
    public boolean isConfigured() {
        return StringUtils.hasText(appId) && StringUtils.hasText(appSecret);
    }

    public boolean hasEncryptKey() {
        return StringUtils.hasText(encryptKey);
    }

    public boolean hasVerificationToken() {
        return StringUtils.hasText(verificationToken);
    }
}
