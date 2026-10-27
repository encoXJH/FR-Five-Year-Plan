package edu.fzrj.metrics.module.integration.service.support;

import java.time.LocalDateTime;

/**
 * 飞书消息推送状态机（纯逻辑，便于单测；API 约定第 11 章：指数退避最多重试 3 次）。
 * 终态：SENT 已推送 / FAILED 推送失败 / SKIPPED 无需推送；中间态 PENDING。
 */
public final class FeishuPushRules {

    /** 最多尝试次数（首次 + 重试 2 次后仍失败则置 FAILED，即 retryCount 上限 3） */
    public static final int MAX_ATTEMPTS = 3;

    private FeishuPushRules() {
    }

    /** 一次推送尝试后的落库结果 */
    public record Outcome(String status, int retryCount, LocalDateTime nextRetryAt) {

        public boolean isTerminal() {
            return "SENT".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status);
        }
    }

    /**
     * 本次尝试后的状态转移。
     *
     * @param feishuConfigured 飞书是否已配置（未配置 → SKIPPED）
     * @param hasOpenId        接收人是否已绑定飞书（未绑定 → SKIPPED）
     * @param retryCount       当前已重试次数
     * @param success          本次发送是否成功
     * @param now              当前时间
     */
    public static Outcome afterAttempt(boolean feishuConfigured, boolean hasOpenId,
                                       int retryCount, boolean success, LocalDateTime now) {
        if (!feishuConfigured || !hasOpenId) {
            return new Outcome("SKIPPED", retryCount, null);
        }
        if (success) {
            return new Outcome("SENT", retryCount, null);
        }
        int nextCount = retryCount + 1;
        if (nextCount >= MAX_ATTEMPTS) {
            return new Outcome("FAILED", nextCount, null);
        }
        // 指数退避：第 1 次失败后 1 分钟，第 2 次失败后 2 分钟
        return new Outcome("PENDING", nextCount, now.plusMinutes(1L << (nextCount - 1)));
    }

    /** 是否到了可推送时间（nextRetryAt 为空表示立即可推送） */
    public static boolean isDue(LocalDateTime nextRetryAt, LocalDateTime now) {
        return nextRetryAt == null || !nextRetryAt.isAfter(now);
    }
}
