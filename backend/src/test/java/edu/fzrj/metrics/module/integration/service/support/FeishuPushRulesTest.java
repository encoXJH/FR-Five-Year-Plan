package edu.fzrj.metrics.module.integration.service.support;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书消息推送状态机纯逻辑单测（PENDING → SENT / FAILED / SKIPPED；指数退避最多 3 次）
 */
class FeishuPushRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0, 0);

    @Nested
    class Terminal {

        @Test
        void 发送成功置SENT且清空退避() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, true, 0, true, NOW);
            assertEquals("SENT", outcome.status());
            assertEquals(0, outcome.retryCount());
            assertNull(outcome.nextRetryAt());
            assertTrue(outcome.isTerminal());
        }

        @Test
        void 未配置飞书置SKIPPED() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(false, true, 0, false, NOW);
            assertEquals("SKIPPED", outcome.status());
            assertTrue(outcome.isTerminal());
        }

        @Test
        void 接收人未绑定飞书置SKIPPED() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, false, 0, false, NOW);
            assertEquals("SKIPPED", outcome.status());
            assertTrue(outcome.isTerminal());
        }
    }

    @Nested
    class Retry {

        @Test
        void 首次失败保持PENDING一分钟后重试() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, true, 0, false, NOW);
            assertEquals("PENDING", outcome.status());
            assertEquals(1, outcome.retryCount());
            assertEquals(NOW.plusMinutes(1), outcome.nextRetryAt());
            assertFalse(outcome.isTerminal());
        }

        @Test
        void 第二次失败两分钟后重试() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, true, 1, false, NOW);
            assertEquals("PENDING", outcome.status());
            assertEquals(2, outcome.retryCount());
            assertEquals(NOW.plusMinutes(2), outcome.nextRetryAt());
        }

        @Test
        void 第三次失败置FAILED不再重试() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, true, 2, false, NOW);
            assertEquals("FAILED", outcome.status());
            assertEquals(3, outcome.retryCount());
            assertNull(outcome.nextRetryAt());
            assertTrue(outcome.isTerminal());
        }

        @Test
        void 重试中途成功仍置SENT() {
            FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(true, true, 2, true, NOW);
            assertEquals("SENT", outcome.status());
            assertTrue(outcome.isTerminal());
        }
    }

    @Nested
    class IsDue {

        @Test
        void 空退避时间立即可推送() {
            assertTrue(FeishuPushRules.isDue(null, NOW));
        }

        @Test
        void 退避时间已过可推送() {
            assertTrue(FeishuPushRules.isDue(NOW.minusSeconds(1), NOW));
            assertTrue(FeishuPushRules.isDue(NOW, NOW));
        }

        @Test
        void 退避时间未到不推送() {
            assertFalse(FeishuPushRules.isDue(NOW.plusSeconds(1), NOW));
        }
    }
}
